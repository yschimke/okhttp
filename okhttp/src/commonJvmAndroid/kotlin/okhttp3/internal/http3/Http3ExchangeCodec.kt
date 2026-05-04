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
package okhttp3.internal.http3

import java.io.IOException
import java.net.ProtocolException
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.Headers
import okhttp3.Http3Session
import okhttp3.Http3Stream
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.headersContentLength
import okhttp3.internal.http.ExchangeCodec
import okhttp3.internal.http.HTTP_CONTINUE
import okhttp3.internal.http.RealInterceptorChain
import okhttp3.internal.http.RequestLine
import okhttp3.internal.http.StatusLine
import okhttp3.internal.http2.Header.Companion.RESPONSE_STATUS_UTF8
import okhttp3.internal.http2.Header.Companion.TARGET_AUTHORITY_UTF8
import okhttp3.internal.http2.Header.Companion.TARGET_METHOD_UTF8
import okhttp3.internal.http2.Header.Companion.TARGET_PATH_UTF8
import okhttp3.internal.http2.Header.Companion.TARGET_SCHEME_UTF8
import okhttp3.internal.http.promisesBody
import okhttp3.internal.immutableListOf
import okio.Sink
import okio.Socket
import okio.Source

/**
 * The [ExchangeCodec] OkHttp uses when a call is routed over HTTP/3. Mirrors
 * [okhttp3.internal.http2.Http2ExchangeCodec] — request/response are mapped through
 * [Http3Stream] in the same way H/2 maps through `Http2Stream`. Header framing and
 * QPACK encoding live inside the [Http3Session] implementation; the codec only deals
 * in [Headers].
 */
