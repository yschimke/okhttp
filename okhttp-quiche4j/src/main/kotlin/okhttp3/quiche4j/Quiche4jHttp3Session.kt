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

import okhttp3.Handshake
import okhttp3.Http3Header
import okhttp3.Http3Session
import okhttp3.Http3Stream
import okhttp3.Route

/**
 * [Http3Session] backed by a single [QuicPooledConnection]. Owned by
 * [Quiche4jHttp3Engine]; callers get one per successful [Http3Engine.connect].
 *
 * [maxConcurrentStreams] is seeded from the config we hand quiche
 * ([Quiche4jEngine.newConfig]'s `withInitialMaxStreamsBidi(100)`). A future refinement
 * could read the peer's `MAX_STREAMS` frames and surface them here; OkHttp's pool won't
 * over-allocate anyway because [okhttp3.internal.connection.Http3RealConnection]'s
 * `calls.size >= allocationLimit` check runs before [newStream].
 */
internal class Quiche4jHttp3Session(
  override val route: Route,
  private val pooled: QuicPooledConnection,
) : Http3Session {
  override val handshake: Handshake = pooled.handshake

  override val maxConcurrentStreams: Int = DEFAULT_MAX_CONCURRENT_STREAMS

  override val isHealthy: Boolean
    get() = !pooled.closed

  override fun newStream(
    headers: List<Http3Header>,
    hasRequestBody: Boolean,
  ): Http3Stream {
    val quicheHeaders =
      headers.map { io.quiche4j.http3.Http3Header(it.name.utf8(), it.value.utf8()) }
    val stream =
      pooled.openStream(
        headers = quicheHeaders,
        body = null,
        streamingBody = hasRequestBody,
        // The task queue is trivially short; the openStream call returns as soon as the
        // I/O thread picks up the `h3.sendRequest`. 0 = no additional timeout beyond the
        // caller's own deadline.
        timeoutMillis = 0L,
      )
    return Quiche4jHttp3Stream(this, stream, pooled)
  }

  override fun flush() {
    // The pool's I/O thread runs its send loop on every packet-processing iteration, so
    // there's no pending-bytes buffer at this layer that would need an explicit flush.
  }

  override fun close() {
    // No-op by design. OkHttp's connection pool decides when to retire Http3RealConnection,
    // which is the owner of this session from core's perspective. If we tore down the
    // underlying QuicPooledConnection here, a coalesced peer connection (different host
    // sharing the same QUIC session) would be collateral damage.
  }

  companion object {
    /** Matches the `initialMaxStreamsBidi` set in [Quiche4jEngine.newConfig]. */
    const val DEFAULT_MAX_CONCURRENT_STREAMS: Int = 100
  }
}
