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
import okio.Buffer

/**
 * Per-request state on a pooled [QuicPooledConnection]. The connection's I/O thread produces
 * headers + body events; the caller (via [QuicBodySource]) consumes them.
 *
 * Headers: arrive once, delivered via [headersFuture].
 * Body:    a stream of [BodyEvent]s on [bodyQueue] ending with [BodyEvent.End] or
 *          [BodyEvent.Error].
 */
internal class QuicStream(
  val streamId: Long,
  val connection: QuicPooledConnection,
) {
  val headersFuture: CompletableFuture<ResponseHeaders> = CompletableFuture()
  val bodyQueue: LinkedBlockingQueue<BodyEvent> = LinkedBlockingQueue()

  @Volatile var finished: Boolean = false
    private set

  fun deliverHeaders(
    status: Int,
    headers: List<Pair<String, String>>,
  ) {
    headersFuture.complete(ResponseHeaders(status, headers))
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
    bodyQueue.put(BodyEvent.End)
  }

  fun deliverFailure(cause: Throwable) {
    finished = true
    val e = cause as? IOException ?: IOException(cause)
    if (!headersFuture.isDone) headersFuture.completeExceptionally(e)
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
