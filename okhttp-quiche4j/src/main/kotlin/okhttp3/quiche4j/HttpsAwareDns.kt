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

import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.Dns

/**
 * Marker + lookup interface implemented by a [Dns] that also resolves RFC 9460 HTTPS service
 * records. The [Quiche4jInterceptor] queries this on the caller's configured `Dns` to decide
 * whether the origin advertises HTTP/3; if it does, the interceptor handles the request, if it
 * doesn't, the interceptor falls through to OkHttp's standard transport.
 *
 * Modelled after [square/okhttp#9383](https://github.com/square/okhttp/pull/9383)'s
 * `AndroidDnsResolverDns`, which exposes HTTPS records via Android 36's
 * `android.net.dns.HttpsRecord` API. This interface is the cross-platform equivalent.
 */
interface HttpsAware {
  /**
   * Returns the best HTTPS service record for [hostname] (highest priority first) or `null` if
   * the origin published no records, or the lookup is still pending / failed.
   *
   * Must be fast / non-blocking — the value should have been resolved as part of the regular
   * [Dns.lookup] call.
   */
  fun getHttpsServiceRecord(hostname: String): HttpsServiceRecord?
}

/**
 * A [Dns] wrapper that issues an HTTPS (RFC 9460) record query in parallel with the regular
 * A/AAAA lookup. The A/AAAA result is returned synchronously to satisfy OkHttp; the HTTPS
 * record result is cached so [Quiche4jInterceptor] — via [HttpsAware] — can decide whether to
 * route the request over HTTP/3.
 *
 * Typical use:
 * ```kotlin
 * val dns = HttpsAwareDns()
 * val interceptor = Quiche4jInterceptor.Builder().build()
 * val client = OkHttpClient.Builder()
 *   .dns(dns)
 *   .addInterceptor(interceptor)
 *   .build()
 * ```
 *
 * @param delegate the underlying `Dns` used for A/AAAA resolution. Defaults to [Dns.SYSTEM].
 * @param resolver the HTTPS record resolver. Defaults to a dnsjava-backed implementation.
 * @param httpsLookupTimeoutMillis cap on how long we'll wait for the HTTPS record lookup to
 *   complete before proceeding with the A/AAAA result. The HTTPS result may still arrive later
 *   — it'll be cached for future calls to [getHttpsServiceRecord].
 */
class HttpsAwareDns(
  private val delegate: Dns = Dns.SYSTEM,
  private val resolver: HttpsServiceRecordResolver = HttpsServiceRecordResolver.DEFAULT,
  private val httpsLookupTimeoutMillis: Long = 500,
) : Dns,
  HttpsAware {
  private val executor =
    Executors.newCachedThreadPool { r ->
      Thread(r, "okhttp-quiche4j-https-dns").apply { isDaemon = true }
    }
  private val cache = ConcurrentHashMap<String, CompletableFuture<HttpsServiceRecord?>>()

  override fun lookup(hostname: String): List<InetAddress> {
    // Kick off (or reuse) an HTTPS record lookup in parallel with the A/AAAA lookup.
    cache.computeIfAbsent(hostname) {
      CompletableFuture.supplyAsync(
        {
          try {
            resolver.lookup(hostname).sortedBy { it.priority }.firstOrNull { it.supportsHttp3 }
              ?: resolver.lookup(hostname).minByOrNull { it.priority }
          } catch (_: Throwable) {
            null
          }
        },
        executor,
      )
    }
    return delegate.lookup(hostname)
  }

  override fun getHttpsServiceRecord(hostname: String): HttpsServiceRecord? {
    val future = cache[hostname] ?: return null
    return try {
      future.get(httpsLookupTimeoutMillis, TimeUnit.MILLISECONDS)
    } catch (_: Throwable) {
      null
    }
  }
}
