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
) : Interceptor {
  /** Visible to tests: size of the pooled-connection cache. */
  internal val pooledConnectionCount: Int
    get() = engine.pooledConnectionCount()
  override fun intercept(chain: Interceptor.Chain): Response {
    // QUIC requires TLS — plaintext http:// URLs fall through to OkHttp's standard transport.
    // We intentionally do not throw here; the interceptor should be a no-op when it can't help.
    if (chain.request().url.scheme != "https") {
      return chain.proceed(chain.request())
    }

    val realChain = chain as RealInterceptorChain
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

    return innerChain.proceed(chain.request())
  }

  class Builder {
    private var maxIdleTimeoutMillis: Long = 30_000
    private var userAgent: String = "okhttp-quiche4j"
    private var trustedCaPemFile: String? = null
    private var trustedCaDirectory: String? = null
    private var allowInsecure: Boolean = false

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

    fun build(): Quiche4jInterceptor {
      val engine =
        Quiche4jEngine(
          maxIdleTimeoutMillis = maxIdleTimeoutMillis,
          trustedCaPemFile = trustedCaPemFile,
          trustedCaDirectory = trustedCaDirectory,
          allowInsecure = allowInsecure,
          userAgent = userAgent,
        )
      return Quiche4jInterceptor(engine)
    }
  }
}
