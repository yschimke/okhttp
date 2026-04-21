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

import io.quiche4j.http3.Http3Header
import java.io.IOException
import java.net.InetSocketAddress
import java.util.Random
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.X509TrustManager
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.internal.platform.Platform
import okhttp3.internal.ws.WebSocketProtocol
import okhttp3.internal.ws.WebSocketReader
import okhttp3.internal.ws.WebSocketWriter
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.Source
import okio.Timeout
import okio.buffer

/**
 * An [okhttp3.WebSocket] served over an HTTP/3 extended CONNECT stream (RFC 9220).
 *
 * Layout per connection:
 *
 *  * One [QuicPooledConnection] from [Quiche4jEngine] (same pool as the request/response
 *    path shares).
 *  * One [QuicStream] on that connection carrying CONNECT + `:protocol: websocket` headers,
 *    then raw WebSocket frames (RFC 6455 framing including client masking — see RFC 8441
 *    §3, inherited by RFC 9220).
 *  * One dedicated daemon thread running the [WebSocketReader] loop; listener callbacks
 *    (`onMessage`, `onClosing`, etc.) fire on that thread.
 *  * Writes go through a `writeLock` — [WebSocketWriter] is not thread-safe.
 */
internal class Quiche4jWebSocket private constructor(
  private val pooled: QuicPooledConnection,
  private val stream: QuicStream,
  private val originalRequest: Request,
  private val listener: WebSocketListener,
  private val response: Response,
  private val writeTimeoutMillis: Long,
) : WebSocket {
  private val writerSink =
    QuicRequestBodySink(
      stream = stream,
      pool = pooled,
      writeTimeoutMillis = writeTimeoutMillis,
      // WebSocket keeps the QUIC stream open for both directions. We fin explicitly during
      // the close handshake; the buffered sink's close() must not fin on its own.
      sendFinOnClose = false,
    ).buffer()

  private val writer =
    WebSocketWriter(
      isClient = true,
      sink = writerSink,
      random = Random(),
      perMessageDeflate = false,
      noContextTakeover = false,
      minimumDeflateSize = Long.MAX_VALUE,
    )

  private val readerSource = WebSocketStreamSource(stream).buffer()

  private val reader =
    WebSocketReader(
      isClient = true,
      source = readerSource,
      frameCallback = InboundFrames(),
      perMessageDeflate = false,
      noContextTakeover = false,
    )

  private val writeLock = Any()
  private val closed = AtomicBoolean(false)
  private val failureReported = AtomicBoolean(false)

  @Volatile private var readerThread: Thread? = null

  override fun request(): Request = originalRequest

  override fun queueSize(): Long = 0L

  override fun send(text: String): Boolean = sendMessage(WebSocketProtocol.OPCODE_TEXT, text.encodeUtf8())

  override fun send(bytes: ByteString): Boolean = sendMessage(WebSocketProtocol.OPCODE_BINARY, bytes)

  private fun sendMessage(
    opcode: Int,
    data: ByteString,
  ): Boolean {
    if (closed.get()) return false
    return try {
      synchronized(writeLock) { writer.writeMessageFrame(opcode, data) }
      true
    } catch (e: IOException) {
      fail(e)
      false
    }
  }

  override fun close(
    code: Int,
    reason: String?,
  ): Boolean {
    if (!closed.compareAndSet(false, true)) return false
    return try {
      synchronized(writeLock) { writer.writeClose(code, reason?.encodeUtf8()) }
      // Fin the QUIC stream so the peer knows we're done. RFC 8441 §5 permits/encourages
      // this after the close frame.
      pooled.sendBodyChunk(stream, ByteArray(0), fin = true, timeoutMillis = writeTimeoutMillis)
      true
    } catch (e: IOException) {
      fail(e)
      true
    }
  }

  override fun cancel() {
    if (closed.compareAndSet(false, true)) {
      pooled.cancelStream(stream)
    }
  }

  private fun fail(e: IOException) {
    if (failureReported.compareAndSet(false, true)) {
      closed.set(true)
      try {
        listener.onFailure(this, e, response)
      } catch (_: Throwable) {
        // Don't let listener misbehaviour poison teardown.
      }
      try {
        pooled.cancelStream(stream)
      } catch (_: Throwable) { /* best effort */ }
      pooled.releaseStream(stream)
    }
  }

  private fun startReaderLoop() {
    val t =
      Thread({
        try {
          listener.onOpen(this, response)
        } catch (t: Throwable) {
          fail(t as? IOException ?: IOException(t))
          return@Thread
        }
        try {
          while (!closed.get()) {
            reader.processNextFrame()
          }
        } catch (e: IOException) {
          fail(e)
        } catch (t: Throwable) {
          fail(IOException(t))
        } finally {
          pooled.releaseStream(stream)
        }
      }, "quiche4j-ws-reader-${pooled.key.host}:${pooled.key.port}")
    t.isDaemon = true
    readerThread = t
    t.start()
  }

  private inner class InboundFrames : WebSocketReader.FrameCallback {
    override fun onReadMessage(text: String) {
      listener.onMessage(this@Quiche4jWebSocket, text)
    }

    override fun onReadMessage(bytes: ByteString) {
      listener.onMessage(this@Quiche4jWebSocket, bytes)
    }

    override fun onReadPing(payload: ByteString) {
      // RFC 6455 §5.5.3: respond with a pong carrying identical payload.
      try {
        synchronized(writeLock) { writer.writePong(payload) }
      } catch (e: IOException) {
        fail(e)
      }
    }

    override fun onReadPong(payload: ByteString) {
      // We don't send pings on a timer in this v1 implementation; no tracking needed.
    }

    override fun onReadClose(
      code: Int,
      reason: String,
    ) {
      val weInitiatedClose = !closed.compareAndSet(false, true)
      try {
        listener.onClosing(this@Quiche4jWebSocket, code, reason)
        if (!weInitiatedClose) {
          synchronized(writeLock) { writer.writeClose(code, null) }
          try {
            pooled.sendBodyChunk(stream, ByteArray(0), fin = true, timeoutMillis = writeTimeoutMillis)
          } catch (_: IOException) { /* peer may be gone */ }
        }
        listener.onClosed(this@Quiche4jWebSocket, code, reason)
      } catch (e: IOException) {
        fail(e)
      } catch (t: Throwable) {
        fail(IOException(t))
      }
    }
  }

  companion object {
    /**
     * Handshake an HTTP/3 extended CONNECT WebSocket against [originalRequest]'s origin.
     * Blocks until either the server responds with a 2xx (handshake success) or something
     * fails (DNS / QUIC handshake / 4xx / timeout). [Quiche4jWebSocketFactory] decides
     * whether to fallback or propagate the failure.
     */
    fun connect(
      engine: Quiche4jEngine,
      client: OkHttpClient,
      originalRequest: Request,
      listener: WebSocketListener,
    ): Quiche4jWebSocket {
      val httpUrl = originalRequest.url.asHttpsForH3()
      check(httpUrl.scheme == "https") {
        "quiche4j WebSocket requires wss:// or https://; got ${originalRequest.url.scheme}"
      }

      val host = httpUrl.host
      val port = httpUrl.port
      val addresses = client.dns.lookup(host)
      if (addresses.isEmpty()) throw IOException("No addresses for $host")
      val peer = InetSocketAddress(addresses.first(), port)

      val trustManager: X509TrustManager =
        client.x509TrustManager ?: Platform.get().platformTrustManager()
      val hostnameVerifier = client.hostnameVerifier

      val pooled =
        engine.acquire(
          host = host,
          port = port,
          peer = peer,
          trustManager = trustManager,
          hostnameVerifier = hostnameVerifier,
          handshakeTimeoutMillis = client.connectTimeoutMillis.toLong(),
          maxIdleTimeoutMillis = client.readTimeoutMillis.toLong(),
        )

      // RFC 9220 §4: CONNECT + :protocol=websocket. Sec-WebSocket-Key/Accept absent per
      // RFC 8441 §4 (inherited). Sec-WebSocket-Version defaults to 13.
      val reqHeaders = buildConnectHeaders(httpUrl, originalRequest.headers)

      val stream =
        pooled.openStream(
          headers = reqHeaders,
          body = null,
          streamingBody = true, // keep stream open for bidirectional frames
          timeoutMillis = client.connectTimeoutMillis.toLong(),
        )

      val readTimeoutMs = client.readTimeoutMillis.toLong()
      val responseHeaders =
        try {
          if (readTimeoutMs <= 0L) {
            stream.headersFuture.get()
          } else {
            stream.headersFuture.get(readTimeoutMs, TimeUnit.MILLISECONDS)
          }
        } catch (e: TimeoutException) {
          pooled.cancelStream(stream)
          pooled.releaseStream(stream)
          throw IOException("WebSocket handshake timed out after ${readTimeoutMs}ms", e)
        } catch (e: ExecutionException) {
          pooled.releaseStream(stream)
          val cause = e.cause ?: e
          throw cause as? IOException ?: IOException(cause)
        } catch (e: InterruptedException) {
          Thread.currentThread().interrupt()
          pooled.cancelStream(stream)
          pooled.releaseStream(stream)
          throw IOException("interrupted during WebSocket handshake")
        }

      val status = responseHeaders.status
      if (status !in 200..299) {
        pooled.cancelStream(stream)
        pooled.releaseStream(stream)
        throw IOException("WebSocket CONNECT rejected: HTTP $status")
      }

      val response =
        Response.Builder()
          .request(originalRequest)
          .protocol(Protocol.HTTP_3)
          .code(status)
          .message("")
          .headers(
            Headers.Builder().apply {
              responseHeaders.headers.forEach { (n, v) -> add(n, v) }
            }.build(),
          )
          .handshake(pooled.handshake)
          .build()

      val ws =
        Quiche4jWebSocket(
          pooled = pooled,
          stream = stream,
          originalRequest = originalRequest,
          listener = listener,
          response = response,
          writeTimeoutMillis = client.writeTimeoutMillis.toLong(),
        )
      ws.startReaderLoop()
      return ws
    }

    private fun HttpUrl.asHttpsForH3(): HttpUrl {
      val s = scheme
      val target = if (s == "wss") "https" else if (s == "ws") "http" else s
      return if (target == s) this else newBuilder().scheme(target).build()
    }

    private fun buildConnectHeaders(
      url: HttpUrl,
      userHeaders: Headers,
    ): List<Http3Header> {
      val result = mutableListOf<Http3Header>()
      result += Http3Header(":method", "CONNECT")
      result += Http3Header(":protocol", "websocket")
      result += Http3Header(":scheme", url.scheme)
      result +=
        Http3Header(
          ":authority",
          if (url.port == defaultPort(url.scheme)) url.host else "${url.host}:${url.port}",
        )
      result += Http3Header(":path", url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: ""))

      var sawVersion = false
      var sawOrigin = false
      for ((name, value) in userHeaders) {
        val lower = name.lowercase()
        if (lower.startsWith(":")) continue
        // Host is conveyed via :authority. Connection/Upgrade/Sec-WebSocket-Key/-Accept are
        // explicitly absent in the extended-CONNECT form (RFC 8441 §4, inherited by RFC 9220).
        if (lower == "host" || lower == "connection" || lower == "upgrade" ||
          lower == "sec-websocket-key" || lower == "sec-websocket-accept"
        ) {
          continue
        }
        if (lower == "sec-websocket-version") sawVersion = true
        if (lower == "origin") sawOrigin = true
        result += Http3Header(lower, value)
      }
      if (!sawVersion) result += Http3Header("sec-websocket-version", "13")
      if (!sawOrigin) {
        val portSuffix = if (url.port == defaultPort(url.scheme)) "" else ":${url.port}"
        result += Http3Header("origin", "${url.scheme}://${url.host}$portSuffix")
      }
      return result
    }

    private fun defaultPort(scheme: String): Int =
      when (scheme) {
        "https" -> 443
        "http" -> 80
        else -> -1
      }
  }
}

