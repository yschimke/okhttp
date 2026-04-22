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

import java.lang.ref.Reference
import okhttp3.Address
import okhttp3.Connection
import okhttp3.OkHttpClient
import okhttp3.Route
import okhttp3.internal.concurrent.Lockable
import okhttp3.internal.http.ExchangeCodec
import okhttp3.internal.http.RealInterceptorChain

/**
 * The contract [RealConnectionPool] + [RealRoutePlanner] + [RealCall] need from any
 * connection they store, acquire, or exchange through — whether it's TCP-backed
 * ([RealConnection]) or QUIC-backed ([Http3RealConnection]).
 *
 * Introduced in Phase 2.2a. Today [RealConnectionPool.connections] is still typed
 * `ConcurrentLinkedQueue<RealConnection>`; the pool refactor that widens it lives in
 * Phase 2.2b. This interface is here so we stop having to special-case H/3 in the
 * planner and call layer below the pool — and so we can point at one place in code
 * review when we argue about "what is a pooled connection."
 *
 * Invariants any implementation must preserve:
 *
 *  * All mutable state ([noNewExchanges], [idleAtNs], [calls]) is guarded by [withLock]
 *    via [Lockable]. The pool calls [assertLockHeld] liberally.
 *  * Once [noNewExchanges] flips to true it stays true. Pool eviction, stream errors,
 *    and explicit `cancel()` all set it; nothing clears it.
 *  * [isEligible] and [isHealthy] are free to return stale results — the pool holds
 *    the lock across the acquire + health-check sequence, so concurrent state changes
 *    can't race inside one caller.
 */
internal interface PooledConnection :
  Connection,
  ExchangeCodec.Carrier,
  Lockable {
  /**
   * False when this connection can still carry more streams. Goes true on pool
   * eviction, stream errors, session close, or explicit [noNewExchanges] call.
   * Implementations must guard assignment with [withLock].
   */
  var noNewExchanges: Boolean

  /**
   * Maximum concurrent streams the peer accepts on this connection. 1 for HTTP/1.1,
   * updated from SETTINGS / MAX_STREAMS for multiplexed transports.
   */
  val allocationLimit: Int

  /** Calls currently carried by this connection, used by the pool for leak detection. */
  val calls: MutableList<Reference<RealCall>>

  /**
   * Timestamp when [calls] last reached zero. The pool uses this to pick an eviction
   * target when the idle-connection limit is exceeded.
   */
  var idleAtNs: Long

  /**
   * True if the transport carries concurrent streams (HTTP/2, HTTP/3). False for
   * HTTP/1.1, which is one-exchange-at-a-time. Controls coalescing eligibility.
   */
  val isMultiplexed: Boolean

  /**
   * Whether this connection can carry a new stream to [address], taking into account
   * allocation limit, retired-connection flag, and (for multiplexed transports)
   * certificate coalescing over [routes].
   */
  fun isEligible(
    address: Address,
    routes: List<Route>?,
  ): Boolean

  /**
   * Whether this connection is still responsive. [doExtensiveChecks] enables slower
   * liveness probes that are only worth doing for idle connections before reuse.
   */
  fun isHealthy(doExtensiveChecks: Boolean): Boolean

  /**
   * Returns a fresh [ExchangeCodec] that sends one HTTP request/response exchange
   * over this connection. Called exactly once per [RealCall]'s exchange.
   */
  fun newCodec(
    client: OkHttpClient,
    chain: RealInterceptorChain,
  ): ExchangeCodec
}
