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

import java.io.Closeable
import java.io.IOException

/**
 * One established HTTP/3 / QUIC session, used by OkHttp's core to create per-request
 * [Http3Stream]s. Analogous to an HTTP/2 connection: one session multiplexes many
 * concurrent streams over a single QUIC connection.
 *
 * Owned by the [Http3Engine] that produced it. The engine manages the underlying UDP
 * socket, QUIC handshake, congestion control, and any internal stream-level pooling;
 * core treats a session as an opaque carrier.
 *
 * Implementations must be thread-safe — multiple calls may open streams concurrently.
 */
interface Http3Session : Closeable {
  /** The resolved route this session was opened against. */
  val route: Route

  /** TLS 1.3 handshake information from the QUIC handshake (RFC 9001). */
  val handshake: Handshake

  /**
   * Maximum number of concurrently-open client-initiated bidirectional streams the peer
   * will accept. Derived from the `initial_max_streams_bidi` QUIC transport parameter
   * (RFC 9000 §18.2), updated as the peer sends `MAX_STREAMS` frames.
   */
  val maxConcurrentStreams: Int

  /**
   * True if the session is still usable for new streams. Returns false after close,
   * after the peer sent a CONNECTION_CLOSE, or after an idle timeout. OkHttp's pool
   * consults this before handing the session to a new call.
   */
  val isHealthy: Boolean

  /**
   * Open a new HTTP/3 bidirectional stream with [headers] as the request HEADERS frame.
   * The engine is free to defer actual wire transmission until the first [flush] or
   * until the caller writes through the returned stream's `sink`.
   *
   * @param headers The request pseudo-headers (`:method`, `:path`, `:scheme`,
   *   `:authority`) followed by regular headers, all lowercase.
   * @param hasRequestBody Hint: `false` means the caller will close the stream's sink
   *   without writing any bytes. Engines may send the HEADERS frame with END_STREAM
   *   set immediately in that case.
   * @throws IOException if the session is closed or the peer's stream limit is reached.
   */
  @Throws(IOException::class)
  fun newStream(
    headers: List<Http3Header>,
    hasRequestBody: Boolean,
  ): Http3Stream

  /**
   * Flush any buffered frames to the wire. Called by the codec between request phases
   * so pipelined writes don't sit indefinitely in an engine-internal buffer.
   */
  @Throws(IOException::class)
  fun flush()

  /** Close this session and any open streams. Sends a QUIC `CONNECTION_CLOSE` frame. */
  override fun close()
}
