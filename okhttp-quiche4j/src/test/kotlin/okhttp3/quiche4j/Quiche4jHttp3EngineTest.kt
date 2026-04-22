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
package okhttp3.quiche4j

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import okhttp3.Http3Preference
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.junit.jupiter.api.Test

/**
 * Live integration test for the stage-2 HTTP/3 path: install [Quiche4jHttp3Engine] on a
 * real [OkHttpClient], configure discovery so the route planner picks H/3, and fetch a
 * real URL. If this passes we've plumbed the new SPI end-to-end against a real QUIC
 * server.
 */
class Quiche4jHttp3EngineTest {
  @Test fun `fetch against cloudflare-quic via stage-2 engine`() {
    val engine = Quiche4jHttp3Engine()

    // Prime Alt-Svc for the origin so the route planner's Http3Decision short-circuits
    // to "yes" without needing an HTTPS-record lookup. Cloudflare serves Alt-Svc on its
    // own responses too, but we don't want the first call to go over TCP just to fetch
    // the header.
    val altSvcCache = okhttp3.InMemoryAltSvcCache()
    altSvcCache.put(
      origin = okhttp3.AltSvcOrigin(scheme = "https", host = "cloudflare-quic.com", port = 443),
      entries =
        listOf(
          okhttp3.AltSvcEntry(
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
        .build()

    val request =
      Request
        .Builder()
        .url("https://cloudflare-quic.com/")
        // Force isn't strictly needed with Alt-Svc primed, but belt-and-braces so a
        // flaky Alt-Svc cache doesn't quietly fall through to TCP and mask a real H/3
        // failure.
        .tag<Http3Preference>(Http3Preference.Force())
        .build()

    val response = client.newCall(request).execute()
    response.use {
      println("response: ${it.protocol} ${it.code}")
      assertThat(it.protocol).isEqualTo(Protocol.HTTP_3)
      val bytes = it.body.bytes()
      println("body: ${bytes.size} bytes")
      assertThat(bytes.size).isGreaterThan(0)
    }
  }

  @Test fun `second fetch reuses the pooled Http3RealConnection`() {
    val engine = Quiche4jHttp3Engine()
    val altSvcCache = okhttp3.InMemoryAltSvcCache()
    altSvcCache.put(
      origin = okhttp3.AltSvcOrigin(scheme = "https", host = "cloudflare-quic.com", port = 443),
      entries =
        listOf(
          okhttp3.AltSvcEntry(
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
        .build()

    val request =
      Request
        .Builder()
        .url("https://cloudflare-quic.com/")
        .tag<Http3Preference>(Http3Preference.Force())
        .build()

    val first = client.newCall(request).execute().also { it.body.bytes() }
    first.close()
    assertThat(first.protocol).isEqualTo(Protocol.HTTP_3)

    val idleBefore = client.connectionPool.idleConnectionCount()

    val second = client.newCall(request).execute().also { it.body.bytes() }
    second.close()
    assertThat(second.protocol).isEqualTo(Protocol.HTTP_3)

    // Pool should have exactly one idle entry between the two calls (the QUIC session
    // handed back after the first call completed). The second call found and reused it.
    println("idleBefore=$idleBefore, connectionCount=${client.connectionPool.connectionCount()}")
    assertThat(idleBefore).isEqualTo(1)
    assertThat(client.connectionPool.connectionCount()).isEqualTo(1)
  }
}
