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
import java.util.concurrent.Executor
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
 * Entries are cached with a [cacheTtlMillis] budget (default 5 minutes) so a transient DNS
 * outage doesn't poison the cache for the lifetime of the JVM. Successful results and null
 * (no record) results share the same TTL — short enough that the cache tracks reality, long
 * enough that a hot host doesn't spam the resolver.
 *
 * @param delegate the underlying `Dns` used for A/AAAA resolution. Defaults to [Dns.SYSTEM].
 * @param resolver the HTTPS record resolver. Defaults to a dnsjava-backed implementation.
 * @param httpsLookupTimeoutMillis cap on how long we'll wait for the HTTPS record lookup to
 *   complete before proceeding with the A/AAAA result. The HTTPS result may still arrive later
 *   — it'll be cached for future calls to [getHttpsServiceRecord].
 * @param cacheTtlMillis how long a lookup result stays in the cache before [lookup] re-issues
 *   a fresh HTTPS query. Applies to both hits and misses.
 * @param executor the executor that runs the HTTPS record lookups. Defaults to a small bounded
 *   daemon pool shared across all `HttpsAwareDns` instances so a burst of distinct hosts
 *   doesn't spawn an unbounded thread fleet.
 */
class HttpsAwareDns(
  private val delegate: Dns = Dns.SYSTEM,
  private val resolver: HttpsServiceRecordResolver = HttpsServiceRecordResolver.DEFAULT,
  private val httpsLookupTimeoutMillis: Long = 500,
  private val cacheTtlMillis: Long = TimeUnit.MINUTES.toMillis(5),
  private val executor: Executor = DEFAULT_EXECUTOR,
) : Dns,
  HttpsAware {
  private val cache = ConcurrentHashMap<String, Entry>()

  override fun lookup(hostname: String): List<InetAddress> {
    // Kick off (or reuse, if still fresh) an HTTPS record lookup in parallel with A/AAAA.
    cache.compute(hostname) { _, existing ->
      if (existing != null && !existing.isExpired(cacheTtlMillis)) existing else startLookup(hostname)
    }
    return delegate.lookup(hostname)
  }

  override fun getHttpsServiceRecord(hostname: String): HttpsServiceRecord? {
    val entry = cache[hostname] ?: return null
    return try {
      entry.future.get(httpsLookupTimeoutMillis, TimeUnit.MILLISECONDS)
    } catch (_: Throwable) {
      null
    }
  }

  private fun startLookup(hostname: String): Entry {
    val future =
      CompletableFuture.supplyAsync(
        {
          try {
            // Single resolver call — the previous impl looked up twice (once for h3, once for
            // the "best priority" fallback) which burned an extra DNS round trip on every miss.
            val records = resolver.lookup(hostname)
            records.sortedBy { it.priority }.firstOrNull { it.supportsHttp3 }
              ?: records.minByOrNull { it.priority }
          } catch (_: Throwable) {
            null
          }
        },
        executor,
      )
    return Entry(future, System.nanoTime())
  }

  private data class Entry(
    val future: CompletableFuture<HttpsServiceRecord?>,
    val startedAtNs: Long,
  ) {
    fun isExpired(ttlMillis: Long): Boolean =
      System.nanoTime() - startedAtNs > TimeUnit.MILLISECONDS.toNanos(ttlMillis)
  }

  companion object {
    /**
     * Shared default executor for HTTPS lookups. Bounded to 2 threads — HTTPS record queries
     * are short (a handful of ms) and we want enough parallelism to cover a page load's worth
     * of distinct origins without letting a pathological burst fan out unbounded.
     */
    private val DEFAULT_EXECUTOR: Executor =
      Executors.newFixedThreadPool(2) { r ->
        Thread(r, "okhttp-quiche4j-https-dns").apply { isDaemon = true }
      }
  }
}
