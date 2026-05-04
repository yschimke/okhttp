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

import java.io.IOException
import java.lang.ref.Reference
import java.net.Proxy
import java.net.Socket as JavaNetSocket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.Address
import okhttp3.Handshake
import okhttp3.Http3Session
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Route
import okhttp3.internal.closeQuietly
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.concurrent.withLock
import okhttp3.internal.http.ExchangeCodec
import okhttp3.internal.http.RealInterceptorChain
import okhttp3.internal.http3.Http3ExchangeCodec
import okhttp3.internal.tls.OkHostnameVerifier

/**
 * An HTTP/3 / QUIC connection to a remote web server, carrying 1 or more concurrent
 * streams multiplexed over a single [Http3Session]. Sibling to [RealConnection] — both
 * implement the public [Connection] interface and the internal [ExchangeCodec.Carrier],
 * so the rest of the core ([RealCall], [Exchange], EventListener dispatch) never has to
 * branch on H/3 vs TCP.
 *
 * Phase 2.1b introduces this class without yet wiring it into [RealConnectionPool]. The
 * pool refactor + [RealRoutePlanner] integration lands in Phase 2.2 along with
 * `Http3ConnectPlan`; at that point this class starts accepting real calls.
 */
internal class Http3RealConnection(
  val taskRunner: TaskRunner,
  val connectionPool: RealConnectionPool,
  private val session: Http3Session,
  override val connectionListener: ConnectionListener,
) : PooledConnection {
  override val route: Route = session.route

  /**
   * Whether this connection refuses new streams. Set true by [trackFailure],
   * [noNewExchanges], or externally (pool eviction). Writes happen under [withLock].
   */
  override var noNewExchanges: Boolean = false

  /**
   * Max concurrent streams the peer allows; seeded from [Http3Session.maxConcurrentStreams]
   * at construction time. Phase 2.2 will plumb live updates as the peer sends
   * `MAX_STREAMS` frames (QUIC, RFC 9000 §19.11).
   */
  override var allocationLimit: Int = session.maxConcurrentStreams

  /** Active calls carried by this connection. Matches [RealConnection.calls]. */
  override val calls = mutableListOf<Reference<RealCall>>()

  /** Timestamp when [calls] last reached zero. Updated by [RealConnectionPool] in Phase 2.2. */
  override var idleAtNs: Long = Long.MAX_VALUE

  /** See [PooledConnection.incrementSuccessCount]. Guarded by [withLock]. */
  private var successCount: Int = 0

  /**
   * True once this connection has returned 421 Misdirected Request; it then refuses to
   * carry requests for hostnames other than its route's. Guarded by [withLock].
   */
  private var noCoalescedConnections: Boolean = false

  /** HTTP/3 sessions are always multiplexed; this mirrors [RealConnection.isMultiplexed]. */
  override val isMultiplexed: Boolean
    get() = true

  override fun route(): Route = route

  override fun handshake(): Handshake = session.handshake

  override fun protocol(): Protocol = Protocol.HTTP_3

  /**
   * [Connection.socket] returns a [java.net.Socket] — an interface we can't cleanly
   * satisfy for a UDP-based transport. Stage-3 will broaden the return type to
   * `okio.Socket` or introduce a transport-aware sibling. Until then we hand back a
   * pre-closed socket: the pool only calls `closeQuietly()` on it during eviction, and
   * a closed socket short-circuits that to a no-op — no stray file descriptors, no
   * pretending we own TCP state we don't. Anything else attempted on it (I/O, binding,
   * querying) will fail fast with a clear `SocketException`.
   */
  override fun socket(): JavaNetSocket = PLACEHOLDER_CLOSED_SOCKET

  /**
   * Returns an [ExchangeCodec] that encodes one HTTP request/response exchange over this
   * connection's [Http3Session]. Called once per call by [RealConnection.newCodec]'s
   * analogue in Phase 2.2; today this is only reachable via unit tests.
   */
  @Throws(IOException::class)
  override fun newCodec(
    client: OkHttpClient,
    chain: RealInterceptorChain,
  ): ExchangeCodec = Http3ExchangeCodec(client, this, chain, session)

  /**
   * True if the session is usable for new streams and our allocation limit allows it.
   * Mirrors [RealConnection.isHealthy]. [doExtensiveChecks] is accepted for signature
   * parity — QUIC's built-in liveness (IDLE_TIMEOUT, PATH_CHALLENGE) means there's no
   * separate "probe the socket" path to enable.
   */
  override fun isHealthy(doExtensiveChecks: Boolean): Boolean {
    @Suppress("UNUSED_PARAMETER") doExtensiveChecks
    if (noNewExchanges) return false
    return session.isHealthy
  }

  /**
   * Whether this connection can carry a stream to [address]. Port of
   * [RealConnection.isEligible] — same coalescing rules apply: QUIC sessions, like H/2
   * connections, can carry requests for multiple origins as long as the peer cert
   * covers the new host, the IP matches, and the hostname verifier is the default
   * strict one.
   */
  override fun isEligible(
    address: Address,
    routes: List<Route>?,
  ): Boolean {
    if (calls.size >= allocationLimit || noNewExchanges) return false
    if (!this.route.address.equalsNonHost(address)) return false
    if (address.url.host == this.route.address.url.host) return true

    // Coalescing path — same checks as RealConnection.isEligible for H/2.
    //
    // 1. Must be multiplexed. H/3 is always multiplexed, but we still gate coalescing
    //    on the 421-Misdirected-Request flag that the exchange machinery flips.
    if (noCoalescedConnections) return false

    // 2. Routes must share an IP/UDP peer (and both be DIRECT — MASQUE is out of scope).
    if (routes == null || !routeMatchesAny(routes)) return false

    // 3. Strict hostname verifier + cert must cover the new host.
    if (address.hostnameVerifier !== OkHostnameVerifier) return false
    if (!supportsUrl(address.url)) return false

    // 4. Certificate pinner must approve.
    try {
      address.certificatePinner!!.check(address.url.host, session.handshake.peerCertificates)
    } catch (_: SSLPeerUnverifiedException) {
      return false
    }

    return true
  }

  private fun routeMatchesAny(candidates: List<Route>): Boolean =
    candidates.any {
      it.proxy.type() == Proxy.Type.DIRECT &&
        route.proxy.type() == Proxy.Type.DIRECT &&
        route.socketAddress == it.socketAddress
    }

  private fun supportsUrl(url: HttpUrl): Boolean {
    val routeUrl = route.address.url
    if (url.port != routeUrl.port) return false
    if (url.host == routeUrl.host) return true
    // Different host — only allowed when the cert chain names this URL's host.
    val peerCerts = session.handshake.peerCertificates
    if (peerCerts.isEmpty()) return false
    return OkHostnameVerifier.verify(url.host, peerCerts[0] as X509Certificate)
  }

  override fun trackFailure(
    call: RealCall,
    e: IOException?,
  ) {
    // QUIC has no per-stream "refused, try again on the same connection" equivalent of
    // H/2's REFUSED_STREAM. Any failure retires the whole connection; the caller will
    // fall through to a fresh one via the route planner.
    var noNewExchangesEvent = false
    withLock {
      if (!noNewExchanges) {
        noNewExchangesEvent = true
        noNewExchanges = true
      }
    }
    if (noNewExchangesEvent) {
      connectionListener.noNewExchanges(this)
    }
  }

  override fun noNewExchanges() {
    withLock {
      noNewExchanges = true
    }
    connectionListener.noNewExchanges(this)
  }

  override fun incrementSuccessCount() {
    withLock { successCount++ }
  }

  /**
   * Retires this connection from coalescing (hostnames other than the route's). Called
   * when the peer returns a 421 Misdirected Request, signalling that the coalescing
   * decision was wrong for this origin. The connection can still carry requests for
   * its original hostname.
   */
  override fun noCoalescedConnections() {
    withLock { noCoalescedConnections = true }
  }

  /**
   * Close the session forcibly — cancels in-flight streams and sends CONNECTION_CLOSE.
   * Called by [RealCall.cancel] via the Carrier contract.
   */
  override fun cancel() {
    session.closeQuietly()
  }

  companion object {
    /** See [socket]. */
    private val PLACEHOLDER_CLOSED_SOCKET: JavaNetSocket =
      JavaNetSocket().apply { close() }
  }
}
