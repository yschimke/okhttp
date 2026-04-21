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

import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.Executor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * The [WebSocket] returned by [Quiche4jWebSocketFactory.newWebSocket] while the H/3
 * extended-CONNECT handshake is in-flight. Once the handshake completes, it switches to
 * forwarding every operation (and listener callback) to the underlying
 * [Quiche4jWebSocket]; until then, outgoing messages are queued and drained on connect.
 *
 * This mirrors OkHttp's own `RealWebSocket` contract: `send()` after `newWebSocket()`
 * returns is permitted and buffered, never blocks the caller on the handshake.
 */
internal class ConnectingQuiche4jWebSocket(
  private val engine: Quiche4jEngine,
  private val client: OkHttpClient,
  private val httpsRecordResolver: HttpsServiceRecordResolver?,
  private val altSvcCache: AltSvcCache,
  private val request: Request,
  private val userListener: WebSocketListener,
  private val readerExecutor: Executor,
) : WebSocket {
  private val lock = Any()

  // Set once either (a) H/3 connects, or (b) we close/fail and there'll never be a delegate.
  @Volatile private var delegate: Quiche4jWebSocket? = null

  @Volatile private var closed: Boolean = false

  // Reason we're closed (null = still live or success-closed).
  @Volatile private var failure: Throwable? = null

  private val pending = ArrayDeque<Pending>()
  private var pendingBytes: Long = 0L

  // A listener that re-emits events with *this* as the WebSocket, so the user sees the
  // object they were handed from newWebSocket, not the inner Quiche4jWebSocket.
  private val proxiedListener =
    object : WebSocketListener() {
      override fun onOpen(
        webSocket: WebSocket,
        response: Response,
      ) {
        userListener.onOpen(this@ConnectingQuiche4jWebSocket, response)
      }

      override fun onMessage(
        webSocket: WebSocket,
        text: String,
      ) {
        userListener.onMessage(this@ConnectingQuiche4jWebSocket, text)
      }

      override fun onMessage(
        webSocket: WebSocket,
        bytes: ByteString,
      ) {
        userListener.onMessage(this@ConnectingQuiche4jWebSocket, bytes)
      }

      override fun onClosing(
        webSocket: WebSocket,
        code: Int,
        reason: String,
      ) {
        userListener.onClosing(this@ConnectingQuiche4jWebSocket, code, reason)
      }

      override fun onClosed(
        webSocket: WebSocket,
        code: Int,
        reason: String,
      ) {
        userListener.onClosed(this@ConnectingQuiche4jWebSocket, code, reason)
      }

      override fun onFailure(
        webSocket: WebSocket,
        t: Throwable,
        response: Response?,
      ) {
        userListener.onFailure(this@ConnectingQuiche4jWebSocket, t, response)
      }
    }

  internal fun doConnect() {
    // Short-circuit the cases where H/3 isn't viable before touching the engine:
    //   * ForceOff → user explicitly opted out of H/3.
    //   * Discovery configured AND neither HTTPS record nor Alt-Svc advertises h3 AND no
    //     Force tag on the request → origin hasn't told us it speaks h3, so trying would
    //     waste a round-trip.
    // Both surface as NoHttp3Route — a subtype of IOException that FailoverWebSocketFactory
    // treats as "skip primary, go straight to secondary".
    val preference = Http3Preference.of(request)
    if (preference is Http3Preference.ForceOff) {
      reportFailure(NoHttp3Route("Http3Preference.ForceOff on this request"))
      return
    }
    val viable =
      Http3Decision.shouldAttempt(
        request = request,
        dns = client.dns,
        httpsResolver = httpsRecordResolver,
        altSvcCache = altSvcCache,
      )
    if (!viable) {
      reportFailure(NoHttp3Route("origin does not advertise h3 via Alt-Svc or HTTPS record"))
      return
    }

    try {
      val ws =
        Quiche4jWebSocket.connect(
          engine = engine,
          client = client,
          originalRequest = request,
          listener = proxiedListener,
          readerExecutor = readerExecutor,
        )
      val drainSnapshot: List<Pending>
      val closeAfter: Pending.Close?
      synchronized(lock) {
        if (closed) {
          // The user cancelled or called close before we got here. Honour that.
          ws.cancel()
          delegate = ws
          return
        }
        delegate = ws
        drainSnapshot = pending.toList()
        pending.clear()
        pendingBytes = 0
        closeAfter = drainSnapshot.filterIsInstance<Pending.Close>().firstOrNull()
      }
      for (op in drainSnapshot) {
        when (op) {
          is Pending.SendText -> ws.send(op.text)
          is Pending.SendBytes -> ws.send(op.bytes)
          is Pending.Close -> {
            ws.close(op.code, op.reason)
            // Further ops in the snapshot are undefined per RFC 6455 — stop here.
            return
          }
        }
      }
      // If no explicit close was queued, leave things running — the reader thread carries
      // on and the user can still send more messages against `delegate`.
    } catch (e: Exception) {
      val ioe = e as? IOException ?: IOException(e)
      reportFailure(ioe)
    }
  }

  private fun reportFailure(cause: IOException) {
    synchronized(lock) {
      closed = true
      failure = cause
      pending.clear()
      pendingBytes = 0
    }
    try {
      userListener.onFailure(this, cause, null)
    } catch (_: Throwable) {
      // Listener exceptions shouldn't break teardown.
    }
  }

  override fun request(): Request = request

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
      val d = delegate
      if (d != null) {
        closed = true
        return d.close(code, reason)
      }
      // Pre-connect: queue the close and flip the closed flag so further sends bounce.
      closed = true
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
          is Pending.SendText -> op.text.length.toLong() // approximate — matches RealWebSocket
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
