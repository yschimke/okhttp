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

import io.quiche4j.http3.Http3Header
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.X509TrustManager
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.internal.http.RealInterceptorChain
import okhttp3.internal.platform.Platform
import okio.Buffer
import okio.buffer

/**
 * Terminal interceptor in [Quiche4jInterceptor]'s inner chain. Performs the HTTP/3 fetch by
 * acquiring a pooled QUIC connection from [Quiche4jEngine], opening one H/3 stream, and wiring
 * its headers + body queue into a [QuicBodySource]-backed [Response].
 *
 * See `PLAN.md` for the Stage 2 plan that folds this into an `Http3ExchangeCodec` and the core
 * route-planner.
 */
internal class Quiche4jCallServer(
  private val engine: Quiche4jEngine,
) : Interceptor {
  override fun intercept(chain: Interceptor.Chain): Response {
    val realChain = chain as RealInterceptorChain
    val call = realChain.call
    val eventListener = call.eventListener
    val request = chain.request()
    val url = request.url

    // https-only is enforced by Quiche4jInterceptor before we get here.
    check(url.scheme == "https") { "quiche4j transport requires https://, got ${url.scheme}" }

    // DNS.
    eventListener.dnsStart(call, url.host)
    val addresses = realChain.dns.lookup(url.host)
    eventListener.dnsEnd(call, url.host, addresses)
    if (addresses.isEmpty()) throw IOException("No addresses for ${url.host}")
    val peerPort =
      (Http3Preference.of(request) as? Http3Preference.Force)?.portOverride ?: url.port
    val peer = InetSocketAddress(addresses.first(), peerPort)

    val sentAt = System.currentTimeMillis()

    eventListener.connectStart(call, peer, Proxy.NO_PROXY)
    eventListener.secureConnectStart(call)

    // Source trust config from the OkHttpClient so the quiche4j transport honours the same
    // hostname verifier / trust manager as the H/1.1 / H/2 paths. When neither is set, fall
    // back to the platform defaults that OkHttpClient.Builder would have applied.
    val trustManager: X509TrustManager =
      realChain.x509TrustManagerOrNull ?: Platform.get().platformTrustManager()
    val hostnameVerifier = realChain.hostnameVerifier

    val pooled =
      try {
        engine.acquire(
          url.host,
          peerPort,
          peer,
          trustManager = trustManager,
          hostnameVerifier = hostnameVerifier,
          handshakeTimeoutMillis = chain.connectTimeoutMillis().toLong(),
          // QUIC's max_idle_timeout is a connection-level parameter (RFC 9000 §10.1) that
          // covers any packet exchange, not just application bytes. Source it from the
          // caller's readTimeout since that's the closest analog OkHttp already exposes — and
          // when unset, readTimeout's default (10s) matches what OkHttp applies to H/2.
          maxIdleTimeoutMillis = chain.readTimeoutMillis().toLong(),
        )
      } catch (e: Exception) {
        val ioe = e as? IOException ?: IOException(e)
        eventListener.connectFailed(call, peer, Proxy.NO_PROXY, null, ioe)
        throw ioe
      }

    eventListener.secureConnectEnd(call, pooled.handshake)
    eventListener.connectEnd(call, peer, Proxy.NO_PROXY, Protocol.HTTP_3)

    val handle = QuicConnectionHandle(peer, Protocol.HTTP_3, pooled.handshake)
    eventListener.connectionAcquired(call, handle)

    // AtomicReference rather than a local var so the cancellation hook (which runs on the
    // cancelling thread) sees the assignment from openStream promptly.
    val streamRef = java.util.concurrent.atomic.AtomicReference<QuicStream?>()
    // Register the cancellation hook *before* openStream so a cancel racing with openStream
    // doesn't slip through. The hook runs on Call.cancel() via Call.addEventListener — no
    // polling. If cancel fires before stream is set, nothing to tear down; if it fires after,
    // cancelStream sends STOP_SENDING + RESET_STREAM and unblocks the consumer with an IOE.
    CancellationHook.attach(call) { streamRef.get()?.let { pooled.cancelStream(it) } }

    var returnedSuccessfully = false
    try {
      eventListener.requestHeadersStart(call)
      val reqHeaders = buildH3Request(request)
      val rawBody = request.body
      val isDuplex = rawBody?.isDuplex() == true
      val hasBody = rawBody != null
      val bufferedBody: ByteArray? =
        if (hasBody && !isDuplex) {
          Buffer().also { rawBody!!.writeTo(it) }.readByteArray()
        } else {
          null
        }
      if (hasBody) eventListener.requestBodyStart(call)
      // connectTimeoutMillis is a conservative ceiling on how long we wait for the pool's I/O
      // thread to pick up the sendRequest task — in practice microseconds. If we exceed it,
      // the I/O thread has wedged and we'd hang forever without a bound.
      val stream =
        pooled.openStream(
          reqHeaders,
          bufferedBody,
          streamingBody = isDuplex,
          timeoutMillis = chain.connectTimeoutMillis().toLong(),
        )
      streamRef.set(stream)
      eventListener.requestHeadersEnd(call, request)

      if (isDuplex) {
        // Same contract as okhttp3.internal.http.CallServerInterceptor: call writeTo on the
        // call thread. The caller's writeTo is expected to register an async producer and
        // return quickly without closing the sink; the async producer eventually writes bytes
        // + closes the sink (which sends fin=true over the QUIC stream). The call thread then
        // falls through to read response headers. QuicRequestBodySink's writes hop onto the
        // pool's I/O thread so quiche stays single-threaded per connection — no extra thread
        // of our own.
        val sink = QuicRequestBodySink(stream, pooled, chain.writeTimeoutMillis().toLong()).buffer()
        rawBody!!.writeTo(sink)
        // Do NOT close the sink here — for duplex, the application closes it when its async
        // producer finishes. requestBodyEnd will fire when the sink is closed.
      } else if (!hasBody) {
        // No body — headers were sent with fin=true.
      } else {
        eventListener.requestBodyEnd(call, (bufferedBody?.size ?: 0).toLong())
      }

      eventListener.responseHeadersStart(call)
      // readTimeout bounds how long we wait for the peer to send :status. OkHttp's convention
      // is readTimeout=0 → no timeout, so preserve that by calling get() without a bound.
      val readTimeoutMs = chain.readTimeoutMillis().toLong()
      val responseHeaders =
        try {
          if (readTimeoutMs <= 0L) {
            stream.headersFuture.get()
          } else {
            stream.headersFuture.get(readTimeoutMs, TimeUnit.MILLISECONDS)
          }
        } catch (e: TimeoutException) {
          throw IOException("timed out waiting for HTTP/3 response headers after ${readTimeoutMs}ms", e)
        } catch (e: ExecutionException) {
          val cause = e.cause ?: e
          throw cause as? IOException ?: IOException(cause)
        } catch (e: InterruptedException) {
          Thread.currentThread().interrupt()
          throw java.io.InterruptedIOException("interrupted waiting for HTTP/3 response headers")
        }
      if (responseHeaders.status < 0) {
        throw java.io.IOException(
          "HTTP/3 peer sent a malformed :status pseudo-header (not parseable as a positive int)",
        )
      }

      val contentType =
        responseHeaders.headers
          .firstOrNull { it.first.equals("content-type", ignoreCase = true) }
          ?.second
          ?.toMediaTypeOrNull()

      val response =
        Response
          .Builder()
          .request(request)
          .protocol(Protocol.HTTP_3)
          .code(responseHeaders.status)
          .message("") // HTTP/3 has no reason phrase.
          .headers(
            Headers
              .Builder()
              .apply { responseHeaders.headers.forEach { (n, v) -> add(n, v) } }
              .build(),
          ).body(bodyOf(stream, contentType, eventListener, call, handle))
          .sentRequestAtMillis(sentAt)
          .receivedResponseAtMillis(System.currentTimeMillis())
          .handshake(pooled.handshake)
          .build()

      eventListener.responseHeadersEnd(call, response)
      returnedSuccessfully = true
      return response
    } finally {
      if (!returnedSuccessfully) {
        streamRef.get()?.let {
          // Send STOP_SENDING + RESET_STREAM so the remote doesn't keep producing bytes for
          // an abandoned stream (and chewing up connection-level flow control).
          pooled.cancelStream(it)
          pooled.releaseStream(it)
        }
        eventListener.connectionReleased(call, handle)
      }
      // On success, lifecycle continues via QuicBodySource.close().
    }
  }

  private fun bodyOf(
    stream: QuicStream,
    contentType: okhttp3.MediaType?,
    eventListener: okhttp3.EventListener,
    call: okhttp3.Call,
    handle: QuicConnectionHandle,
  ): ResponseBody {
    val source = QuicBodySource(stream, eventListener, call, handle).buffer()
    return source.asResponseBody(contentType, -1L)
  }

  /**
   * Build an HTTP/3 request header list from an okhttp3.Request. The `User-Agent`, `Host`, and
   * content headers are already present — [BridgeInterceptor] ran earlier in the inner chain and
   * populated them to match OkHttp's normal behaviour. We just forward everything the caller
   * plus BridgeInterceptor produced, minus connection-level headers that don't apply to H/3.
   */
  private fun buildH3Request(request: okhttp3.Request): List<Http3Header> {
    val url = request.url
    val headers = mutableListOf<Http3Header>()
    headers += Http3Header(":method", request.method)
    headers += Http3Header(":scheme", url.scheme)
    headers +=
      Http3Header(
        ":authority",
        if (url.port == url.scheme.defaultPort()) url.host else "${url.host}:${url.port}",
      )
    headers += Http3Header(":path", url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: ""))
    for ((name, value) in request.headers) {
      val lower = name.lowercase()
      if (lower.startsWith(":")) continue
      // Skip connection-management headers that aren't meaningful in H/3.
      if (lower == "host" || lower == "connection" || lower == "transfer-encoding" || lower == "upgrade") continue
      headers += Http3Header(lower, value)
    }
    return headers
  }

  private fun String.defaultPort(): Int =
    when (this) {
      "https" -> 443
      "http" -> 80
      else -> -1
    }
}
