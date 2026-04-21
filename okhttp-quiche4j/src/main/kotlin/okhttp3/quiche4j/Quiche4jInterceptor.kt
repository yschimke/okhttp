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

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.internal.cache.CacheInterceptor
import okhttp3.internal.http.BridgeInterceptor
import okhttp3.internal.http.RealInterceptorChain

/**
 * An OkHttp application interceptor that short-circuits the normal HTTP/1.x/2 stack and performs
 * the fetch over HTTP/3 (QUIC) using [quiche4j][io.quiche4j].
 *
 * This mirrors the `cronet-transport-for-okhttp` design: add it as the **last application
 * interceptor**, and every request that reaches it is handled by the QUIC engine. The response is
 * returned as a normal `okhttp3.Response` with [Protocol.HTTP_3][okhttp3.Protocol.HTTP_3].
 *
 * Caching is preserved by running a nested interceptor chain that includes
 * [BridgeInterceptor] (host/user-agent/cookies) and [CacheInterceptor] so cache reads/writes
 * still happen exactly as they would for a normal OkHttp call. Only the final "call the network"
 * step is replaced with the quiche4j-backed [Quiche4jCallServer].
 *
 * TLS trust and hostname verification follow the `OkHttpClient`'s configured
 * [X509TrustManager][javax.net.ssl.X509TrustManager] (defaults to the platform trust manager)
 * and [HostnameVerifier][javax.net.ssl.HostnameVerifier] (defaults to
 * [okhttp3.internal.tls.OkHostnameVerifier]). All verification runs in Java after the QUIC
 * handshake; quiche's BoringSSL verification is disabled.
 *
 * See `PLAN.md` in this module for the full three-stage roadmap.
 */
class Quiche4jInterceptor internal constructor(
  internal val engine: Quiche4jEngine,
  internal val httpsRecordResolver: HttpsServiceRecordResolver?,
  val altSvcCache: AltSvcCache,
  /**
   * The terminal interceptor at the bottom of the inner chain — the one that actually talks
   * QUIC. Tests replace this with a stub so they can exercise the inner
   * [BridgeInterceptor]/[CacheInterceptor] wiring without needing an H/3 server.
   */
  internal val terminal: Interceptor,
) : Interceptor {
  /** Visible to tests: size of the pooled-connection cache. */
  internal val pooledConnectionCount: Int
    get() = engine.pooledConnectionCount()

  override fun intercept(chain: Interceptor.Chain): Response {
    // QUIC requires TLS — plaintext http:// URLs fall through to OkHttp's standard transport.
    // We intentionally do not throw here; the interceptor should be a no-op when it can't help.
    val outerRequest = chain.request()
    if (outerRequest.url.scheme != "https") {
      return chain.proceed(outerRequest)
    }

    val preference = Http3Preference.of(outerRequest)
    if (preference is Http3Preference.ForceOff) {
      return chain.proceed(outerRequest).also { updateAltSvcFromResponse(it) }
    }

    // Soft-cast: a test-wrapped chain or some future wrapping interceptor might pass us a
    // non-RealInterceptorChain. In that case we can't build the inner chain (nor pull dns
    // off it), so fall through to the standard transport. Better than a CCE from the user's
    // call site.
    val realChain =
      chain as? RealInterceptorChain
        ?: return chain.proceed(outerRequest).also { updateAltSvcFromResponse(it) }

    // Discovery decision — HTTPS record (via HttpsAware Dns or explicit resolver) + Alt-Svc
    // cache + the request's Http3Preference tag. Shared with Quiche4jWebSocketFactory so the
    // WebSocket surface honours the same signals.
    val shouldHandle =
      Http3Decision.shouldAttempt(
        request = outerRequest,
        dns = realChain.dns,
        httpsResolver = httpsRecordResolver,
        altSvcCache = altSvcCache,
      )
    if (!shouldHandle) {
      // Fall through but still scan the response's Alt-Svc header so the *next* call can prefer
      // HTTP/3 if the origin starts advertising it.
      return chain.proceed(outerRequest).also { updateAltSvcFromResponse(it) }
    }

    val call = realChain.call

    // BridgeInterceptor and CacheInterceptor pick up cookie jar/cache from the chain directly.
    val innerInterceptors =
      listOf(
        BridgeInterceptor(),
        CacheInterceptor(),
        terminal,
      )

    val innerChain =
      RealInterceptorChain(
        call = call,
        interceptors = innerInterceptors,
        index = 0,
        exchange = null,
        request = chain.request(),
      )

    return try {
      innerChain.proceed(chain.request()).also { updateAltSvcFromResponse(it) }
    } catch (e: Exception) {
      // Http3Preference.Force(fallback=true) — the caller asked for H/3 but also asked us to
      // try the standard stack on failure. Intentionally broader than `IOException`: quiche's
      // JNI layer can raise `RuntimeException` (LinkageError surfacing as such, stale native
      // state, misbehaving interceptors); callers who wrote `fallback = true` asked us to
      // recover from H/3 trouble regardless of the exact type. Errors (OOM, LinkageError)
      // still bubble. If the call was canceled, skip the retry — the user asked to stop.
      if (
        preference is Http3Preference.Force &&
        preference.fallback &&
        !call.isCanceled()
      ) {
        chain.proceed(outerRequest).also { updateAltSvcFromResponse(it) }
      } else {
        throw e
      }
    }
  }

  /** Inspect a completed response's `Alt-Svc` header(s) and update the cache. */
  private fun updateAltSvcFromResponse(response: Response) {
    val altSvc = response.headers("Alt-Svc")
    if (altSvc.isEmpty()) return
    val origin = AltSvcOrigin.of(response.request.url)
    val combined = altSvc.joinToString(separator = ", ")
    val parsed = AltSvcEntry.parseHeader(combined)
    if (parsed.isEmpty() && combined.trim().equals("clear", ignoreCase = true)) {
      altSvcCache.remove(origin)
    } else if (parsed.isNotEmpty()) {
      altSvcCache.put(origin, parsed)
    }
  }

  class Builder {
    private var httpsRecordResolver: HttpsServiceRecordResolver? = null
    private var altSvcCache: AltSvcCache = InMemoryAltSvcCache()

    /**
     * Optional HTTPS DNS record (RFC 9460) resolver. If set, the interceptor looks up the
     * origin's HTTPS record before taking over the call. If no record advertises `"h3"` in its
     * ALPN list, or if the lookup fails, the interceptor calls `chain.proceed()` and lets
     * OkHttp's standard H/1.1/H/2 stack handle the request. If unset (default), every HTTPS
     * request reaching this interceptor is attempted over HTTP/3.
     */
    fun httpsServiceRecordResolver(resolver: HttpsServiceRecordResolver?) =
      apply { this.httpsRecordResolver = resolver }

    /**
     * Set the Alt-Svc cache. Defaults to [InMemoryAltSvcCache]. Pass a persistent
     * implementation (e.g. one that mirrors [AltSvcCache.snapshot] to disk) to survive
     * process restarts.
     */
    fun altSvcCache(cache: AltSvcCache) = apply { this.altSvcCache = cache }

    fun build(): Quiche4jInterceptor {
      val engine = Quiche4jEngine()
      return Quiche4jInterceptor(
        engine = engine,
        httpsRecordResolver = httpsRecordResolver,
        altSvcCache = altSvcCache,
        terminal = Quiche4jCallServer(engine),
      )
    }
  }
}
