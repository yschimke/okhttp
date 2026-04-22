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

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import okhttp3.Http3Header
import okhttp3.Http3Session
import okhttp3.Http3Stream
import okio.Buffer
import okio.Sink
import okio.Source
import okio.Timeout

/**
 * [Http3Stream] backed by a stage-1 [QuicStream] + [QuicPooledConnection]. Adapts the
 * stage-1 body-queue + futures interface into the okio sink/source + headers/trailers
 * contract that core's `Http3ExchangeCodec` talks.
 *
 * One subtle contract: [readTimeout] and [writeTimeout] return the same [Timeout]
 * instances the [source] and [sink] honour internally. `Http3ExchangeCodec.writeRequestHeaders`
 * mutates them (via `timeout(ms, UNIT)`) immediately after `newStream`, and we need those
 * mutations to take effect on actual I/O dispatch.
 */
internal class Quiche4jHttp3Stream(
  override val session: Http3Session,
  private val quicStream: QuicStream,
  private val pooled: QuicPooledConnection,
) : Http3Stream {
  private val readTimeout = Timeout()
  private val writeTimeout = Timeout()

  override val sink: Sink =
    // QuicRequestBodySink's internal timeout is seeded as 0 (no bound); the real bound
    // comes from writeTimeout which the codec configures post-construction.
    QuicRequestBodySink(quicStream, pooled, writeTimeoutMillis = 0L)
      .withExternalTimeout(writeTimeout)

  override val source: Source =
    BodyQueueSource(quicStream, pooled, readTimeout)

  override val isSourceComplete: Boolean
    get() = quicStream.finished && quicStream.bodyQueue.isEmpty()

  override fun readTimeout(): Timeout = readTimeout

  override fun writeTimeout(): Timeout = writeTimeout

  override fun takeHeaders(callerIsIdle: Boolean): List<Http3Header> {
    val timeoutMs = readTimeout.timeoutMillisOrNoBound()
    val responseHeaders =
      try {
        if (timeoutMs <= 0L) {
          quicStream.headersFuture.get()
        } else {
          quicStream.headersFuture.get(timeoutMs, TimeUnit.MILLISECONDS)
        }
      } catch (e: TimeoutException) {
        throw InterruptedIOException(
          "timed out waiting for HTTP/3 response headers after ${timeoutMs}ms",
        ).apply { initCause(e) }
      } catch (e: ExecutionException) {
        val cause = e.cause ?: e
        throw cause as? IOException ?: IOException(cause)
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw InterruptedIOException("interrupted waiting for HTTP/3 response headers")
      }

    val result = ArrayList<Http3Header>(responseHeaders.headers.size + 1)
    result.add(Http3Header(":status", responseHeaders.status.toString()))
    for ((name, value) in responseHeaders.headers) {
      result.add(Http3Header(name, value))
    }
    return result
  }

  override fun peekTrailers(): List<Http3Header>? {
    if (!quicStream.trailersFuture.isDone) return null
    val trailers =
      try {
        quicStream.trailersFuture.get()
      } catch (e: ExecutionException) {
        val cause = e.cause ?: e
        throw cause as? IOException ?: IOException(cause)
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw InterruptedIOException("interrupted waiting for HTTP/3 trailers")
      }
    val result = ArrayList<Http3Header>(trailers.size)
    for (i in 0 until trailers.size) {
      result.add(Http3Header(trailers.name(i), trailers.value(i)))
    }
    return result
  }

  override fun cancel() {
    pooled.cancelStream(quicStream)
    pooled.releaseStream(quicStream)
  }
}

/**
 * Okio [Source] that drains the stream's body queue. Roughly a subset of
 * [QuicBodySource] without the EventListener plumbing (core's Exchange layer fires
 * those events on our behalf).
 */
