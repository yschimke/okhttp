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

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import java.io.File
import java.nio.file.Files
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.internal.TlsUtil.localhost
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Exercises the CacheInterceptor that sits inside [Quiche4jInterceptor]'s inner chain.
 *
 * The trick: MockWebServer doesn't speak HTTP/3, but the two transports share the same
 * `okhttp3.Cache`. Populate the cache via a plain HTTPS+H/2 request to MockWebServer (no
 * Quiche4jInterceptor on the client), then re-issue the same request through a client that
 * does have Quiche4jInterceptor — the interceptor's inner CacheInterceptor should serve the
 * cached response without ever running Quiche4jCallServer.
 *
 * The point is to verify that the CacheInterceptor we embed in the inner chain correctly
 * honours the OkHttpClient's cache, not that quiche4j can talk to MockWebServer.
 */
class Quiche4jCacheTest {
  private lateinit var server: MockWebServer
  private lateinit var cacheDir: File
  private lateinit var cache: Cache
  private lateinit var handshakeCertificates: HandshakeCertificates

  @BeforeEach
  fun setUp() {
    server = MockWebServer()
    handshakeCertificates = localhost()
    server.useHttps(handshakeCertificates.sslSocketFactory())
    server.start()
    cacheDir = Files.createTempDirectory("quiche4j-cache-test").toFile()
    cache = Cache(cacheDir, maxSize = 10L * 1024L * 1024L)
  }

  @AfterEach
  fun tearDown() {
    server.close()
    cache.close()
    cacheDir.deleteRecursively()
  }

  @Test fun `cache populated via H2 is served via the inner CacheInterceptor on the H3 path`() {
    val body = "hello from mockwebserver"
    server.enqueue(
      MockResponse
        .Builder()
        .body(body)
        .addHeader("Cache-Control", "max-age=300")
        .build(),
    )

    val baseClient =
      OkHttpClient
        .Builder()
        .cache(cache)
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).build()

    // 1) Prime the cache via the normal HTTPS + H/2 path (no Quiche4jInterceptor).
    val url = server.url("/cacheable")
    val primed = baseClient.newCall(Request.Builder().url(url).build()).execute()
    assertThat(primed.body.string()).isEqualTo(body)
    assertThat(primed.cacheResponse).isNull()
    assertThat(primed.networkResponse).isNotNull()

    // 2) Build a client that shares the same Cache but installs Quiche4jInterceptor with a
    //    test terminal that would throw if it ever ran. If the inner CacheInterceptor is
    //    wired correctly, the cache hit short-circuits the chain before the terminal is
    //    invoked, and Quiche4jCallServer is never touched.
    val exploding =
      okhttp3.Interceptor { _ ->
        throw AssertionError("inner terminal must not run on a cache hit")
      }
    val interceptor =
      Quiche4jInterceptor(
        engine = Quiche4jEngine(),
        httpsRecordResolver = null,
        altSvcCache = InMemoryAltSvcCache(),
        terminal = exploding,
      )
    val quicheClient = baseClient.newBuilder().addInterceptor(interceptor).build()

