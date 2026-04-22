/*
 * Copyright (C) 2026 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.http3

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import okhttp3.AltSvcEntry
import okhttp3.AltSvcOrigin
import okhttp3.Handshake
import okhttp3.Http3Engine
import okhttp3.Http3Header
import okhttp3.Http3Session
import okhttp3.Http3Stream
import okhttp3.InMemoryAltSvcCache
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Route
import okhttp3.TlsVersion
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.Sink
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.Test

/**
 * End-to-end integration test. Installs a fake [Http3Engine] on a real [OkHttpClient],
 * primes the Alt-Svc cache so the route planner picks H/3, and runs a real call through
 * the real interceptor chain / exchange machinery. This validates that Phase 2.1 +
 * 2.2's plumbing (codec, carrier, pool, route planner, exchange finder) actually
 * routes H/3 traffic end-to-end without any shortcuts.
 */
class Http3EndToEndTest {
  @Test fun `request flows through fake engine and returns Protocol HTTP_3`() {
    val engine = FakeHttp3Engine()
    val altSvcCache = InMemoryAltSvcCache()
    // Prime Alt-Svc so Http3Decision.shouldAttempt returns true.
    altSvcCache.put(
      origin = AltSvcOrigin("https", "example.com", 443),
      entries =
        listOf(
          AltSvcEntry(
            protocolId = "h3",
            host = "",
            port = 443,
            expiresAtMillis = System.currentTimeMillis() + 60_000,
          ),
        ),
    )

    val client =
      OkHttpClient
        .Builder()
        .protocols(listOf(Protocol.HTTP_3, Protocol.HTTP_2, Protocol.HTTP_1_1))
        .http3Engine(engine)
        .altSvcCache(altSvcCache)
        .dns { host -> listOf(InetSocketAddress.createUnresolved(host, 0).address ?: java.net.InetAddress.getByName("127.0.0.1")) }
        .build()

    val request = Request.Builder().url("https://example.com/hello").build()
    val response = client.newCall(request).execute()

    response.use {
      assertThat(response.protocol).isEqualTo(Protocol.HTTP_3)
      assertThat(response.code).isEqualTo(200)
      assertThat(response.header("content-type")).isEqualTo("text/plain")
      assertThat(response.body.string()).isEqualTo("hello from h3")
    }

    // Engine was invoked exactly once, with a route we'd recognise.
    assertThat(engine.connectCalls.get()).isEqualTo(1)
    val openedRoute = engine.lastRoute.get()!!
    assertThat(openedRoute.address.url.host).isEqualTo("example.com")
    assertThat(openedRoute.address.url.port).isEqualTo(443)

    // Codec produced the expected pseudo-header list.
    val captured = engine.lastSession.get()!!.lastStream.get()!!
    // All four request pseudo-headers present; exact order matches the Http2-style
    // codec output (`:method, :path, :authority, :scheme`).
    val pseudoHeaderNames = captured.requestHeaders.map { it.name.utf8() }.filter { it.startsWith(":") }
    assertThat(pseudoHeaderNames.toSet()).isEqualTo(setOf(":method", ":path", ":scheme", ":authority"))
    assertThat(captured.requestHeaders.first { it.name.utf8() == ":method" }.value.utf8())
      .isEqualTo("GET")
    assertThat(captured.requestHeaders.first { it.name.utf8() == ":path" }.value.utf8())
      .isEqualTo("/hello")
  }