/**
 * Slim [Source] over a [QuicStream]'s body queue. Unlike [QuicBodySource] this one doesn't
 * produce EventListener callbacks — there's no [okhttp3.Call] attached to a WebSocket, so
 * the observability signals don't apply. Used by [Quiche4jWebSocket] to feed decoded bytes
 * into [WebSocketReader].
 */
private class WebSocketStreamSource(
  private val stream: QuicStream,
) : Source {
  @Volatile private var closed: Boolean = false
  private var currentChunk: Buffer? = null
  private var reachedEnd: Boolean = false

  override fun read(
    sink: Buffer,
    byteCount: Long,
  ): Long {
    check(byteCount >= 0) { "byteCount < 0: $byteCount" }
    if (closed) throw IOException("stream closed")
    if (reachedEnd && (currentChunk == null || currentChunk!!.size == 0L)) return -1L

    if (currentChunk == null || currentChunk!!.size == 0L) {
      when (val event =
        try {
          stream.bodyQueue.take()
        } catch (ie: InterruptedException) {
          Thread.currentThread().interrupt()
          throw IOException("interrupted while reading H/3 WebSocket body")
        }) {
        is BodyEvent.Bytes -> currentChunk = event.data
        is BodyEvent.End -> {
          reachedEnd = true
          return -1L
        }
        is BodyEvent.Error -> {
          reachedEnd = true
          throw event.cause
        }
      }
    }

    val chunk = currentChunk!!
    val toWrite = minOf(chunk.size, byteCount)
    // Segment-level transfer when aligned — the frame bytes the I/O thread wrote into
    // `chunk` move straight into `sink` for WebSocketReader to consume.
    sink.write(chunk, toWrite)
    return toWrite
  }

  override fun close() {
    closed = true
  }

  override fun timeout(): Timeout = Timeout.NONE
}