    val cached = quicheClient.newCall(Request.Builder().url(url).build()).execute()
    assertThat(cached.body.string()).isEqualTo(body)
    assertThat(cached.cacheResponse).isNotNull()
    // networkResponse is null for a pure cache hit.
    assertThat(cached.networkResponse).isNull()
    // Protocol on a cache-hit response is Protocol.HTTP_1_1 — that's just how OkHttp's HTTP
    // cache rehydrates entries regardless of the original wire protocol. The key claim we're
    // making is the cache hit itself (cacheResponse set, networkResponse null, no terminal
    // invocation), which proves Quiche4jCallServer never ran.
  }

  @Test fun `304 Not Modified through inner CacheInterceptor refreshes cached entry`() {
    val body = "etag-cacheable"
    val etag = "\"v1\""
    // First response: cacheable with ETag but max-age=0 so the next call must revalidate.
    server.enqueue(
      MockResponse
        .Builder()
        .body(body)
        .addHeader("Cache-Control", "max-age=0")
        .addHeader("ETag", etag)
        .build(),
    )
    // Second response: 304, no body — the cached body is returned.
    server.enqueue(
      MockResponse
        .Builder()
        .code(304)
        .addHeader("ETag", etag)
        .build(),
    )

    val baseClient =
      OkHttpClient
        .Builder()
        .cache(cache)
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).build()

    // Prime via normal HTTPS.
    val url = server.url("/revalidate")
    baseClient.newCall(Request.Builder().url(url).build()).execute().use {
      assertThat(it.body.string()).isEqualTo(body)
    }

    // Swap in a stub terminal that simulates a revalidation H/3 call — returns 304.
    val terminalRan = java.util.concurrent.atomic.AtomicBoolean(false)
    val stubTerminal =
      okhttp3.Interceptor { chain ->
        terminalRan.set(true)
        val sent = chain.request()
        // Echo the conditional header back so the test can assert it was present — that's
        // what proves CacheInterceptor inserted If-None-Match from the cache entry.
        val ifNoneMatch = sent.header("If-None-Match")
        okhttp3.Response
          .Builder()
          .request(sent)
          .protocol(Protocol.HTTP_3)
          .code(304)
          .message("")
          .addHeader("ETag", etag)
          .apply { if (ifNoneMatch != null) addHeader("X-Observed-If-None-Match", ifNoneMatch) }
          .body(okhttp3.ResponseBody.create(null, ""))
          .build()
      }
    val interceptor =
      Quiche4jInterceptor(
        engine = Quiche4jEngine(),
        httpsRecordResolver = null,
        altSvcCache = InMemoryAltSvcCache(),
        terminal = stubTerminal,
      )
    val quicheClient = baseClient.newBuilder().addInterceptor(interceptor).build()

    val revalidated = quicheClient.newCall(Request.Builder().url(url).build()).execute()
    assertThat(terminalRan.get()).isEqualTo(true)
    // The final response code should be 200 (CacheInterceptor translated the 304 into the
    // cached body), NOT 304.
    assertThat(revalidated.code).isEqualTo(200)
    assertThat(revalidated.body.string()).isEqualTo(body)
    assertThat(revalidated.cacheResponse).isNotNull()
    assertThat(revalidated.networkResponse).isNotNull()
    assertThat(revalidated.networkResponse!!.code).isEqualTo(304)
    // And the network request that was sent to our stub carried the If-None-Match header —
    // i.e. CacheInterceptor did the conditional-request rewriting.
    assertThat(revalidated.networkResponse!!.header("X-Observed-If-None-Match")).isEqualTo(etag)
  }

  @Test fun `cache miss falls through to the terminal interceptor`() {
    val served =
      java.util.concurrent.atomic.AtomicBoolean(false)
    val body = "served by quiche4j terminal"
    val stubTerminal =
      okhttp3.Interceptor { chain ->
        served.set(true)
        okhttp3.Response
          .Builder()
          .request(chain.request())
          .protocol(Protocol.HTTP_3)
          .code(200)
          .message("OK")
          .body(okhttp3.ResponseBody.create(null, body))
          .build()
      }
    val interceptor =
      Quiche4jInterceptor(
        engine = Quiche4jEngine(),
        httpsRecordResolver = null,
        altSvcCache = InMemoryAltSvcCache(),
        terminal = stubTerminal,
      )
    val client =
      OkHttpClient
        .Builder()
        .cache(cache)
        .addInterceptor(interceptor)
        .build()

    val response =
      client
        .newCall(Request.Builder().url("https://example.invalid/miss").build())
        .execute()
    assertThat(served.get()).isEqualTo(true)
    assertThat(response.body.string()).isEqualTo(body)
    assertThat(response.protocol).isEqualTo(Protocol.HTTP_3)
    assertThat(response.cacheResponse).isNull()
    response.close()
  }
}
