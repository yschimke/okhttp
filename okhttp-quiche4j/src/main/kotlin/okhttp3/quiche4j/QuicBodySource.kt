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
) : Source {
  @Volatile private var closed: Boolean = false
  private var startedReading: Boolean = false
  private var endedReading: Boolean = false
  private var bytesRead: Long = 0
  private var currentChunk: ByteArray? = null
  private var currentOffset: Int = 0
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

    if (reachedEnd && currentChunk == null) {
      finishRead(ioEx = null)
      return -1L
    }

    if (currentChunk == null) {
      when (val event =
        try {
          stream.bodyQueue.take()
        } catch (ie: InterruptedException) {
          Thread.currentThread().interrupt()
          val ioe = IOException("interrupted while reading H/3 body")
          finishRead(ioe)
          throw ioe
        }) {
        is BodyEvent.Bytes -> {
          currentChunk = event.data
          currentOffset = 0
        }
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
    val remaining = chunk.size - currentOffset
    val toWrite = minOf(remaining.toLong(), byteCount).toInt()
    sink.write(chunk, currentOffset, toWrite)
    currentOffset += toWrite
    bytesRead += toWrite
    if (currentOffset >= chunk.size) {
      currentChunk = null
      currentOffset = 0
    }
    return toWrite.toLong()
  }

  override fun close() {
    if (closed) return
    closed = true
    if (startedReading) finishRead(ioEx = null)
    stream.connection.releaseStream(stream)
    eventListener.connectionReleased(call, connectionHandle)
  }

  override fun timeout(): Timeout = Timeout.NONE

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
