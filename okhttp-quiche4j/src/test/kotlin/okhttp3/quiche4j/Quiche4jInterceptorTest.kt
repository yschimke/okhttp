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
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
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
        .userAgent("test/1.0")
        .maxIdleTimeoutMillis(5_000)
        .allowInsecure(true)
        .build()
    assertThat(interceptor).isNotNull()
  }

  @Test fun `client accepts the interceptor`() {
    val interceptor = Quiche4jInterceptor.Builder().allowInsecure(true).build()
    val client =
      OkHttpClient
        .Builder()
        .addInterceptor(interceptor)
        .build()
    assertThat(client.interceptors.size).isEqualTo(1)
  }

  /**
   * End-to-end test against a public HTTP/3 endpoint. Disabled by default because:
   *   * it requires network, and
   *   * quiche4j does not yet expose a way to load the system CA trust store, so the test only
   *     works with `allowInsecure(true)` or a hand-rolled PEM.
   *
   * See [PLAN.md][../../../../../../PLAN.md] — tracked as a quiche4j upstream task.
   */
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
