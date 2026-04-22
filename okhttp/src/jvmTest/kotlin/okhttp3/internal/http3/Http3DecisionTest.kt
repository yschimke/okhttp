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
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import java.net.InetSocketAddress
import java.net.Proxy
import javax.net.SocketFactory
import okhttp3.Address
import okhttp3.AltSvcEntry
import okhttp3.AltSvcOrigin
import okhttp3.Authenticator
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.Http3Engine
import okhttp3.Http3Preference
import okhttp3.Http3Session
import okhttp3.HttpsServiceRecord
import okhttp3.HttpsServiceRecordResolver
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Route
import org.junit.jupiter.api.Test

class Http3DecisionTest {
  private val address = newAddress(protocols = listOf(Protocol.HTTP_3, Protocol.HTTP_2, Protocol.HTTP_1_1))
  private val request = Request.Builder().url("https://example.com/").build()

  @Test fun `ForceOff overrides any positive signal`() {
    val client = clientWithEngineAnd(cache = altSvcWithH3())
    val forced = Request.Builder().url("https://example.com/").tag<Http3Preference>(Http3Preference.ForceOff).build()
    assertThat(Http3Decision.shouldAttempt(client, forced, address)).isFalse()
  }

  @Test fun `Force overrides every other check (no engine, no protocols, no signals)`() {
    val client = OkHttpClient.Builder().build() // no engine, default altSvcCache
    val forced = Request.Builder().url("https://example.com/").tag<Http3Preference>(Http3Preference.Force()).build()
    assertThat(Http3Decision.shouldAttempt(client, forced, newAddress(protocols = listOf(Protocol.HTTP_1_1)))).isTrue()
  }

  @Test fun `no engine means no H3`() {
    val client = OkHttpClient.Builder().build()
    assertThat(Http3Decision.shouldAttempt(client, request, address)).isFalse()
  }

