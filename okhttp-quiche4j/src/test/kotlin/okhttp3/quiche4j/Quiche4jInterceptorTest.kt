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
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import assertk.assertions.isInstanceOf
import java.security.cert.X509Certificate
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

class Quiche4jInterceptorTest {
  @Test fun `builder produces interceptor`() {
    val interceptor =
      Quiche4jInterceptor
        .Builder()
        .build()
    assertThat(interceptor).isNotNull()
  }

  @Test fun `client accepts the interceptor`() {
    val interceptor = Quiche4jInterceptor.Builder().build()
    val client =
      OkHttpClient
        .Builder()
        .addInterceptor(interceptor)
        .build()
    assertThat(client.interceptors.size).isEqualTo(1)
  }

  /** End-to-end test against a public HTTP/3 endpoint. Uses the JVM's platform trust store. */
  @Test fun `two sequential fetches reuse the pooled connection`() {
    val interceptor = Quiche4jInterceptor.Builder().build()
    val client =
      OkHttpClient
        .Builder()
        .addInterceptor(interceptor)
        .build()
    val request = Request.Builder().url("https://cloudflare-quic.com/").build()

    val first = client.newCall(request).execute()
    first.body?.bytes() // drain
    first.close()
    val afterFirst = interceptor.pooledConnectionCount
    assertThat(afterFirst).isEqualTo(1)

    val second = client.newCall(request).execute()
    second.body?.bytes()
    second.close()
    val afterSecond = interceptor.pooledConnectionCount
    println("pool after first=$afterFirst after second=$afterSecond")
    assertThat(afterSecond).isEqualTo(1) // same entry — pooling worked
    assertThat(second.protocol).isEqualTo(okhttp3.Protocol.HTTP_3)
  }

  @Test fun `CancellationHook fires synchronously when Call dot cancel is invoked`() {
    // The hook composes onto the Call via Call.addEventListener — Quiche4jCallServer then uses
    // this to tear down the QUIC stream on cancellation without polling. This test pins the
    // core mechanism: as soon as RealCall.cancel() runs, our callback runs too, on the thread
    // that invoked cancel (no intermediate delay from a polling loop).
    val client = OkHttpClient.Builder().build()
    val call = client.newCall(Request.Builder().url("https://example.com/").build())

    val fired = java.util.concurrent.atomic.AtomicBoolean(false)
    val invokingThread = java.util.concurrent.atomic.AtomicReference<Thread>()
    CancellationHook.attach(call) {
      fired.set(true)
      invokingThread.set(Thread.currentThread())
    }

    call.cancel()

    assertThat(fired.get()).isTrue()
    assertThat(invokingThread.get()).isEqualTo(Thread.currentThread())

    // Idempotency: a second cancel() must not re-fire the hook.
    fired.set(false)
    call.cancel()
    assertThat(fired.get()).isFalse()
  }

  @Test fun `Http3Preference ForceOff makes the interceptor fall through`() {
    val interceptor = Quiche4jInterceptor.Builder().build()
    val fell = java.util.concurrent.atomic.AtomicBoolean(false)
    val client =
      OkHttpClient
        .Builder()
        .addInterceptor(interceptor)
        .addInterceptor { c ->
          fell.set(true)
          okhttp3.Response
            .Builder()
            .request(c.request())
            .protocol(okhttp3.Protocol.HTTP_2)
            .code(200)
            .message("OK")
            .body(okhttp3.ResponseBody.create(null, "fell"))
            .build()
        }.build()
    val request =
      Request
        .Builder()
        .url("https://cloudflare-quic.com/")
        .tag<Http3Preference>(Http3Preference.ForceOff)
        .build()
    client.newCall(request).execute().use { resp ->
      assertThat(fell.get()).isTrue()
      assertThat(resp.protocol).isEqualTo(okhttp3.Protocol.HTTP_2)
    }
  }

  @Test fun `Http3Preference Force bypasses discovery`() {
    // HttpsAware Dns that claims "no h3" — would normally force fall-through.
    val noH3Dns =
      object : okhttp3.Dns, HttpsAware {
        override fun lookup(hostname: String) = okhttp3.Dns.SYSTEM.lookup(hostname)

        override fun getHttpsServiceRecord(hostname: String) =
          HttpsServiceRecord(
            priority = 1,
            targetName = ".",
            port = null,
            alpnIds = listOf("h2"),
            ipAddressHints = emptyList(),
            echConfigList = null,
          )
      }
    val interceptor = Quiche4jInterceptor.Builder().build()
    val client =
      OkHttpClient
        .Builder()
        .dns(noH3Dns)
        .addInterceptor(interceptor)
        .build()
    val request =
      Request
        .Builder()
        .url("https://cloudflare-quic.com/")
        .tag<Http3Preference>(Http3Preference.Force())
        .build()
    client.newCall(request).execute().use { resp ->
      assertThat(resp.protocol).isEqualTo(okhttp3.Protocol.HTTP_3)
    }
  }