private class BodyQueueSource(
  private val quicStream: QuicStream,
  private val pooled: QuicPooledConnection,
  private val timeoutRef: Timeout,
) : Source {
  @Volatile private var closed: Boolean = false
  private var currentChunk: Buffer? = null
  private var reachedEnd: Boolean = false

  override fun read(
    sink: Buffer,
    byteCount: Long,
  ): Long {
    check(byteCount >= 0) { "byteCount < 0: $byteCount" }
    if (closed) throw IOException("stream closed")
    if (reachedEnd && (currentChunk == null || currentChunk!!.size == 0L)) return -1L

    if (currentChunk == null || currentChunk!!.size == 0L) {
      val event =
        try {
          awaitBodyEvent()
        } catch (ie: InterruptedException) {
          Thread.currentThread().interrupt()
          throw IOException("interrupted while reading H/3 body")
        }
      when (event) {
        is BodyEvent.Bytes -> currentChunk = event.data
        is BodyEvent.End -> {
          reachedEnd = true
          return -1L
        }

        is BodyEvent.Error -> {
          reachedEnd = true
          throw event.cause
        }
      }
    }

    val chunk = currentChunk!!
    val toWrite = minOf(chunk.size, byteCount)
    sink.write(chunk, toWrite)
    return toWrite
  }

  override fun close() {
    if (closed) return
    closed = true
    pooled.releaseStream(quicStream)
  }

  override fun timeout(): Timeout = timeoutRef

  private fun awaitBodyEvent(): BodyEvent {
    val timeoutNs = timeoutRef.timeoutNanos()
    val hasDeadline = timeoutRef.hasDeadline()
    if (timeoutNs == 0L && !hasDeadline) {
      return quicStream.bodyQueue.take()
    }
    val deadlineRemaining =
      if (hasDeadline) timeoutRef.deadlineNanoTime() - System.nanoTime() else Long.MAX_VALUE
    val timeoutRemaining = if (timeoutNs != 0L) timeoutNs else Long.MAX_VALUE
    val waitNs = minOf(deadlineRemaining, timeoutRemaining)
    if (waitNs <= 0) throw InterruptedIOException("read timeout")
    return quicStream.bodyQueue.poll(waitNs, TimeUnit.NANOSECONDS)
      ?: throw InterruptedIOException(
        "read timeout after ${TimeUnit.NANOSECONDS.toMillis(waitNs)}ms",
      )
  }
}

/**
 * Wraps a sink so its `timeout()` returns the externally-owned [Timeout] the codec
 * mutates, while the underlying sink still does the actual write dispatch. Equivalent to
 * overriding `timeout()` to return the caller's instance; `write`/`flush`/`close` are
 * delegated unchanged.
 */
private fun Sink.withExternalTimeout(external: Timeout): Sink =
  object : Sink {
    override fun write(
      source: Buffer,
      byteCount: Long,
    ) {
      // Propagate the external timeout's current bound to the underlying sink for this
      // dispatch. QuicRequestBodySink reads its internal timeout in currentWriteTimeoutMillis.
      this@withExternalTimeout.timeout().copyFrom(external)
      this@withExternalTimeout.write(source, byteCount)
    }

    override fun flush() = this@withExternalTimeout.flush()

    override fun close() = this@withExternalTimeout.close()

    override fun timeout(): Timeout = external
  }

/**
 * Mirror [okio.Timeout.timeoutNanos] + deadline into [target], so the internal timeout
 * on the wrapped sink sees whatever the external one says at the moment of the call.
 */
private fun Timeout.copyFrom(other: Timeout) {
  val nanos = other.timeoutNanos()
  if (nanos == 0L) clearTimeout() else timeout(nanos, TimeUnit.NANOSECONDS)
  if (other.hasDeadline()) deadlineNanoTime(other.deadlineNanoTime()) else clearDeadline()
}

/**
 * Current read budget in milliseconds, or `<= 0` for "no bound". Mirrors the logic
 * inside [QuicBodySource.awaitBodyEvent] but surfaces a milliseconds value for use as a
 * `CompletableFuture.get` bound.
 */
private fun Timeout.timeoutMillisOrNoBound(): Long {
  val nanos = timeoutNanos()
  val hasDeadline = hasDeadline()
  if (nanos == 0L && !hasDeadline) return 0L
  val deadlineRemaining = if (hasDeadline) deadlineNanoTime() - System.nanoTime() else Long.MAX_VALUE
  val timeoutRemaining = if (nanos != 0L) nanos else Long.MAX_VALUE
  val waitNs = minOf(deadlineRemaining, timeoutRemaining)
  if (waitNs <= 0L) return 1L // immediate timeout
  return TimeUnit.NANOSECONDS.toMillis(waitNs).coerceAtLeast(1L)
}