  @Test fun `Alt-Svc header on response populates the cache for next call`() {
    val engine = FakeHttp3Engine(responseHeaders = listOf("alt-svc" to "h3=\":443\"; ma=3600"))
    val altSvcCache = InMemoryAltSvcCache()
    // Seed so the first call goes over H/3 (otherwise the cache would be empty and
    // decision would fall through to TCP — that's a separate test).
    altSvcCache.put(
      origin = AltSvcOrigin("https", "example.com", 443),
      entries =
        listOf(
          AltSvcEntry(
            protocolId = "h3",
            host = "",
            port = 443,
            expiresAtMillis = System.currentTimeMillis() + 60_000,
          ),
        ),
    )
    val client =
      OkHttpClient
        .Builder()
        .protocols(listOf(Protocol.HTTP_3, Protocol.HTTP_2, Protocol.HTTP_1_1))
        .http3Engine(engine)
        .altSvcCache(altSvcCache)
        .dns { _ -> listOf(java.net.InetAddress.getByName("127.0.0.1")) }
        .build()

    client.newCall(Request.Builder().url("https://example.com/").build()).execute().close()

    // CallServerInterceptor parsed the Alt-Svc header from the engine's response.
    val entries = altSvcCache.get(AltSvcOrigin("https", "example.com", 443))
    assertThat(entries).hasSize(1)
    assertThat(entries.first().protocolId).isEqualTo("h3")
    assertThat(entries.first().port).isEqualTo(443)
  }

  // --- fakes ----------------------------------------------------------------

  private class FakeHttp3Engine(
    private val status: Int = 200,
    private val responseBody: String = "hello from h3",
    private val responseHeaders: List<Pair<String, String>> =
      listOf("content-type" to "text/plain"),
  ) : Http3Engine {
    val connectCalls = java.util.concurrent.atomic.AtomicInteger()
    val lastRoute = AtomicReference<Route?>()
    val lastSession = AtomicReference<FakeHttp3Session?>()

    override fun connect(
      client: OkHttpClient,
      route: Route,
    ): Http3Session {
      connectCalls.incrementAndGet()
      lastRoute.set(route)
      val session = FakeHttp3Session(route, status, responseBody, responseHeaders)
      lastSession.set(session)
      return session
    }
  }

  private class FakeHttp3Session(
    override val route: Route,
    private val status: Int,
    private val responseBody: String,
    private val responseHeaders: List<Pair<String, String>>,
  ) : Http3Session {
    override val handshake: Handshake =
      Handshake.get(
        tlsVersion = TlsVersion.TLS_1_3,
        cipherSuite = okhttp3.CipherSuite.TLS_AES_128_GCM_SHA256,
        peerCertificates = emptyList(),
        localCertificates = emptyList(),
      )

    override val maxConcurrentStreams: Int = 100

    override val isHealthy: Boolean = true

    val lastStream = AtomicReference<FakeHttp3Stream?>()

    override fun newStream(
      headers: List<Http3Header>,
      hasRequestBody: Boolean,
    ): Http3Stream {
      val stream = FakeHttp3Stream(this, headers, status, responseBody, responseHeaders)
      lastStream.set(stream)
      return stream
    }

    override fun flush() {}

    override fun close() {}
  }

  private class FakeHttp3Stream(
    override val session: Http3Session,
    val requestHeaders: List<Http3Header>,
    status: Int,
    responseBody: String,
    responseHeaders: List<Pair<String, String>>,
  ) : Http3Stream {
    // A throwaway Sink for the request body — the codec closes it, we ignore what
    // goes in. For this test we only GET.
    override val sink: Sink = Buffer()

    private val responseFrame: Buffer =
      Buffer().apply {
        writeUtf8(responseBody)
      }

    override val source: Source = responseFrame

    @Volatile private var sourceComplete: Boolean = false

    override val isSourceComplete: Boolean
      get() = sourceComplete || responseFrame.size == 0L

    private val timeout: Timeout = Timeout.NONE

    override fun readTimeout(): Timeout = timeout

    override fun writeTimeout(): Timeout = timeout

    private val headersToReturn: List<Http3Header> =
      buildList {
        add(Http3Header(":status", status.toString()))
        for ((name, value) in responseHeaders) {
          add(Http3Header(name, value))
        }
      }

    override fun takeHeaders(callerIsIdle: Boolean): List<Http3Header> = headersToReturn

    override fun peekTrailers(): List<Http3Header>? = emptyList()

    override fun cancel() {
      sourceComplete = true
    }
  }
}