internal class Http3ExchangeCodec(
  @Suppress("unused") client: OkHttpClient,
  override val carrier: ExchangeCodec.Carrier,
  private val chain: RealInterceptorChain,
  private val session: Http3Session,
) : ExchangeCodec {
  @Volatile private var stream: Http3Stream? = null

  @Volatile private var canceled = false

  override val isResponseComplete: Boolean
    get() = stream?.isSourceComplete == true

  override val socket: Socket
    get() = stream!!

  override fun createRequestBody(
    request: Request,
    contentLength: Long,
  ): Sink = stream!!.sink

  @Throws(IOException::class)
  override fun writeRequestHeaders(request: Request) {
    if (stream != null) return

    val hasRequestBody = request.body != null
    val requestHeaders = http3HeadersList(request)
    stream = session.newStream(requestHeaders, hasRequestBody)
    // If we were canceled between the callsite starting and the stream being created,
    // propagate that immediately rather than racing the first write.
    if (canceled) {
      stream!!.cancel()
      throw IOException("Canceled")
    }
    stream!!.readTimeout().timeout(chain.readTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
    stream!!.writeTimeout().timeout(chain.writeTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
  }

  @Throws(IOException::class)
  override fun flushRequest() {
    session.flush()
  }

  @Throws(IOException::class)
  override fun finishRequest() {
    stream!!.sink.close()
  }

  @Throws(IOException::class)
  override fun readResponseHeaders(expectContinue: Boolean): Response.Builder? {
    val stream = stream ?: throw IOException("stream wasn't created")
    val headers = stream.takeHeaders(callerIsIdle = expectContinue)
    val responseBuilder = readHttp3HeadersList(headers)
    return if (expectContinue && responseBuilder.code == HTTP_CONTINUE) {
      null
    } else {
      responseBuilder
    }
  }

  override fun reportedContentLength(response: Response): Long =
    when {
      !response.promisesBody() -> 0L
      else -> response.headersContentLength()
    }

  override fun openResponseBodySource(response: Response): Source = stream!!.source

  @Throws(IOException::class)
  override fun peekTrailers(): Headers? {
    val trailers = stream?.peekTrailers() ?: return null
    return trailersToHeaders(trailers)
  }

  override fun cancel() {
    canceled = true
    stream?.cancel()
  }

  companion object {
    private const val CONNECTION = "connection"
    private const val HOST = "host"
    private const val KEEP_ALIVE = "keep-alive"
    private const val PROXY_CONNECTION = "proxy-connection"
    private const val TRANSFER_ENCODING = "transfer-encoding"
    private const val TE = "te"
    private const val ENCODING = "encoding"
    private const val UPGRADE = "upgrade"

    // RFC 9114 §4.2: forbidden in H/3 request headers (same list as H/2 plus the
    // request pseudo-headers we add ourselves, which callers must not double-supply).
    private val HTTP_3_SKIPPED_REQUEST_HEADERS =
      immutableListOf(
        CONNECTION,
        HOST,
        KEEP_ALIVE,
        PROXY_CONNECTION,
        TE,
        TRANSFER_ENCODING,
        ENCODING,
        UPGRADE,
        ":method",
        ":path",
        ":scheme",
        ":authority",
      )

    // RFC 9114 §4.2: same list of hop-by-hop / obsolete headers the peer may not send.
    private val HTTP_3_SKIPPED_RESPONSE_HEADERS =
      immutableListOf(
        CONNECTION,
        HOST,
        KEEP_ALIVE,
        PROXY_CONNECTION,
        TE,
        TRANSFER_ENCODING,
        ENCODING,
        UPGRADE,
      )

    /**
     * Build the HTTP/3 request header list from [request]. The pseudo-headers come
     * first in RFC 9114 §4.3.1 order, followed by the regular request headers
     * (lowercased, with hop-by-hop and already-pseudo entries filtered out).
     *
     * `TE: trailers` is the sole exception carried through (RFC 9114 inherits RFC 7540
     * §8.1.2.2): gRPC-over-HTTP/3 relies on it.
     */
    @JvmStatic
    fun http3HeadersList(request: Request): Headers {
      val headers = request.headers
      val result = Headers.Builder()
      result.addLenient(TARGET_METHOD_UTF8, request.method)
      result.addLenient(TARGET_PATH_UTF8, RequestLine.requestPath(request.url))
      val host = request.header("Host")
      if (host != null) {
        result.addLenient(TARGET_AUTHORITY_UTF8, host)
      }
      result.addLenient(TARGET_SCHEME_UTF8, request.url.scheme)

      for (i in 0 until headers.size) {
        val name = headers.name(i).lowercase(Locale.US)
        if (name !in HTTP_3_SKIPPED_REQUEST_HEADERS ||
          name == TE && headers.value(i) == "trailers"
        ) {
          result.addLenient(name, headers.value(i))
        }
      }
      return result.build()
    }

    /**
     * Decode the peer's response HEADERS frame: `:status` becomes the status line,
     * everything else that survives the hop-by-hop filter goes into [Headers].
     */
    @JvmStatic
    fun readHttp3HeadersList(headerBlock: Headers): Response.Builder {
      var statusLine: StatusLine? = null
      val headersBuilder = Headers.Builder()
      for (i in 0 until headerBlock.size) {
        val name = headerBlock.name(i)
        val value = headerBlock.value(i)
        if (name == RESPONSE_STATUS_UTF8) {
          statusLine = StatusLine.parse("HTTP/1.1 $value")
        } else if (name !in HTTP_3_SKIPPED_RESPONSE_HEADERS) {
          headersBuilder.addLenient(name, value)
        }
      }
      if (statusLine == null) throw ProtocolException("Expected ':status' header not present")

      return Response
        .Builder()
        .protocol(Protocol.HTTP_3)
        .code(statusLine.code)
        .message(statusLine.message)
        .headers(headersBuilder.build())
    }

    private fun trailersToHeaders(trailers: Headers): Headers {
      val builder = Headers.Builder()
      for (i in 0 until trailers.size) {
        val name = trailers.name(i)
        // Trailers never contain pseudo-headers per RFC 9114 §4.1.
        if (name.startsWith(":")) continue
        if (name in HTTP_3_SKIPPED_RESPONSE_HEADERS) continue
        builder.addLenient(name, trailers.value(i))
      }
      return builder.build()
    }
  }
}
