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
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.fail
import java.io.IOException
import okhttp3.Http3Header
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.internal.http3.Http3ExchangeCodec.Companion.http3HeadersList
import okhttp3.internal.http3.Http3ExchangeCodec.Companion.readHttp3HeadersList
import org.junit.jupiter.api.Test

/**
 * Unit tests for the header-mapping portion of [Http3ExchangeCodec] — the bits that
 * don't require wiring up a real [okhttp3.Http3Session] / [okhttp3.Http3Stream].
 *
 * End-to-end tests against a fake session belong in a follow-up; they need a non-
 * trivial stream harness and live at the `RealConnection` layer, which Phase 2.1b
 * introduces.
 */
class Http3ExchangeCodecTest {
  @Test fun `request pseudo-headers are prepended in RFC 9114 §4-3-1 order`() {
    val request =
      Request
        .Builder()
        .url("https://example.com/path?q=1")
        .header("User-Agent", "test")
        .build()

    val headers = http3HeadersList(request)

    // :method / :path / :scheme (no :authority because no Host header set).
    assertThat(headers[0]).isEqualTo(Http3Header(":method", "GET"))
    assertThat(headers[1]).isEqualTo(Http3Header(":path", "/path?q=1"))
    assertThat(headers[2]).isEqualTo(Http3Header(":scheme", "https"))
    assertThat(headers).contains(Http3Header("user-agent", "test"))
  }

  @Test fun `authority pseudo-header comes from explicit Host header`() {
    val request =
      Request
        .Builder()
        .url("https://example.com/")
        .header("Host", "override.example:8443")
        .build()

    val headers = http3HeadersList(request)

    assertThat(headers).contains(Http3Header(":authority", "override.example:8443"))
    // The user's Host header must not leak through as a regular header.
    assertThat(headers.none { it.name.utf8() == "host" }).isTrue()
  }

  @Test fun `hop-by-hop and upgrade headers are stripped per RFC 9114`() {
    val request =
      Request
        .Builder()
        .url("https://example.com/")
        .header("Connection", "upgrade")
        .header("Upgrade", "websocket")
        .header("Transfer-Encoding", "chunked")
        .header("Keep-Alive", "timeout=5")
        .header("Proxy-Connection", "keep-alive")
        .header("TE", "gzip")
        .build()

    val headers = http3HeadersList(request)

    for (forbidden in listOf("connection", "upgrade", "transfer-encoding", "keep-alive", "proxy-connection", "te")) {
      assertThat(headers.none { it.name.utf8() == forbidden })
        .isTrue()
    }
  }

  @Test fun `TE trailers is carried through (gRPC compatibility)`() {
    val request =
      Request
        .Builder()
        .url("https://example.com/")
        .header("TE", "trailers")
        .build()

    val headers = http3HeadersList(request)

    assertThat(headers).contains(Http3Header("te", "trailers"))
  }

  @Test fun `header names are lowercased (RFC 9114 §4-2 requires lowercase)`() {
    val request =
      Request
        .Builder()
        .url("https://example.com/")
        .header("X-Custom-Casing", "value")
        .build()

    val headers = http3HeadersList(request)

    assertThat(headers).contains(Http3Header("x-custom-casing", "value"))
    assertThat(headers.none { it.name.utf8() == "X-Custom-Casing" }).isTrue()
  }

  @Test fun `user-supplied pseudo-headers are dropped (codec owns them)`() {
    val request =
      Request
        .Builder()
        .url("https://example.com/")
        .header(":method", "POST") // should be ignored
        .header(":path", "/elsewhere") // should be ignored
        .build()

    val headers = http3HeadersList(request)

    // Codec-supplied pseudo-headers reflect the real request, not the smuggled ones.
    assertThat(headers.first { it.name.utf8() == ":method" }.value.utf8()).isEqualTo("GET")
    assertThat(headers.first { it.name.utf8() == ":path" }.value.utf8()).isEqualTo("/")
    // And we don't end up with duplicates.
    assertThat(headers.count { it.name.utf8() == ":method" }).isEqualTo(1)
    assertThat(headers.count { it.name.utf8() == ":path" }).isEqualTo(1)
  }

  @Test fun `response parses status pseudo-header into status line and code`() {
    val headerBlock =
      listOf(
        Http3Header(":status", "200"),
        Http3Header("content-type", "text/plain"),
      )

    val builder = readHttp3HeadersList(headerBlock)

    val response = builder.request(Request.Builder().url("https://example.com/").build()).build()
    assertThat(response.code).isEqualTo(200)
    assertThat(response.protocol).isEqualTo(Protocol.HTTP_3)
    assertThat(response.header("content-type")).isEqualTo("text/plain")
  }

  @Test fun `response without status pseudo-header fails with ProtocolException`() {
    val headerBlock = listOf(Http3Header("content-type", "text/plain"))

    try {
      readHttp3HeadersList(headerBlock)
      fail("expected ProtocolException")
    } catch (e: java.net.ProtocolException) {
      assertThat(e.message!!).contains(":status")
    }
  }

  @Test fun `response drops forbidden hop-by-hop headers`() {
    val headerBlock =
      listOf(
        Http3Header(":status", "200"),
        Http3Header("connection", "close"),
        Http3Header("transfer-encoding", "chunked"),
        Http3Header("keep-alive", "timeout=5"),
        Http3Header("content-type", "text/plain"),
      )

    val builder = readHttp3HeadersList(headerBlock)
    val response = builder.request(Request.Builder().url("https://example.com/").build()).build()

    assertThat(response.header("connection")).isNull()
    assertThat(response.header("transfer-encoding")).isNull()
    assertThat(response.header("keep-alive")).isNull()
    assertThat(response.header("content-type")).isEqualTo("text/plain")
  }

  @Test fun `response carries only HTTP_3 protocol (regardless of negotiation)`() {
    // Even if a test fixture hands the codec a :status that would otherwise look like
    // H/2 wire traffic, we tag the response as H/3 — the codec is only used when the
    // transport actually is H/3.
    val headerBlock = listOf(Http3Header(":status", "204"))

    val builder = readHttp3HeadersList(headerBlock)
    val response = builder.request(Request.Builder().url("https://example.com/").build()).build()
    assertThat(response.protocol).isEqualTo(Protocol.HTTP_3)
  }

  @Test fun `readHttp3HeadersList tolerates invalid status line gracefully`() {
    val headerBlock = listOf(Http3Header(":status", "not-a-number"))

    try {
      readHttp3HeadersList(headerBlock)
      fail("expected IOException from StatusLine.parse")
    } catch (_: IOException) {
      // expected: StatusLine.parse rejects non-numeric codes.
    } catch (_: java.net.ProtocolException) {
      // StatusLine.parse on the JVM throws ProtocolException (subclass of IOException).
    }
  }
}
