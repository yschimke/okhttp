/*
 * Copyright (C) 2026 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package okhttp3.quiche4j

import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import okhttp3.Headers
import okio.Buffer

/**
 * Per-request state on a pooled [QuicPooledConnection]. The connection's I/O thread produces
 * headers + body events; the caller (via [QuicBodySource]) consumes them.
 *
 * Headers:  arrive once as the first [deliverHeaders] call, surfaced via [headersFuture].
 * Body:     a stream of [BodyEvent]s on [bodyQueue] ending with [BodyEvent.End] or
 *           [BodyEvent.Error].
 * Trailers: a second [deliverHeaders] call (no `:status` pseudo-header), surfaced via
 *           [trailersFuture]. Completed with [Headers.EMPTY] at end-of-stream if the peer
 *           didn't send trailers.
 */
internal class QuicStream(
  val streamId: Long,
  val connection: QuicPooledConnection,
) {
  val headersFuture: CompletableFuture<ResponseHeaders> = CompletableFuture()

  /**
   * Completes with trailer headers (if the peer sent any), or [Headers.EMPTY] at the end
   * of the body if it didn't. Completes exceptionally if the stream fails before either
   * path runs. Callers use this via OkHttp's [okhttp3.TrailersSource] plumbed into
   * [okhttp3.Response.Builder.trailers].
   */
  val trailersFuture: CompletableFuture<Headers> = CompletableFuture()
  val bodyQueue: LinkedBlockingQueue<BodyEvent> = LinkedBlockingQueue()

  @Volatile var finished: Boolean = false
    private set

  fun deliverHeaders(
    status: Int,
    headers: List<Pair<String, String>>,
  ) {
    // First call carries the response `:status`; any subsequent call is trailers (RFC 9114
    // §4.1 allows one trailer section after the body). Route to the right future.
    if (!headersFuture.isDone) {
      headersFuture.complete(ResponseHeaders(status, headers))
    } else if (!trailersFuture.isDone) {
      val builder = Headers.Builder()
      for ((n, v) in headers) {
        // Trailers MUST NOT include pseudo-headers (RFC 9114 §4.1.1); drop defensively.
        if (!n.startsWith(":")) builder.add(n, v)
      }
      trailersFuture.complete(builder.build())
    }
  }

  /**
   * Enqueue a body chunk. Takes [Buffer] ownership: the caller must not touch [data]
   * again after this call. Consumers drain it via [okio.BufferedSink.write] which does
   * segment-level transfer at aligned boundaries, so the bytes copied into [data] by
   * the producer stay put all the way through to the user's `ResponseBody` (or
   * WebSocket frame reader) without a second byte copy.
   */
  fun deliverBody(data: Buffer) {
    bodyQueue.put(BodyEvent.Bytes(data))
  }

  fun deliverEnd() {
    finished = true
    // If the peer sent no trailers, surface an empty set so callers don't block forever
    // on Response.trailers().
    if (!trailersFuture.isDone) trailersFuture.complete(Headers.EMPTY)
    bodyQueue.put(BodyEvent.End)
  }

  fun deliverFailure(cause: Throwable) {
    finished = true
    val e = cause as? IOException ?: IOException(cause)
    if (!headersFuture.isDone) headersFuture.completeExceptionally(e)
    if (!trailersFuture.isDone) trailersFuture.completeExceptionally(e)
    bodyQueue.put(BodyEvent.Error(e))
  }
}

internal data class ResponseHeaders(
  val status: Int,
  val headers: List<Pair<String, String>>,
)

internal sealed class BodyEvent {
  /**
   * Holds body bytes as an [okio.Buffer] so the consuming source can forward segments
   * directly to the caller's sink via [okio.BufferedSink.write] (zero-copy at segment
   * boundaries). The buffer is owned by the event — the consumer drains it; producers
   * must not retain a reference after enqueueing.
   */
  class Bytes(
    val data: Buffer,
  ) : BodyEvent()

  object End : BodyEvent()

  class Error(
    val cause: IOException,
  ) : BodyEvent()
}
