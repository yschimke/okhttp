/*
 * Copyright (C) 2026 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package okhttp3.quiche4j

import java.util.ArrayDeque
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * A [WebSocket.Factory] that attempts [primary] first and falls back to [secondary] if
 * the primary factory's WebSocket fails before [WebSocketListener.onOpen] fires. Once the
 * primary has opened, failures propagate to the caller as normal — failover is a
 * *connect-time* behaviour only.
 *
 * Composable with any two [WebSocket.Factory] implementations; the typical use with
 * quiche4j is:
 *
 * ```kotlin
 * val quiche = Quiche4jWebSocketFactory.Builder(okHttpClient).build()
 * val factory = FailoverWebSocketFactory(primary = quiche, secondary = okHttpClient)
 * val ws = factory.newWebSocket(request, listener)
 * ```
 *
 * The [Request] passed through to [secondary] is unchanged from what the caller supplied,
 * so an `wss://` URL remains `wss://` — the secondary factory (e.g. an `OkHttpClient`) is
 * expected to accept that URL on its own terms.
 *
 * The returned [WebSocket] buffers outgoing messages until either factory opens; all
 * messages sent in the meantime are forwarded to whichever transport wins. Writes and
 * closes after the commit point behave exactly like the underlying transport.
 */
class FailoverWebSocketFactory(
  private val primary: WebSocket.Factory,
  private val secondary: WebSocket.Factory,
) : WebSocket.Factory {
  override fun newWebSocket(
    request: Request,
    listener: WebSocketListener,
  ): WebSocket {
    val proxy = FailoverWebSocket(request, listener, secondary)
    proxy.startPrimary(primary)
    return proxy
  }
}

