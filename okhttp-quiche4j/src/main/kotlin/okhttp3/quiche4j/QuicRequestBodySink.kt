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
import java.io.InterruptedIOException
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
        val n = pool.sendBodyChunk(stream, slice, fin = false)
        // quiche returns DONE (-1) when the stream is flow-control-blocked. Back off and retry.
        if (n <= 0) {
          if (n < 0 && n != io.quiche4j.Quiche.ErrorCode.DONE.toLong()) {
            throw IOException("quiche4j sendBody failed: $n")
          }
          try {
            Thread.sleep(5)
          } catch (e: InterruptedException) {
            // Restore the interrupt flag + translate to an IOException so the caller's outer
            // try/catch (and Quiche4jInterceptor's fallback path, if applicable) sees it
            // cleanly instead of a checked exception bubbling out of an `okio.Sink.write`.
            Thread.currentThread().interrupt()
            throw InterruptedIOException("interrupted while waiting for QUIC flow-control window")
          }
          continue
        }
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
    try {
      pool.sendBodyChunk(stream, EMPTY_BYTES, fin = true)
    } catch (_: IOException) {
      // Best effort — if the stream is already broken, cancelStream has (or will) clean up.
    }
  }

  private companion object {
    const val MAX_CHUNK: Int = 16 * 1024
    val EMPTY_BYTES = ByteArray(0)
  }
}
