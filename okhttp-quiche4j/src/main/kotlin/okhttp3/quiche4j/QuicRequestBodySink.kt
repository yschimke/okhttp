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
import okio.Buffer
import okio.Sink
import okio.Timeout

/**
 * An okio [Sink] that streams request-body chunks over an HTTP/3 stream via [QuicPooledConnection]
 * without buffering the whole body in memory. Paired with [QuicBodySource] — same shape, opposite
 * direction.
 *
 * Used for duplex request bodies (`RequestBody.isDuplex() == true`, e.g. gRPC). Not used for
 * regular bodies — those stay on the faster "buffer then send once" path in
 * [Quiche4jCallServer].
 *
 * All `h3.sendBody` calls go through the pool's task queue so they're serialised on the
 * connection's I/O thread, same as every other quiche interaction.
 */
internal class QuicRequestBodySink(
  private val stream: QuicStream,
  private val pool: QuicPooledConnection,
  /**
   * Bound on each `sendBodyChunk` dispatch; `<= 0` means no bound. Matches the request's
   * `writeTimeout` — a slow-consumer server that leaves us wedged in flow-control back-off
   * can't hang the call thread forever.
   */
  private val writeTimeoutMillis: Long = 0L,
  /**
   * When `true` (default), [close] sends a zero-byte chunk with `fin=true` to terminate the
   * QUIC stream — the natural behaviour for a one-shot HTTP request body.
   *
   * WebSocket connections (RFC 9220) reuse this sink but keep the stream open for the full
   * session; they close the stream separately via [finish] when the WebSocket close handshake
   * completes. Pass `false` for that case.
   */
  private val sendFinOnClose: Boolean = true,
) : Sink {
  @Volatile private var closed: Boolean = false

  override fun write(
    source: Buffer,
    byteCount: Long,
  ) {
    if (closed) throw IOException("closed")
    var remaining = byteCount
    while (remaining > 0) {
      val chunk = source.readByteArray(minOf(remaining, MAX_CHUNK.toLong()))
      var offset = 0
      while (offset < chunk.size) {
        val slice =
          if (offset == 0) {
            chunk
          } else {
            chunk.copyOfRange(offset, chunk.size)
          }
        // Flow-control back-off (quiche returning DONE) and write-timeout enforcement both
        // live inside pool.sendBodyChunk: it re-queues on the I/O thread until the window
        // opens, an error occurs, or the timeout elapses. So the call thread only sees a
        // positive byte count or an IOException.
        val n = pool.sendBodyChunk(stream, slice, fin = false, timeoutMillis = writeTimeoutMillis)
        offset += n.toInt()
      }
      remaining -= chunk.size
    }
  }

  override fun flush() { /* sendBodyChunk blocks until quiche accepts bytes, no extra flush step. */ }

  override fun timeout(): Timeout = Timeout.NONE

  override fun close() {
    if (closed) return
    closed = true
    if (sendFinOnClose) finish()
  }

  /**
   * Explicitly terminate the QUIC stream with a zero-byte `fin=true` chunk. Used by
   * WebSocket teardown (where [close] deliberately doesn't fin) and called from [close] on
   * request-body sinks via [sendFinOnClose]. Swallows `IOException` — if the stream is
   * already broken, `cancelStream` has (or will) clean up.
   */
  fun finish() {
    try {
      pool.sendBodyChunk(stream, EMPTY_BYTES, fin = true, timeoutMillis = writeTimeoutMillis)
    } catch (_: IOException) {
      // Best effort.
    }
  }

  private companion object {
    const val MAX_CHUNK: Int = 16 * 1024
    val EMPTY_BYTES = ByteArray(0)
  }
}