  @Test fun `Http3Preference Force with fallback recovers when H3 fails`() {
    // 1s handshake timeout + a port that doesn't speak QUIC (HTTPS on 443 over TCP is fine
    // once we fall through, but UDP to :81 has no QUIC handler, so the handshake will time out).
    val interceptor = Quiche4jInterceptor.Builder().build()
    val client =
      OkHttpClient
        .Builder()
        .connectTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
        .addInterceptor(interceptor)
        .build()
    val request =
      Request
        .Builder()
        .url("https://cloudflare-quic.com/")
        .tag(
          Http3Preference::class.java,
          Http3Preference.Force(portOverride = 81, fallback = true),
        ).build()
    client.newCall(request).execute().use { resp ->
      // With fallback=true the interceptor should have caught the H/3 handshake timeout and
      // re-dispatched through the standard chain. The real URL port is still 443, so the H/2
      // request succeeds.
      println("fallback ok protocol=${resp.protocol} code=${resp.code}")
      assertThat(resp.protocol).isEqualTo(okhttp3.Protocol.HTTP_2)
      assertThat(resp.code).isEqualTo(200)
    }
  }

  @Test fun `Http3Preference Force with fallback=false propagates the H3 failure`() {
    val interceptor = Quiche4jInterceptor.Builder().build()
    val client =
      OkHttpClient
        .Builder()
        .connectTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
        .addInterceptor(interceptor)
        .build()
    val request =
      Request
        .Builder()
        .url("https://cloudflare-quic.com/")
        .tag(
          Http3Preference::class.java,
          Http3Preference.Force(portOverride = 81, fallback = false),
        ).build()
    val thrown =
      try {
        client.newCall(request).execute().also { it.close() }
        null
      } catch (e: java.io.IOException) {
        e
      }
    assertThat(thrown).isNotNull()
    assertThat(thrown!!).isInstanceOf(java.io.IOException::class.java)
  }

  @Test fun `Http3Preference Current is equivalent to no tag`() {
    val interceptor = Quiche4jInterceptor.Builder().build()
    val client =
      OkHttpClient
        .Builder()
        .addInterceptor(interceptor)
        .build()
    val request =
      Request
        .Builder()
        .url("https://cloudflare-quic.com/")
        .tag<Http3Preference>(Http3Preference.Current)
        .build()
    client.newCall(request).execute().use { resp ->
      assertThat(resp.protocol).isEqualTo(okhttp3.Protocol.HTTP_3)
    }
  }

  @Test fun `Alt-Svc cache seeded after a successful H3 fetch`() {
    val interceptor = Quiche4jInterceptor.Builder().build()
    val client =
      OkHttpClient
        .Builder()
        .addInterceptor(interceptor)
        .build()
    val request = Request.Builder().url("https://cloudflare-quic.com/").build()
    client.newCall(request).execute().close()

    val origin = AltSvcOrigin("https", "cloudflare-quic.com", 443)
    val cached = interceptor.altSvcCache.get(origin)
    println("alt-svc cache entries=${cached.map { "${it.protocolId}=:${it.port}" }}")
    assertThat(cached).isNotEmpty()
    assertThat(cached.any { it.protocolId.equals("h3", ignoreCase = true) }).isTrue()
  }

  @Test fun `seeded Alt-Svc drives h3 decision without HTTPS-record resolver`() {
    val cache = InMemoryAltSvcCache()
    // Pre-seed with an h3 entry — simulates "we learned this on a prior call".
    cache.put(
      AltSvcOrigin("https", "cloudflare-quic.com", 443),
      listOf(AltSvcEntry("h3", "", 443, System.currentTimeMillis() + 60_000)),
    )
    // Register a sentinel downstream interceptor so we can tell if the outer chain was used.
    val fellThrough = java.util.concurrent.atomic.AtomicBoolean(false)
    val interceptor =
      Quiche4jInterceptor
        .Builder()
        .altSvcCache(cache)
        // An HttpsAware Dns that always says "no h3" — without Alt-Svc this would force
        // fall-through. With Alt-Svc seeded, Quiche4jInterceptor should still pick h3.
        .build()
    val noH3Dns =
      object : okhttp3.Dns, HttpsAware {
        override fun lookup(hostname: String) = okhttp3.Dns.SYSTEM.lookup(hostname)

        override fun getHttpsServiceRecord(hostname: String) =
          HttpsServiceRecord(
            priority = 1,
            targetName = ".",
            port = null,
            alpnIds = listOf("h2"),
            ipAddressHints = emptyList(),
            echConfigList = null,
          )
      }
    val client =
      OkHttpClient
        .Builder()
        .dns(noH3Dns)
        .addInterceptor(interceptor)
        .addInterceptor { c ->
          fellThrough.set(true)
          c.proceed(c.request())
        }.build()
    val request = Request.Builder().url("https://cloudflare-quic.com/").build()
    client.newCall(request).execute().use { resp ->
      assertThat(fellThrough.get()).isFalse()
      assertThat(resp.protocol).isEqualTo(okhttp3.Protocol.HTTP_3)
    }
  }

