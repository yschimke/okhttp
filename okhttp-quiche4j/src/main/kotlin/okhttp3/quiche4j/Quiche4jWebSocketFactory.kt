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

import java.util.concurrent.Executor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * A [WebSocket.Factory] that opens WebSockets over HTTP/3 using the quiche4j transport
 * (RFC 9220 extended CONNECT on an HTTP/3 stream).
 *
 * This factory is deliberately **HTTP/3-only** — there is no built-in fallback to
 * HTTP/1.1. If you need the "try H/3 first, fall back to H/1.1 on connect failure"
 * behaviour, compose this factory with an [OkHttpClient] (itself a [WebSocket.Factory])
 * via [FailoverWebSocketFactory]:
 *
 * ```kotlin
 * val quiche = Quiche4jWebSocketFactory.Builder().client(okHttpClient).build()
 * val factory = FailoverWebSocketFactory(primary = quiche, secondary = okHttpClient)
 * val ws = factory.newWebSocket(request, listener)
 * ```
 *
 * [newWebSocket] is **non-blocking**, matching [OkHttpClient.newWebSocket]. The returned
 * [WebSocket] starts in a "connecting" state: the extended-CONNECT handshake runs on the
 * supplied connect executor (defaulting to the client's Dispatcher executor), and messages
 * sent via [WebSocket.send] before the handshake completes are queued. On success,
 * [WebSocketListener.onOpen] fires and queued messages are drained. On failure,
 * [WebSocketListener.onFailure] fires and no further callbacks run.
 *
 * Typical wiring without failover:
 *
 * ```kotlin
 * val client = OkHttpClient.Builder().build()
 * val factory = Quiche4jWebSocketFactory.Builder().client(client).build()
 * val ws = factory.newWebSocket(request, listener)
 * ```
 */
class Quiche4jWebSocketFactory internal constructor(
  internal val engine: Quiche4jEngine,
  internal val client: OkHttpClient,
  internal val connectExecutor: Executor,
) : WebSocket.Factory {
  override fun newWebSocket(
    request: Request,
    listener: WebSocketListener,
  ): WebSocket {
    // QUIC requires TLS. Plaintext ws:// is a configuration error for this factory —
    // callers who need ws:// support should use a FailoverWebSocketFactory wrapping an
    // OkHttpClient, so the ws:// request reaches the HTTP/1.1 transport.
    val scheme = request.url.scheme
    require(scheme == "wss" || scheme == "https") {
      "Quiche4jWebSocketFactory requires wss:// or https://, got $scheme"
    }
    val proxy = ConnectingQuiche4jWebSocket(engine, client, request, listener)
    connectExecutor.execute { proxy.doConnect() }
    return proxy
  }

  class Builder {
    private var client: OkHttpClient? = null
    private var connectExecutor: Executor? = null

    /**
     * The [OkHttpClient] whose TLS trust, hostname verifier, DNS, and timeouts configure
     * the HTTP/3 handshake. Defaults to a new `OkHttpClient.Builder().build()`.
     */
    fun client(client: OkHttpClient) = apply { this.client = client }

    /**
     * The executor that runs the H/3 handshake. Defaults to the [client]'s Dispatcher
     * executor — the same pool that [OkHttpClient] uses for async calls.
     */
    fun connectExecutor(executor: Executor) = apply { this.connectExecutor = executor }

    fun build(): Quiche4jWebSocketFactory {
      val c = client ?: OkHttpClient.Builder().build()
      val e: Executor = connectExecutor ?: c.dispatcher.executorService
      return Quiche4jWebSocketFactory(
        engine = Quiche4jEngine(),
        client = c,
        connectExecutor = e,
      )
    }
  }
}
