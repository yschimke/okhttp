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

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * A [WebSocket.Factory] that opens WebSockets over HTTP/3 using the quiche4j transport
 * (RFC 9220 extended CONNECT on an HTTP/3 stream). Falls back to a user-supplied
 * [WebSocket.Factory] — typically an [OkHttpClient] — for cases where HTTP/3 can't be
 * used: plaintext `ws://`, [Http3Preference.ForceOff], handshake failure on
 * `Http3Preference.Force(fallback = true)` or the default, etc.
 *
 * Unlike [OkHttpClient.newWebSocket], this implementation performs the extended-CONNECT
 * handshake **synchronously** inside [newWebSocket]. That is intentional: a blocking
 * handshake lets us decide whether to use the HTTP/3 stream or the fallback before
 * returning, without needing an "upgrade-in-flight" proxy wrapper. Callers that require
 * async behaviour should wrap [newWebSocket] in their own executor.
 *
 * Typical wiring:
 * ```kotlin
 * val client = OkHttpClient.Builder().build()
 * val factory = Quiche4jWebSocketFactory.Builder()
 *   .client(client)            // supplies trust / hostname / timeouts
 *   .fallback(client)          // called when H/3 isn't available
 *   .build()
 * val ws = factory.newWebSocket(request, listener)
 * ```
 *
 * The HTTP/3 path does not flow through [Quiche4jInterceptor], and deliberately so — a
 * WebSocket is not a request/response pair that Cache/Bridge interceptors should see. It
 * does reuse the same [Quiche4jEngine] shape (pooled QUIC connection per origin) so a
 * page that opens multiple WebSockets to one host shares a single handshake.
 */
class Quiche4jWebSocketFactory internal constructor(
  internal val engine: Quiche4jEngine,
  internal val client: OkHttpClient,
  internal val fallback: WebSocket.Factory,
) : WebSocket.Factory {
  override fun newWebSocket(
    request: Request,
    listener: WebSocketListener,
  ): WebSocket {
    val preference = Http3Preference.of(request)

    // Cases that can't use HTTP/3: fall back immediately without attempting a handshake.
    // ws:// is plaintext; QUIC requires TLS. Users who want H/3 WebSockets must use wss://.
    val scheme = request.url.scheme
    val isSecure = scheme == "https" || scheme == "wss"
    if (!isSecure || preference is Http3Preference.ForceOff) {
      return fallback.newWebSocket(request, listener)
    }

    return try {
      Quiche4jWebSocket.connect(engine, client, request, listener)
    } catch (e: Exception) {
      // Force(fallback = false) → propagate; otherwise, try the fallback.
      if (preference is Http3Preference.Force && !preference.fallback) throw e
      fallback.newWebSocket(request, listener)
    }
  }

  class Builder {
    private var client: OkHttpClient? = null
    private var fallback: WebSocket.Factory? = null

    /**
     * The [OkHttpClient] whose TLS trust, hostname verifier, and timeouts configure the
     * HTTP/3 handshake. Defaults to a new `OkHttpClient.Builder().build()` if unset.
     */
    fun client(client: OkHttpClient) = apply { this.client = client }

    /**
     * The [WebSocket.Factory] to delegate to when HTTP/3 can't be used. Defaults to the
     * supplied [client] — which is exactly what most callers want, since `OkHttpClient`
     * itself implements [WebSocket.Factory] via its standard HTTP/1.1-upgrade path.
     */
    fun fallback(fallback: WebSocket.Factory) = apply { this.fallback = fallback }

    fun build(): Quiche4jWebSocketFactory {
      val c = client ?: OkHttpClient.Builder().build()
      val f = fallback ?: c
      return Quiche4jWebSocketFactory(
        engine = Quiche4jEngine(),
        client = c,
        fallback = f,
      )
    }
  }
}
