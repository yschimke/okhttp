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
package okhttp3

import java.io.IOException

/**
 * Pluggable HTTP/3 / QUIC transport. Install one via
 * [OkHttpClient.Builder.http3Engine] to enable HTTP/3 for requests whose
 * [OkHttpClient.protocols] list includes [Protocol.HTTP_3]. Without an engine
 * installed HTTP/3 is silently disabled and every call falls through to the
 * HTTP/2 / HTTP/1.1 stack.
 *
 * OkHttp core owns:
 *
 *  * Route planning (DNS, proxy, happy-eyeballs against the TCP path).
 *  * HTTP/3 discovery (Alt-Svc cache, HTTPS DNS records, per-request
 *    [Http3Preference] tags).
 *  * Request/response mapping (the [okhttp3.internal.http3.Http3ExchangeCodec]
 *    that produces and consumes HTTP/2-style header lists).
 *  * EventListener dispatch around the handshake and exchange.
 *
 * The engine owns:
 *
 *  * The UDP socket and congestion control.
 *  * The QUIC handshake and TLS 1.3 session under QUIC (RFC 9001).
 *  * Any internal per-origin pooling of QUIC connections.
 *  * Stream multiplexing, HEADERS/DATA framing, and QPACK encode/decode.
 *
 * The SPI is intentionally small so alternative implementations (quiche4j,
 * netty-incubator-codec-http3, msquic, ...) can slot in. Implementations must
 * be thread-safe.
 */
fun interface Http3Engine {
  /**
   * Returns true when this engine is ready to attempt an HTTP/3 connection for [route].
   *
   * Engines that initialize asynchronously can return false here so OkHttp skips HTTP/3
   * without treating the skipped attempt as a network failure. Once initialization completes,
   * subsequent calls can return true and route planning will begin using HTTP/3.
   */
  fun isAvailable(
    client: OkHttpClient,
    route: Route,
  ): Boolean = true

  /**
   * Open an HTTP/3 session to [route], blocking until the QUIC + H/3 handshake
   * completes successfully or throwing [IOException] on failure. OkHttp will
   * transparently fall back to the TCP path when the failure looks like "origin
   * doesn't speak h3" (unreachable UDP, handshake timeout, etc.).
   *
   * The returned session's [Http3Session.handshake] must be populated: OkHttp
   * surfaces it through `Response.handshake` and consults it for certificate
   * pinning and `EventListener.secureConnectEnd`.
   *
   * @param client The OkHttpClient the call belongs to. Engines should honour
   *   its DNS, trust manager, hostname verifier, and connect/read/write
   *   timeouts.
   * @param route The resolved route, including the UDP peer address and origin
   *   host. The engine may ignore [Route.proxy] since QUIC over HTTP CONNECT
   *   proxies requires MASQUE (RFC 9298), which OkHttp core doesn't yet wire.
   */
  @Throws(IOException::class)
  fun connect(
    client: OkHttpClient,
    route: Route,
  ): Http3Session
}