  @Test fun `fall-through to outer chain when HTTPS record lacks h3`() {
    val fakeDns =
      object : okhttp3.Dns, HttpsAware {
        override fun lookup(hostname: String): List<java.net.InetAddress> = okhttp3.Dns.SYSTEM.lookup(hostname)

        override fun getHttpsServiceRecord(hostname: String): HttpsServiceRecord =
          HttpsServiceRecord(
            priority = 1,
            targetName = ".",
            port = null,
            alpnIds = listOf("h2"), // deliberately no h3
            ipAddressHints = emptyList(),
            echConfigList = null,
          )
      }
    val sentinel = java.util.concurrent.atomic.AtomicBoolean(false)
    val fallThroughInterceptor =
      okhttp3.Interceptor { c ->
        sentinel.set(true)
        okhttp3.Response
          .Builder()
          .request(c.request())
          .protocol(okhttp3.Protocol.HTTP_2)
          .code(200)
          .message("OK")
          .body(okhttp3.ResponseBody.create(null, "fallthrough"))
          .build()
      }
    val interceptor = Quiche4jInterceptor.Builder().build()
    val client =
      OkHttpClient
        .Builder()
        .dns(fakeDns)
        .addInterceptor(interceptor)
        .addInterceptor(fallThroughInterceptor)
        .build()
    val request = Request.Builder().url("https://example.com/").build()
    client.newCall(request).execute().use { resp ->
      assertThat(sentinel.get()).isTrue()
      assertThat(resp.body?.string()).isEqualTo("fallthrough")
      assertThat(resp.protocol).isEqualTo(okhttp3.Protocol.HTTP_2)
    }
  }

  @Test fun `HttpsAwareDns advertises h3 for cloudflare`() {
    val dns = HttpsAwareDns()
    val interceptor = Quiche4jInterceptor.Builder().build()
    val client =
      OkHttpClient
        .Builder()
        .dns(dns)
        .addInterceptor(interceptor)
        .build()
    val request = Request.Builder().url("https://cloudflare-quic.com/").build()
    client.newCall(request).execute().use { resp ->
      // Trigger the lookup path via the real call (this also seeds the cache).
      val record = dns.getHttpsServiceRecord("cloudflare-quic.com")
      println("https-record alpn=${record?.alpnIds} priority=${record?.priority} target=${record?.targetName}")
      assertThat(record).isNotNull()
      assertThat(record!!.supportsHttp3).isTrue()
      assertThat(resp.protocol).isEqualTo(okhttp3.Protocol.HTTP_3)
    }
  }

  @Test fun `live fetch against public h3 server with real TLS`() {
    val interceptor = Quiche4jInterceptor.Builder().build()
    val client =
      OkHttpClient
        .Builder()
        .addInterceptor(interceptor)
        .build()
    val request = Request.Builder().url("https://cloudflare-quic.com/").build()
    client.newCall(request).execute().use { resp ->
      println("status=${resp.code} protocol=${resp.protocol}")
      val bodyPreview = resp.body?.string()?.take(120)
      println("body[0..120]=$bodyPreview")
      assertThat(resp.protocol).isEqualTo(okhttp3.Protocol.HTTP_3)

      val handshake = checkNotNull(resp.handshake) { "handshake should be populated" }
      val peers = handshake.peerCertificates
      assertThat(peers).isNotEmpty()
      val leaf = peers.first() as X509Certificate
      val leafSubject = leaf.subjectX500Principal.name
      println("peer leaf subject=$leafSubject chain.size=${peers.size}")
      assertThat(leafSubject.lowercase()).contains("cloudflare")
      assertThat(peers.size).isGreaterThan(0)
    }
  }
}
