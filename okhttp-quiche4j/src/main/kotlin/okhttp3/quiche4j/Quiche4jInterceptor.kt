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
 * the fetch over HTTP/3 (QUIC) using [quiche4j](https://github.com/yschimke/quiche4j).
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
 * See `PLAN.md` in this module for the full three-stage roadmap.
 */
class Quiche4jInterceptor private constructor(
  internal val engine: Quiche4jEngine,
  internal val httpsRecordResolver: HttpsServiceRecordResolver?,
  val altSvcCache: AltSvcCache,
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

    val realChain = chain as RealInterceptorChain

    // Optional HTTPS-record check. Two sources, in priority order:
    //   1. The OkHttpClient's Dns, if it implements HttpsAware. Preferred because the lookup has
    //      already happened as part of the regular A/AAAA query.
    //   2. The explicit resolver passed on the Builder. Used by callers who don't want to swap
    //      their Dns wholesale.
    // If either source returns a record that doesn't advertise "h3", fall through to OkHttp's
    // normal stack. If the lookup fails or returns no records, we also fall through — absence
    // of a record doesn't mean "no H/3", but neither does it tell us anything useful, so the
    // safe default is to not interfere when discovery was explicitly requested.
    val hostname = outerRequest.url.host
    val dns = realChain.dns
    val discoveryEnabled = dns is HttpsAware || httpsRecordResolver != null
    val record: HttpsServiceRecord? =
      when {
        dns is HttpsAware -> {
          // Trigger the (parallel) A/AAAA + HTTPS lookup on the shared Dns so the cache is
          // populated by the time we query it. If we end up falling through, OkHttp's core will
          // hit the same Dns and reuse whatever caching the underlying system provides.
          try {
            dns.lookup(hostname)
          } catch (_: Throwable) {
            // DNS failure — best-effort. chain.proceed() will re-attempt and surface the error.
          }
          dns.getHttpsServiceRecord(hostname)
        }
        httpsRecordResolver != null ->
          try {
            httpsRecordResolver.lookup(hostname).firstOrNull()
          } catch (_: Throwable) {
            null
          }
        else -> null
      }
    // Decision: prefer HTTP/3 when any of the following is true:
    //   (a) discovery is disabled (no HTTPS-record resolver, no HttpsAware Dns, no cached
    //       Alt-Svc) — the caller asked for HTTP/3 by adding this interceptor
    //   (b) HTTPS record explicitly advertises h3
    //   (c) Alt-Svc cache has an unexpired h3 entry for this origin
    val origin = AltSvcOrigin.of(outerRequest.url)
    val altSvcHasH3 = altSvcCache.get(origin).any { it.protocolId.equals("h3", true) }
    val shouldHandle =
      when {
        !discoveryEnabled -> true
        record?.supportsHttp3 == true -> true
        altSvcHasH3 -> true
        else -> false
      }
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
        Quiche4jCallServer(engine),
      )

    val innerChain =
      RealInterceptorChain(
        call = call,
        interceptors = innerInterceptors,
        index = 0,
        exchange = null,
        request = chain.request(),
      )

    return innerChain.proceed(chain.request()).also { updateAltSvcFromResponse(it) }
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
    private var maxIdleTimeoutMillis: Long = 30_000
    private var userAgent: String = "okhttp-quiche4j"
    private var trustedCaPemFile: String? = null
    private var trustedCaDirectory: String? = null
    private var allowInsecure: Boolean = false
    private var httpsRecordResolver: HttpsServiceRecordResolver? = null
    private var altSvcCache: AltSvcCache = InMemoryAltSvcCache()

    /** Max QUIC idle timeout in ms before the connection is closed. Default 30s. */
    fun maxIdleTimeoutMillis(value: Long) = apply { this.maxIdleTimeoutMillis = value }

    /** User-Agent header used by BridgeInterceptor-style defaults. */
    fun userAgent(value: String) = apply { this.userAgent = value }

    /**
     * PEM file containing the trusted CA certificates for peer verification.
     *
     * Overrides automatic platform detection. Mutually exclusive with
     * [trustedCaDirectory].
     */
    fun trustedCaPemFile(path: String?) = apply { this.trustedCaPemFile = path }

    /**
     * Directory of trusted CA certificates in OpenSSL hashed layout
     * (e.g. `/etc/ssl/certs`) for peer verification.
     *
     * Overrides automatic platform detection. Mutually exclusive with
     * [trustedCaPemFile].
     */
    fun trustedCaDirectory(path: String?) = apply { this.trustedCaDirectory = path }

    /**
     * Disable peer verification entirely. Development use only — never enable in production.
     */
    fun allowInsecure(value: Boolean) = apply { this.allowInsecure = value }

    /**
     * Optional HTTPS DNS record (RFC 9460) resolver. If set, the interceptor looks up the
     * origin's HTTPS record before taking over the call. If no record advertises `"h3"` in its
     * ALPN list, or if the lookup fails, the interceptor calls `chain.proceed()` and lets
     * OkHttp's standard H/1.1/H/2 stack handle the request. If unset (default), every HTTPS
     * request reaching this interceptor is attempted over HTTP/3.
     *
     * Pass [HttpsServiceRecordResolver.DEFAULT] to use the dnsjava-backed implementation.
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
      val engine =
        Quiche4jEngine(
          maxIdleTimeoutMillis = maxIdleTimeoutMillis,
          trustedCaPemFile = trustedCaPemFile,
          trustedCaDirectory = trustedCaDirectory,
          allowInsecure = allowInsecure,
          userAgent = userAgent,
        )
      return Quiche4jInterceptor(engine, httpsRecordResolver, altSvcCache)
    }
  }
}
