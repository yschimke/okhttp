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
import okio.Socket
import okio.Timeout

/**
 * One HTTP/3 request/response stream, created via [Http3Session.newStream]. OkHttp core
 * talks to the engine through this interface: [sink] writes the request body, [source]
 * reads the response body, [takeHeaders] blocks for the response head, [peekTrailers]
 * non-blocking-ly checks for trailers, and [cancel] aborts the stream with a RESET_STREAM.
 *
 * Lifecycle: created with the request headers already queued by the session. The caller
 * writes the request body (if any) through [sink] and closes it to finish the request
 * side. Responses arrive asynchronously: [takeHeaders] blocks until HEADERS are
 * received, then bytes flow through [source], and finally [peekTrailers] (or source EOF)
 * terminates the response side.
 *
 * Implementations must be thread-safe — [sink] and [source] are typically used from
 * different threads (the caller writes; the engine fills the source from its I/O thread).
 */
interface Http3Stream : Socket {
  /** The session this stream belongs to. */
  val session: Http3Session

  /**
   * True once the response body and (possibly empty) trailers have been fully received.
   * Matches [okhttp3.internal.http.ExchangeCodec.isResponseComplete]'s contract.
   */
  val isSourceComplete: Boolean

  /** Okio timeout applied to [source] reads. Set from the call's `readTimeout`. */
  fun readTimeout(): Timeout

  /** Okio timeout applied to [sink] writes. Set from the call's `writeTimeout`. */
  fun writeTimeout(): Timeout

  /**
   * Block until the peer sends response HEADERS for this stream, then return them.
   *
   * @param callerIsIdle hint from the codec that the caller is waiting for a `100 Continue`
   *   informational response (per `Expect: 100-continue`). Engines may use this to apply
   *   a read timeout where they would otherwise wait indefinitely.
   * @throws IOException on stream reset, connection loss, or timeout.
   */
  @Throws(IOException::class)
  fun takeHeaders(callerIsIdle: Boolean): Headers

  /**
   * Non-blocking check for trailers. Returns:
   *
   *  * `null` if the response body hasn't finished yet.
   *  * an empty list if the body finished without trailers.
   *  * the trailers otherwise.
   *
   * @throws IOException on stream reset.
   */
  @Throws(IOException::class)
  fun peekTrailers(): Headers?
}
