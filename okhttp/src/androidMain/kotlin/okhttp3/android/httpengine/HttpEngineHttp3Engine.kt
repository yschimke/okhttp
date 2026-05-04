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
package okhttp3.android.httpengine

import android.net.http.BidirectionalStream
import android.net.http.HeaderBlock
import android.net.http.HttpEngine
import android.net.http.HttpException
import android.net.http.UrlResponseInfo
import android.os.Build
import androidx.annotation.RequiresExtension
import java.io.IOException
import java.nio.ByteBuffer
import java.util.AbstractMap
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import okhttp3.CipherSuite
import okhttp3.Handshake
import okhttp3.Headers
import okhttp3.Http3Engine
import okhttp3.Http3Session
import okhttp3.Http3Stream
import okhttp3.OkHttpClient
import okhttp3.Route
import okhttp3.TlsVersion
import okhttp3.internal.SuppressSignatureCheck
import okhttp3.internal.http2.Header.Companion.RESPONSE_STATUS_UTF8
import okhttp3.internal.http2.Header.Companion.TARGET_AUTHORITY_UTF8
import okhttp3.internal.http2.Header.Companion.TARGET_METHOD_UTF8
import okhttp3.internal.http2.Header.Companion.TARGET_PATH_UTF8
import okhttp3.internal.http2.Header.Companion.TARGET_SCHEME_UTF8
import okio.Buffer
import okio.Sink
import okio.Source
import okio.Timeout

/**
 * HTTP/3 transport backed by Android's [HttpEngine].
 *
 * The supplied engine provider should return the same long-lived engine once initialization
 * completes, or null while initialization is still running. OkHttp skips HTTP/3 while this returns
 * null; once it returns an engine, the existing client begins planning HTTP/3 routes.
 *
 * Android exposes QUIC hints and protocol enablement on [HttpEngine.Builder], not on individual
 * [BidirectionalStream] requests. For a strictly HTTP/3 engine, configure the builder with QUIC
 * enabled, HTTP/2 disabled, and any known `addQuicHint()` / `QuicOptions` host allowlist entries.
 *
 * HttpEngine exposes requests, not a separately-opened QUIC connection. This adapter maps the
 * OkHttp HTTP/3 SPI's session object to a lightweight owner for per-request
 * [BidirectionalStream]s, then rejects any stream that does not negotiate an HTTP/3 ALPN. Metadata
 * HttpEngine does not expose through that API, such as peer certificates and live peer stream
 * limits, uses conservative defaults.
 */
@SuppressSignatureCheck
@RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)
class HttpEngineHttp3Engine(
  private val httpEngine: Supplier<HttpEngine?>,
  private val executor: Executor = newHttpEngineExecutor(),
) : Http3Engine {
  constructor(
    httpEngine: HttpEngine,
    executor: Executor = newHttpEngineExecutor(),
  ) : this(Supplier { httpEngine }, executor)

  override fun isAvailable(
    client: OkHttpClient,
    route: Route,
  ): Boolean = httpEngine.get() != null

  override fun connect(
    client: OkHttpClient,
    route: Route,
  ): Http3Session {
    val engine = httpEngine.get() ?: throw IOException("HttpEngine is not initialized")
    return HttpEngineHttp3Session(
      httpEngine = engine,
      executor = executor,
      route = route,
    )
  }

  companion object {
    private fun newHttpEngineExecutor(): Executor =
      Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "OkHttp HttpEngine").apply {
          isDaemon = true
        }
      }
  }
}

@SuppressSignatureCheck
@RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)
private class HttpEngineHttp3Session(
  private val httpEngine: HttpEngine,
  private val executor: Executor,
  override val route: Route,
) : Http3Session {
  private val closed = AtomicBoolean()

  override val handshake: Handshake =
    Handshake.get(
      TlsVersion.TLS_1_3,
      CipherSuite.TLS_AES_128_GCM_SHA256,
      peerCertificates = listOf(),
      localCertificates = listOf(),
    )

  override val maxConcurrentStreams: Int = Int.MAX_VALUE

  override val isHealthy: Boolean
    get() = !closed.get()

  override fun newStream(
    headers: Headers,
    hasRequestBody: Boolean,
  ): Http3Stream {
    check(!closed.get()) { "closed" }
    return HttpEngineHttp3Stream(
      session = this,
      httpEngine = httpEngine,
      executor = executor,
      headers = headers,
    )
  }

  override fun flush() {
  }

  override fun close() {
    closed.set(true)
  }
}