  @Test fun `HTTP_3 absent from protocols means no H3`() {
    val addressWithoutH3 = newAddress(protocols = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
    val client = clientWithEngineAnd(cache = altSvcWithH3())
    assertThat(Http3Decision.shouldAttempt(client, request, addressWithoutH3)).isFalse()
  }

  @Test fun `Alt-Svc cache with h3 entry triggers H3`() {
    val client = clientWithEngineAnd(cache = altSvcWithH3())
    assertThat(Http3Decision.shouldAttempt(client, request, address)).isTrue()
  }

  @Test fun `Alt-Svc cache with only h2 entry does not trigger H3`() {
    val client = clientWithEngineAnd(cache = altSvcWith(protocolId = "h2"))
    assertThat(Http3Decision.shouldAttempt(client, request, address)).isFalse()
  }

  @Test fun `HTTPS record with h3 alpn triggers H3`() {
    val resolver =
      HttpsServiceRecordResolver {
        listOf(
          HttpsServiceRecord(
            priority = 1,
            targetName = "example.com",
            port = null,
            alpnIds = listOf("h3", "h2"),
            ipAddressHints = emptyList(),
            echConfigList = null,
          ),
        )
      }
    val client =
      OkHttpClient
        .Builder()
        .http3Engine(NoopEngine)
        .httpsServiceRecordResolver(resolver)
        .build()
    assertThat(Http3Decision.shouldAttempt(client, request, address)).isTrue()
  }

  @Test fun `HTTPS record without h3 does not trigger H3`() {
    val resolver =
      HttpsServiceRecordResolver {
        listOf(
          HttpsServiceRecord(
            priority = 1,
            targetName = "example.com",
            port = null,
            alpnIds = listOf("h2"),
            ipAddressHints = emptyList(),
            echConfigList = null,
          ),
        )
      }
    val client =
      OkHttpClient
        .Builder()
        .http3Engine(NoopEngine)
        .httpsServiceRecordResolver(resolver)
        .build()
    assertThat(Http3Decision.shouldAttempt(client, request, address)).isFalse()
  }

  @Test fun `HTTPS record resolver exception falls through to no-signal`() {
    val resolver = HttpsServiceRecordResolver { throw RuntimeException("dns boom") }
    val client =
      OkHttpClient
        .Builder()
        .http3Engine(NoopEngine)
        .httpsServiceRecordResolver(resolver)
        .build()
    assertThat(Http3Decision.shouldAttempt(client, request, address)).isFalse()
  }

  @Test fun `no Alt-Svc no HTTPS record means no H3 (don't speculate)`() {
    val client = OkHttpClient.Builder().http3Engine(NoopEngine).build()
    assertThat(Http3Decision.shouldAttempt(client, request, address)).isFalse()
  }

  @Test fun `expired Alt-Svc entries don't count`() {
    val cache = okhttp3.InMemoryAltSvcCache()
    cache.put(
      origin = AltSvcOrigin("http", "example.com", 443),
      entries =
        listOf(
          AltSvcEntry(
            protocolId = "h3",
            host = "",
            port = 443,
            expiresAtMillis = System.currentTimeMillis() - 1000,
          ),
        ),
    )
    val client = OkHttpClient.Builder().http3Engine(NoopEngine).altSvcCache(cache).build()
    assertThat(Http3Decision.shouldAttempt(client, request, address)).isFalse()
  }

  @Test fun `Alt-Svc entry with non-default port surfaces as port override`() {
    val cache = okhttp3.InMemoryAltSvcCache()
    cache.put(
      origin = AltSvcOrigin("http", "example.com", 443),
      entries =
        listOf(
          AltSvcEntry(
            protocolId = "h3",
            host = "",
            port = 8443, // non-default
            expiresAtMillis = System.currentTimeMillis() + 60_000,
          ),
        ),
    )
    val client = OkHttpClient.Builder().http3Engine(NoopEngine).altSvcCache(cache).build()
    val decision = Http3Decision.decide(client, request, address)
    check(decision is Http3Decision.Decision.Attempt)
    assertThat(decision.portOverride).isEqualTo(8443)
  }

  @Test fun `Alt-Svc entry with same port as origin has no port override`() {
    val cache = okhttp3.InMemoryAltSvcCache()
    cache.put(
      origin = AltSvcOrigin("http", "example.com", 443),
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
    val client = OkHttpClient.Builder().http3Engine(NoopEngine).altSvcCache(cache).build()
    val decision = Http3Decision.decide(client, request, address)
    check(decision is Http3Decision.Decision.Attempt)
    assertThat(decision.portOverride).isNull()
  }

  @Test fun `HTTPS record port override propagates`() {
    val resolver =
      HttpsServiceRecordResolver {
        listOf(
          HttpsServiceRecord(
            priority = 1,
            targetName = "example.com",
            port = 8443,
            alpnIds = listOf("h3", "h2"),
            ipAddressHints = emptyList(),
            echConfigList = null,
          ),
        )
      }
    val client =
      OkHttpClient
        .Builder()
        .http3Engine(NoopEngine)
        .httpsServiceRecordResolver(resolver)
        .build()
    val decision = Http3Decision.decide(client, request, address)
    check(decision is Http3Decision.Decision.Attempt)
    assertThat(decision.portOverride).isEqualTo(8443)
  }

  @Test fun `Force tag portOverride wins over every other signal`() {
    val cache = okhttp3.InMemoryAltSvcCache()
    cache.put(
      origin = AltSvcOrigin("http", "example.com", 443),
      entries =
        listOf(
          AltSvcEntry(
            protocolId = "h3",
            host = "",
            port = 8443,
            expiresAtMillis = System.currentTimeMillis() + 60_000,
          ),
        ),
    )
    val client = OkHttpClient.Builder().http3Engine(NoopEngine).altSvcCache(cache).build()
    val forced =
      Request
        .Builder()
        .url("https://example.com/")
        .tag<Http3Preference>(Http3Preference.Force(portOverride = 9443))
        .build()
    val decision = Http3Decision.decide(client, forced, address)
    check(decision is Http3Decision.Decision.Attempt)
    assertThat(decision.portOverride).isEqualTo(9443)
  }

  // --- helpers --------------------------------------------------------------

  private fun clientWithEngineAnd(cache: okhttp3.AltSvcCache): OkHttpClient =
    OkHttpClient.Builder().http3Engine(NoopEngine).altSvcCache(cache).build()

  private fun altSvcWithH3(): okhttp3.AltSvcCache = altSvcWith(protocolId = "h3")

  private fun altSvcWith(protocolId: String): okhttp3.AltSvcCache {
    val cache = okhttp3.InMemoryAltSvcCache()
    cache.put(
      origin = AltSvcOrigin("http", "example.com", 443),
      entries =
        listOf(
          AltSvcEntry(
            protocolId = protocolId,
            host = "",
            port = 443,
            expiresAtMillis = System.currentTimeMillis() + 60_000,
          ),
        ),
    )
    return cache
  }

  private object NoopEngine : Http3Engine {
    override fun connect(
      client: OkHttpClient,
      route: Route,
    ): Http3Session = throw UnsupportedOperationException("not expected to run in decision tests")
  }

  private fun newAddress(protocols: List<Protocol>): Address =
    Address(
      uriHost = "example.com",
      uriPort = 443,
      dns = Dns.SYSTEM,
      socketFactory = SocketFactory.getDefault(),
      sslSocketFactory = null,
      hostnameVerifier = null,
      certificatePinner = null,
      proxyAuthenticator = Authenticator.NONE,
      proxy = null,
      protocols = protocols,
      connectionSpecs = listOf(ConnectionSpec.MODERN_TLS),
      proxySelector = java.net.ProxySelector.getDefault(),
    )
}