internal class FailoverWebSocket(
  private val originalRequest: Request,
  private val userListener: WebSocketListener,
  private val secondary: WebSocket.Factory,
) : WebSocket {
  private val lock = Any()

  // The currently-active delegate, once we've committed to one transport. Before that,
  // the "pending" queue captures user ops and `primary` is the in-flight attempt.
  @Volatile private var delegate: WebSocket? = null

  // The WebSocket returned by the first factory — tracked so cancel() reaches it even
  // before we've committed.
  @Volatile private var primaryInFlight: WebSocket? = null

  // True once either factory has fired onOpen. After this, failover is off the table.
  @Volatile private var opened: Boolean = false

  @Volatile private var closed: Boolean = false

  private val pending = ArrayDeque<Pending>()
  private var pendingBytes: Long = 0L

  internal fun startPrimary(primary: WebSocket.Factory) {
    val listener =
      object : WebSocketListener() {
        override fun onOpen(
          webSocket: WebSocket,
          response: Response,
        ) {
          commit(webSocket, response)
        }

        override fun onMessage(
          webSocket: WebSocket,
          text: String,
        ) {
          userListener.onMessage(this@FailoverWebSocket, text)
        }

        override fun onMessage(
          webSocket: WebSocket,
          bytes: ByteString,
        ) {
          userListener.onMessage(this@FailoverWebSocket, bytes)
        }

        override fun onClosing(
          webSocket: WebSocket,
          code: Int,
          reason: String,
        ) {
          userListener.onClosing(this@FailoverWebSocket, code, reason)
        }

        override fun onClosed(
          webSocket: WebSocket,
          code: Int,
          reason: String,
        ) {
          userListener.onClosed(this@FailoverWebSocket, code, reason)
        }

        override fun onFailure(
          webSocket: WebSocket,
          t: Throwable,
          response: Response?,
        ) {
          val shouldFailover: Boolean =
            synchronized(lock) {
              // Pre-open failures are failover-eligible. Post-open failures just propagate.
              !opened && !closed
            }
          if (shouldFailover) {
            startSecondary()
          } else {
            userListener.onFailure(this@FailoverWebSocket, t, response)
          }
        }
      }
    primaryInFlight = primary.newWebSocket(originalRequest, listener)
  }

  private fun startSecondary() {
    val listener =
      object : WebSocketListener() {
        override fun onOpen(
          webSocket: WebSocket,
          response: Response,
        ) {
          commit(webSocket, response)
        }

        override fun onMessage(
          webSocket: WebSocket,
          text: String,
        ) {
          userListener.onMessage(this@FailoverWebSocket, text)
        }

        override fun onMessage(
          webSocket: WebSocket,
          bytes: ByteString,
        ) {
          userListener.onMessage(this@FailoverWebSocket, bytes)
        }

        override fun onClosing(
          webSocket: WebSocket,
          code: Int,
          reason: String,
        ) {
          userListener.onClosing(this@FailoverWebSocket, code, reason)
        }

        override fun onClosed(
          webSocket: WebSocket,
          code: Int,
          reason: String,
        ) {
          userListener.onClosed(this@FailoverWebSocket, code, reason)
        }

        override fun onFailure(
          webSocket: WebSocket,
          t: Throwable,
          response: Response?,
        ) {
          userListener.onFailure(this@FailoverWebSocket, t, response)
        }
      }
    try {
      val secondaryWs = secondary.newWebSocket(originalRequest, listener)
      // Track in case cancel() fires before secondary's onOpen.
      primaryInFlight = secondaryWs
    } catch (t: Throwable) {
      userListener.onFailure(this, t, null)
    }
  }

  private fun commit(
    winner: WebSocket,
    response: Response,
  ) {
    val drain: List<Pending>
    val closeAfterDrain: Pending.Close?
    synchronized(lock) {
      if (opened) return // raced — first winner already set
      opened = true
      delegate = winner
      drain = pending.toList()
      closeAfterDrain = drain.filterIsInstance<Pending.Close>().firstOrNull()
      pending.clear()
      pendingBytes = 0L
    }
    userListener.onOpen(this, response)
    if (closed) {
      // close() arrived before commit. Honour it now.
      winner.cancel()
      return
    }
    for (op in drain) {
      when (op) {
        is Pending.SendText -> winner.send(op.text)
        is Pending.SendBytes -> winner.send(op.bytes)
        is Pending.Close -> {
          winner.close(op.code, op.reason)
          return
        }
      }
    }
    @Suppress("UNUSED_EXPRESSION") closeAfterDrain // kept to document intent in comments above
  }

  override fun request(): Request = originalRequest

  override fun queueSize(): Long =
    synchronized(lock) {
      delegate?.queueSize() ?: pendingBytes
    }

  override fun send(text: String): Boolean = enqueueOrForward(Pending.SendText(text))

  override fun send(bytes: ByteString): Boolean = enqueueOrForward(Pending.SendBytes(bytes))

  override fun close(
    code: Int,
    reason: String?,
  ): Boolean {
    synchronized(lock) {
      if (closed) return false
      closed = true
      val d = delegate
      if (d != null) return d.close(code, reason)
      pending += Pending.Close(code, reason)
      return true
    }
  }

  override fun cancel() {
    synchronized(lock) {
      closed = true
      val d = delegate
      if (d != null) {
        d.cancel()
      } else {
        primaryInFlight?.cancel()
        pending.clear()
        pendingBytes = 0
      }
    }
  }

  private fun enqueueOrForward(op: Pending): Boolean {
    synchronized(lock) {
      if (closed) return false
      val d = delegate
      if (d != null) {
        return when (op) {
          is Pending.SendText -> d.send(op.text)
          is Pending.SendBytes -> d.send(op.bytes)
          else -> false
        }
      }
      pending += op
      pendingBytes +=
        when (op) {
          is Pending.SendText -> op.text.length.toLong()
          is Pending.SendBytes -> op.bytes.size.toLong()
          else -> 0L
        }
      return true
    }
  }

  private sealed class Pending {
    class SendText(
      val text: String,
    ) : Pending()

    class SendBytes(
      val bytes: ByteString,
    ) : Pending()

    class Close(
      val code: Int,
      val reason: String?,
    ) : Pending()
  }
}
