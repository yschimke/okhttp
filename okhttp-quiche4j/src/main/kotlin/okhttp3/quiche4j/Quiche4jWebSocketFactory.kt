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
 * val quiche = Quiche4jWebSocketFactory.Builder(okHttpClient).build()
 * val factory = FailoverWebSocketFactory(primary = quiche, secondary = okHttpClient)
 * val ws = factory.newWebSocket(request, listener)
 * ```
 *
 * The factory consults the same HTTP/3 discovery signals as [Quiche4jInterceptor]:
 *
 *  * [Http3Preference] tag on the request — [Http3Preference.Force] bypasses discovery,
 *    [Http3Preference.ForceOff] skips H/3 entirely.
 *  * HTTPS DNS record (RFC 9460), either via the [OkHttpClient]'s [okhttp3.Dns]
 *    implementing [HttpsAware] or an explicit [HttpsServiceRecordResolver].
 *  * Alt-Svc cache seeded by prior interceptor responses.
 *
 * When the signals say "h3 not advertised here", the factory skips the handshake and
 * fires [WebSocketListener.onFailure] with [NoHttp3Route]. That is exactly the shape
 * [FailoverWebSocketFactory] needs to transparently hand off to its secondary.
 *
 * [newWebSocket] is **non-blocking**, matching [OkHttpClient.newWebSocket]. The returned
 * [WebSocket] starts in a "connecting" state: the extended-CONNECT handshake runs on the
 * supplied connect executor (defaulting to the client's Dispatcher executor), and
 * messages sent before the handshake completes are queued. On success,
 * [WebSocketListener.onOpen] fires and queued messages drain. On failure,
 * [WebSocketListener.onFailure] fires and no further callbacks run.
 */
class Quiche4jWebSocketFactory internal constructor(
  internal val engine: Quiche4jEngine,
  internal val client: OkHttpClient,
  internal val connectExecutor: Executor,
  internal val httpsRecordResolver: HttpsServiceRecordResolver?,
  internal val altSvcCache: AltSvcCache,
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

    val proxy =
      ConnectingQuiche4jWebSocket(
        engine = engine,
        client = client,
        httpsRecordResolver = httpsRecordResolver,
        altSvcCache = altSvcCache,
        request = request,
        userListener = listener,
        // The reader loop reuses the same executor as the handshake. One configurable
        // pool, one shutdown hook — no per-WebSocket `new Thread(...)`.
        readerExecutor = connectExecutor,
      )
    connectExecutor.execute { proxy.doConnect() }
    return proxy
  }

  /**
   * @param client The [OkHttpClient] whose TLS trust, hostname verifier, DNS, and timeouts
   *   configure the HTTP/3 handshake. Deliberately required: this factory reuses the
   *   client's Dispatcher executor and connection pool, and we don't want to silently
   *   create (and therefore leak) one on the caller's behalf. Reuse an existing client
   *   so its `dispatcher.executorService.shutdown()` / `connectionPool.evictAll()`
   *   lifecycle covers both surfaces.
   */
  class Builder(
    private val client: OkHttpClient,
  ) {
    private var connectExecutor: Executor? = null
    private var httpsRecordResolver: HttpsServiceRecordResolver? = null
    private var altSvcCache: AltSvcCache = InMemoryAltSvcCache()

    /**
     * The executor that runs the H/3 handshake **and** the per-WebSocket reader loop.
     * Defaults to the client's Dispatcher executor — the same pool that [OkHttpClient]
     * uses for async calls, so the caller already owns its shutdown.
     *
     * The reader loop is long-running: one task per live WebSocket, pinned for the
     * duration. The default Dispatcher executor is an unbounded cached pool so this is
     * safe. If you supply a bounded executor, size it so `<max concurrent handshakes> +
     * <max live WebSockets>` fits — otherwise the handshake will starve behind reader
     * loops.
     */
    fun connectExecutor(executor: Executor): Builder = apply { this.connectExecutor = executor }

    /**
     * Optional HTTPS DNS record (RFC 9460) resolver. If set, the factory consults it to
     * confirm the origin advertises `"h3"` before attempting the handshake. Leaving this
     * null (the default) is fine when the [OkHttpClient]'s [okhttp3.Dns] implements
     * [HttpsAware], or when you pair this factory with [FailoverWebSocketFactory] and
     * don't mind a speculative attempt.
     */
    fun httpsServiceRecordResolver(resolver: HttpsServiceRecordResolver?): Builder =
      apply { this.httpsRecordResolver = resolver }

    /**
     * Alt-Svc cache consulted before attempting the H/3 handshake and (ideally) shared
     * with a [Quiche4jInterceptor] on the same [OkHttpClient] so both surfaces see the
     * same advertised alternatives. Defaults to [InMemoryAltSvcCache].
     */
    fun altSvcCache(cache: AltSvcCache): Builder = apply { this.altSvcCache = cache }

    fun build(): Quiche4jWebSocketFactory {
      val e: Executor = connectExecutor ?: client.dispatcher.executorService
      return Quiche4jWebSocketFactory(
        engine = Quiche4jEngine(),
        client = client,
        connectExecutor = e,
        httpsRecordResolver = httpsRecordResolver,
        altSvcCache = altSvcCache,
      )
    }
  }
}
