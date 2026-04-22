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
package okhttp3.internal.connection

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import java.net.InetSocketAddress
import java.net.Proxy
import javax.net.SocketFactory
import okhttp3.Address
import okhttp3.Authenticator
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.NetworkIdentitySource
import okhttp3.Protocol
import okhttp3.Route
import org.junit.jupiter.api.Test

class RouteDatabaseTest {
  private val route1 = newRoute(host = "example.com", ip = "1.2.3.4")
  private val route2 = newRoute(host = "example.com", ip = "1.2.3.5")

  @Test fun `default None source preserves pre-2026 network-blind behaviour`() {
    val db = RouteDatabase() // defaults to NetworkIdentitySource.None
    db.failed(route1)
    assertThat(db.shouldPostpone(route1)).isTrue()
    assertThat(db.shouldPostpone(route2)).isFalse()
  }

  @Test fun `connected clears the current-network failure`() {
    val db = RouteDatabase()
    db.failed(route1)
    db.connected(route1)
    assertThat(db.shouldPostpone(route1)).isFalse()
  }

  @Test fun `failure on one network does not postpone on another`() {
    val source = MutableNetworkIdentitySource(currentId = "wifi")
    val db = RouteDatabase(source)

    // Failure recorded on WiFi.
    db.failed(route1)
    assertThat(db.shouldPostpone(route1)).isTrue()

    // Switch to cellular — same route should be retried.
    source.currentId = "cellular"
    assertThat(db.shouldPostpone(route1)).isFalse()

    // Back to WiFi — still remembered on WiFi.
    source.currentId = "wifi"
    assertThat(db.shouldPostpone(route1)).isTrue()
  }

  @Test fun `connected on one network doesn't clear a failure recorded on another`() {
    val source = MutableNetworkIdentitySource(currentId = "wifi")
    val db = RouteDatabase(source)
    db.failed(route1)

    source.currentId = "cellular"
    db.connected(route1) // cellular success shouldn't wipe the WiFi memory

    source.currentId = "wifi"
    assertThat(db.shouldPostpone(route1)).isTrue()
  }

  @Test fun `null network identity shares a single bucket across calls`() {
    val source = MutableNetworkIdentitySource(currentId = null)
    val db = RouteDatabase(source)
    db.failed(route1)

    // Source keeps returning null — treat it as "single unknown network".
    assertThat(db.shouldPostpone(route1)).isTrue()

    // Identifying a network now means we're in a different bucket, so the null-era
    // failure doesn't carry over.
    source.currentId = "wifi"
    assertThat(db.shouldPostpone(route1)).isFalse()
  }

  @Test fun `failedRoutes snapshot ignores network scoping`() {
    val source = MutableNetworkIdentitySource(currentId = "wifi")
    val db = RouteDatabase(source)
    db.failed(route1)
    source.currentId = "cellular"
    db.failed(route2)

    // Snapshot returns both Routes; this is a tooling/debug view — don't rely on it
    // for routing decisions.
    assertThat(db.failedRoutes).isEqualTo(setOf(route1, route2))
  }

  @Test fun `distinct routes on the same network remain independent`() {
    val db = RouteDatabase()
    db.failed(route1)
    assertThat(db.shouldPostpone(route1)).isTrue()
    assertThat(db.shouldPostpone(route2)).isFalse()

    db.connected(route1)
    assertThat(db.failedRoutes).isEmpty()
  }

  private class MutableNetworkIdentitySource(
    @Volatile var currentId: Any?,
  ) : NetworkIdentitySource {
    override fun currentNetworkId(): Any? = currentId
  }

  private fun newRoute(
    host: String,
    ip: String,
  ): Route =
    Route(
      address =
        Address(
          uriHost = host,
          uriPort = 443,
          dns = Dns.SYSTEM,
          socketFactory = SocketFactory.getDefault(),
          sslSocketFactory = null,
          hostnameVerifier = null,
          certificatePinner = null,
          proxyAuthenticator = Authenticator.NONE,
          proxy = null,
          protocols = listOf(Protocol.HTTP_1_1),
          connectionSpecs = listOf(ConnectionSpec.MODERN_TLS),
          proxySelector = java.net.ProxySelector.getDefault(),
        ),
      proxy = Proxy.NO_PROXY,
      socketAddress = InetSocketAddress.createUnresolved(ip, 443),
    )
}
