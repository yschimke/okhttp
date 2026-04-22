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
import java.net.Socket as JavaNetSocket
import okhttp3.Address
import okhttp3.Handshake
import okhttp3.Http3Session
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Route
import okhttp3.internal.closeQuietly
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.concurrent.withLock
import okhttp3.internal.http.ExchangeCodec
import okhttp3.internal.http.RealInterceptorChain
import okhttp3.internal.http3.Http3ExchangeCodec

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
  internal val connectionListener: ConnectionListener,
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
   * Whether this connection can carry a stream to [address]. Phase 2.2 will flesh out
   * the coalescing rules (same cert, same IP/UDP peer) — for now this is the minimal
   * "host + protocol match" check so pool integration tests have something to chew on.
   */
  override fun isEligible(
    address: Address,
    @Suppress("UNUSED_PARAMETER") routes: List<Route>?,
  ): Boolean {
    if (calls.size >= allocationLimit || noNewExchanges) return false
    if (!this.route.address.equalsNonHost(address)) return false
    return address.url.host == this.route.address.url.host
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