@SuppressSignatureCheck
@RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)
private class HttpEngineHttp3Stream(
  override val session: Http3Session,
  httpEngine: HttpEngine,
  executor: Executor,
  headers: Headers,
) : Http3Stream {
  private val callback = Callback()
  private val stream: BidirectionalStream
  private val readTimeout = Timeout()
  private val writeTimeout = Timeout()
  private val responseHeaders = LinkedBlockingQueue<Result<Headers>>()
  private val readEvents = LinkedBlockingQueue<ReadEvent>()
  private val writeEvents = LinkedBlockingQueue<Result<Unit>>()
  private val ready = CountDownLatch(1)
  private val readyFailure = AtomicReference<IOException?>()
  private val trailersRef = AtomicReference<Headers?>()
  private val requestClosed = AtomicBoolean()
  private val canceled = AtomicBoolean()
  private val readBuffer = ByteBuffer.allocateDirect(READ_BUFFER_SIZE)

  init {
    val request = headers.toHttpEngineRequest()
    callback.owner = this

    val builder =
      httpEngine
        .newBidirectionalStreamBuilder(request.url, executor, callback)
        .setHttpMethod(request.method)
        .setDelayRequestHeadersUntilFirstFlushEnabled(false)

    for ((name, value) in request.headers) {
      builder.addHeader(name, value)
    }

    stream = builder.build()
    stream.start()
  }

  override val source: Source =
    object : Source {
      private val current = Buffer()

      override fun read(
        sink: Buffer,
        byteCount: Long,
      ): Long {
        if (byteCount == 0L) return 0L

        while (current.size == 0L) {
          when (val event = readEvents.take(readTimeout)) {
            is ReadEvent.Data -> current.write(event.bytes)
            is ReadEvent.End -> return -1L
            is ReadEvent.Failure -> throw event.exception
          }
        }

        return current.read(sink, byteCount)
      }

      override fun timeout(): Timeout = readTimeout

      override fun close() {
        cancel()
      }
    }

  override val sink: Sink =
    object : Sink {
      override fun write(
        source: Buffer,
        byteCount: Long,
      ) {
        awaitReady()

        var remaining = byteCount
        while (remaining > 0L) {
          val byteCountToWrite = minOf(remaining, WRITE_BUFFER_SIZE.toLong()).toInt()
          val bytes = source.readByteArray(byteCountToWrite.toLong())
          val buffer = ByteBuffer.allocateDirect(bytes.size)
          buffer.put(bytes)
          buffer.flip()

          stream.write(buffer, false)
          awaitWrite()
          remaining -= byteCountToWrite
        }
      }

      override fun flush() {
        awaitReady()
        stream.flush()
      }

      override fun timeout(): Timeout = writeTimeout

      override fun close() {
        if (requestClosed.getAndSet(true)) return

        awaitReady()
        stream.write(ByteBuffer.allocateDirect(0), true)
        awaitWrite()
      }
    }

  override val isSourceComplete: Boolean
    get() = stream.isDone

  override fun readTimeout(): Timeout = readTimeout

  override fun writeTimeout(): Timeout = writeTimeout

  override fun takeHeaders(callerIsIdle: Boolean): Headers =
    responseHeaders.take(readTimeout).getOrThrow()

  override fun peekTrailers(): Headers? =
    if (stream.isDone) {
      trailersRef.get() ?: Headers.EMPTY
    } else {
      trailersRef.get()
    }

  override fun cancel() {
    if (canceled.getAndSet(true)) return

    stream.cancel()
    fail(IOException("Canceled"))
  }

  private fun awaitReady() {
    while (true) {
      writeTimeout.throwIfReached()
      if (ready.await(100L, TimeUnit.MILLISECONDS)) {
        readyFailure.get()?.let { throw it }
        return
      }
    }
  }

  private fun awaitWrite() {
    writeEvents.take(writeTimeout).getOrThrow()
  }

  private fun fail(exception: IOException) {
    responseHeaders.offer(Result.failure(exception))
    readyFailure.compareAndSet(null, exception)
    ready.countDown()
    writeEvents.offer(Result.failure(exception))
    readEvents.offer(ReadEvent.Failure(exception))
  }

  private fun startRead(stream: BidirectionalStream) {
    readBuffer.clear()
    stream.read(readBuffer)
  }

  private class Callback : BidirectionalStream.Callback {
    lateinit var owner: HttpEngineHttp3Stream

    override fun onStreamReady(stream: BidirectionalStream) {
      owner.ready.countDown()
    }

    override fun onResponseHeadersReceived(
      stream: BidirectionalStream,
      info: UrlResponseInfo,
    ) {
      if (!info.negotiatedProtocol.isHttp3()) {
        val protocol = info.negotiatedProtocol.ifEmpty { "<unknown>" }
        owner.fail(IOException("Expected HTTP/3 but HttpEngine negotiated $protocol"))
        stream.cancel()
        return
      }

      owner.responseHeaders.offer(Result.success(info.toHeaders()))
      owner.startRead(stream)
    }

    override fun onReadCompleted(
      stream: BidirectionalStream,
      info: UrlResponseInfo,
      buffer: ByteBuffer,
      endOfStream: Boolean,
    ) {
      buffer.flip()
      if (buffer.hasRemaining()) {
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        owner.readEvents.offer(ReadEvent.Data(bytes))
      }

      if (endOfStream) {
        owner.readEvents.offer(ReadEvent.End)
      } else {
        owner.startRead(stream)
      }
    }

    override fun onWriteCompleted(
      stream: BidirectionalStream,
      info: UrlResponseInfo,
      buffer: ByteBuffer,
      endOfStream: Boolean,
    ) {
      owner.writeEvents.offer(Result.success(Unit))
    }

    override fun onResponseTrailersReceived(
      stream: BidirectionalStream,
      info: UrlResponseInfo,
      trailers: HeaderBlock,
    ) {
      owner.trailersRef.set(trailers.toHeaders())
    }

    override fun onSucceeded(
      stream: BidirectionalStream,
      info: UrlResponseInfo,
    ) {
      owner.readEvents.offer(ReadEvent.End)
    }

    override fun onFailed(
      stream: BidirectionalStream,
      info: UrlResponseInfo?,
      error: HttpException,
    ) {
      owner.fail(IOException(error))
    }

    override fun onCanceled(
      stream: BidirectionalStream,
      info: UrlResponseInfo?,
    ) {
      owner.fail(IOException("Canceled"))
    }
  }

  private sealed class ReadEvent {
    data class Data(val bytes: ByteArray) : ReadEvent()

    data class Failure(val exception: IOException) : ReadEvent()

    data object End : ReadEvent()
  }

  private data class HttpEngineRequest(
    val url: String,
    val method: String,
    val headers: List<Map.Entry<String, String>>,
  )

  companion object {
    private const val READ_BUFFER_SIZE = 16 * 1024
    private const val WRITE_BUFFER_SIZE = 16 * 1024

    private fun Headers.toHttpEngineRequest(): HttpEngineRequest {
      var method: String? = null
      var scheme: String? = null
      var authority: String? = null
      var path: String? = null
      val requestHeaders = mutableListOf<Map.Entry<String, String>>()

      for (i in 0 until size) {
        when (val name = name(i)) {
          TARGET_METHOD_UTF8 -> method = value(i)
          TARGET_SCHEME_UTF8 -> scheme = value(i)
          TARGET_AUTHORITY_UTF8 -> authority = value(i)
          TARGET_PATH_UTF8 -> path = value(i)
          else -> requestHeaders += AbstractMap.SimpleImmutableEntry(name, value(i))
        }
      }

      return HttpEngineRequest(
        url = "${scheme ?: "https"}://${authority ?: error("missing :authority")}${path ?: "/"}",
        method = method ?: "GET",
        headers = requestHeaders,
      )
    }

    private fun UrlResponseInfo.toHeaders(): Headers =
      headers.toHeaders {
        addLenient(RESPONSE_STATUS_UTF8, httpStatusCode.toString())
      }

    private fun HeaderBlock.toHeaders(
      beforeHeaders: Headers.Builder.() -> Unit = {},
    ): Headers {
      val builder = Headers.Builder()
      builder.beforeHeaders()
      for ((name, value) in asList) {
        builder.addLenient(name, value)
      }
      return builder.build()
    }

    private fun String.isHttp3(): Boolean =
      split(',', '+', ' ')
        .map { it.trim().lowercase() }
        .any { it == "h3" || it.startsWith("h3-") }

    private fun <T> BlockingQueue<T>.take(timeout: Timeout): T {
      while (true) {
        timeout.throwIfReached()
        poll(100L, TimeUnit.MILLISECONDS)?.let {
          return it
        }
      }
    }
  }
}
