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

import okhttp3.Http3Engine
import okhttp3.Http3Session
import okhttp3.OkHttpClient
import okhttp3.Route
import okhttp3.internal.platform.Platform

/**
 * [Http3Engine] backed by [quiche4j](https://github.com/yschimke/quiche4j). Install via
 * `OkHttpClient.Builder.http3Engine(Quiche4jHttp3Engine())` to enable HTTP/3 support for
 * requests whose Address's protocols list includes [okhttp3.Protocol.HTTP_3].
 *
 * Each [connect] call opens a fresh QUIC connection — we intentionally do not dedupe at
 * this layer. OkHttp's connection pool already caches the [Http3RealConnection] wrapping
 * each session, and the route planner's `planReusePooledConnection` path short-circuits
 * before this engine is called when a reusable connection exists. Avoiding a second
 * layer of pooling keeps the lifecycle owned by OkHttp core.
 *
 * TLS verification is done in Java: quiche's own `verify_peer` is disabled, the peer
 * certificate chain is pulled out after the QUIC handshake, and
 * [OkHttpClient.x509TrustManager] + [OkHttpClient.hostnameVerifier] run against it. This
 * way the engine honours exactly the same trust configuration as the TCP+TLS path —
 * including Android's system trust store, user-installed CAs via network-security-
 * config, and any certificate pinning the client is set up with.
 */
class Quiche4jHttp3Engine : Http3Engine {
  // Holds the quiche Config factory. Not used for any kind of connection pooling here
  // (see class KDoc).
  private val engine = Quiche4jEngine()

  override fun connect(
    client: OkHttpClient,
    route: Route,
  ): Http3Session {
    val host = route.address.url.host
    val port = route.socketAddress.port
    val trustManager =
      client.x509TrustManager ?: Platform.get().platformTrustManager()
    val pooled =
      QuicPooledConnection.connect(
        key = PoolKey(host, port),
        peer = route.socketAddress,
        engine = engine,
        trustManager = trustManager,
        hostnameVerifier = client.hostnameVerifier,
        handshakeTimeoutMillis = client.connectTimeoutMillis.toLong(),
        // QUIC's max_idle_timeout bounds any packet-exchange gap, not just application
        // bytes. readTimeout is the closest OkHttp analog.
        maxIdleTimeoutMillis = client.readTimeoutMillis.toLong(),
      )
    return Quiche4jHttp3Session(route, pooled)
  }
}
