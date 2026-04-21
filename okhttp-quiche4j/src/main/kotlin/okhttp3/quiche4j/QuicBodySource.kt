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
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.EventListener
import okio.Buffer
import okio.Source
import okio.Timeout

/**
 * An [okio.Source] that drains a [QuicStream]'s [bodyQueue][QuicStream.bodyQueue].
 * Bytes are produced by the pool connection's I/O thread (see [QuicPooledConnection.pollAll]);
 * consumers on another thread pull them here via blocking [java.util.concurrent.BlockingQueue].
 *
 * Lifecycle events:
 *   * [responseBodyStart][EventListener.responseBodyStart] fires on the first [read].
 *   * [responseBodyEnd][EventListener.responseBodyEnd] fires on EOF / [close].
 *   * [connectionReleased][EventListener.connectionReleased] fires on [close], marking the
 *     pooled connection as free for reuse.
 */
internal class QuicBodySource(
  private val stream: QuicStream,
  private val eventListener: EventListener,
  private val call: Call,
  private val connectionHandle: QuicConnectionHandle,
  readTimeoutMillis: Long = 0L,
) : Source {
  // Initialised from the chain's readTimeout; callers can still mutate it via timeout()
  // (e.g. okio's withTimeout(...) blocks) to narrow further on a per-read basis.
  private val timeout =
    Timeout().apply {
      if (readTimeoutMillis > 0) timeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
    }

  @Volatile private var closed: Boolean = false
  private var startedReading: Boolean = false
  private var endedReading: Boolean = false
  private var bytesRead: Long = 0

  /**
   * The [Buffer] we're currently draining into the caller's sink. Replaced when empty by
   * the next [BodyEvent.Bytes] pulled off the stream's queue. Kept as a field rather
   * than pulled-per-read because a caller that asks for fewer bytes than one chunk holds
   * needs to come back for the rest on the next read.
   */
  private var currentChunk: Buffer? = null
  private var reachedEnd: Boolean = false

  override fun read(
    sink: Buffer,
    byteCount: Long,
  ): Long {
    check(byteCount >= 0) { "byteCount < 0: $byteCount" }
    if (closed) throw IOException("stream closed")
    if (!startedReading) {
      startedReading = true
      eventListener.responseBodyStart(call)
    }

    if (reachedEnd && (currentChunk == null || currentChunk!!.size == 0L)) {
      finishRead(ioEx = null)
      return -1L
    }

    if (currentChunk == null || currentChunk!!.size == 0L) {
      when (val event =
        try {
          awaitBodyEvent()
        } catch (ie: InterruptedException) {
          Thread.currentThread().interrupt()
          val ioe = IOException("interrupted while reading H/3 body")
          finishRead(ioe)
          throw ioe
        } catch (te: InterruptedIOException) {
          finishRead(te)
          throw te
        }) {
        is BodyEvent.Bytes -> currentChunk = event.data
        is BodyEvent.End -> {
          reachedEnd = true
          finishRead(ioEx = null)
          return -1L
        }
        is BodyEvent.Error -> {
          reachedEnd = true
          finishRead(event.cause)
          throw event.cause
        }
      }
    }

    val chunk = currentChunk!!
    val toWrite = minOf(chunk.size, byteCount)
    // sink.write(source, byteCount) transfers whole segments from `chunk` into `sink`
    // when they're aligned — so the bytes the I/O thread wrote into `chunk`'s segments
    // are moved (not copied) all the way to the caller's ResponseBody. At misaligned or
    // sub-segment boundaries okio falls back to a byte copy, which is what we had before.
    sink.write(chunk, toWrite)
    bytesRead += toWrite
    return toWrite
  }

  override fun close() {
    if (closed) return
    closed = true
    if (startedReading) finishRead(ioEx = null)
    stream.connection.releaseStream(stream)
    eventListener.connectionReleased(call, connectionHandle)
  }

  override fun timeout(): Timeout = timeout

  /**
   * Block on [QuicStream.bodyQueue] but honour [timeout]: its configured `timeoutNanos`
   * and its deadline (if set) both bound how long we'll wait. If neither is set we fall
   * back to an uninterrupted `take()`, matching the pre-timeout behaviour.
   *
   * Throws [InterruptedIOException] on timeout; propagates [InterruptedException] verbatim
   * so the caller can restore the interrupt flag in the outer catch.
   */
  private fun awaitBodyEvent(): BodyEvent {
    val timeoutNs = timeout.timeoutNanos()
    val hasDeadline = timeout.hasDeadline()
    if (timeoutNs == 0L && !hasDeadline) {
      return stream.bodyQueue.take()
    }
    val deadlineRemaining = if (hasDeadline) timeout.deadlineNanoTime() - System.nanoTime() else Long.MAX_VALUE
    val timeoutRemaining = if (timeoutNs != 0L) timeoutNs else Long.MAX_VALUE
    val waitNs = minOf(deadlineRemaining, timeoutRemaining)
    if (waitNs <= 0) throw InterruptedIOException("read timeout")
    return stream.bodyQueue.poll(waitNs, TimeUnit.NANOSECONDS)
      ?: throw InterruptedIOException("read timeout after ${TimeUnit.NANOSECONDS.toMillis(waitNs)}ms")
  }

  private fun finishRead(ioEx: IOException?) {
    if (endedReading) return
    endedReading = true
    if (ioEx == null) {
      eventListener.responseBodyEnd(call, bytesRead)
    } else {
      eventListener.responseFailed(call, ioEx)
    }
  }
}
