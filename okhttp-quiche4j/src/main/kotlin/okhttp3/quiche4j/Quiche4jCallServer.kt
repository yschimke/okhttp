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
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.internal.http.RealInterceptorChain
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
    val peer = InetSocketAddress(addresses.first(), url.port)

    val sentAt = System.currentTimeMillis()

    eventListener.connectStart(call, peer, Proxy.NO_PROXY)
    eventListener.secureConnectStart(call)

    val pooled =
      try {
        engine.acquire(url.host, url.port, peer)
      } catch (e: Exception) {
        val ioe = e as? IOException ?: IOException(e)
        eventListener.connectFailed(call, peer, Proxy.NO_PROXY, null, ioe)
        throw ioe
      }

    eventListener.secureConnectEnd(call, pooled.handshake)
    eventListener.connectEnd(call, peer, Proxy.NO_PROXY, Protocol.HTTP_3)

    val handle = QuicConnectionHandle(peer, Protocol.HTTP_3, pooled.handshake)
    eventListener.connectionAcquired(call, handle)

    var stream: QuicStream? = null
    var returnedSuccessfully = false
    try {
      eventListener.requestHeadersStart(call)
      val reqHeaders = buildH3Request(request, engine.userAgent)
      val hasBody = request.body != null
      val body: ByteArray? =
        if (hasBody) Buffer().also { request.body!!.writeTo(it) }.readByteArray() else null
      if (hasBody) eventListener.requestBodyStart(call)
      stream = pooled.openStream(reqHeaders, body)
      eventListener.requestHeadersEnd(call, request)
      if (hasBody) eventListener.requestBodyEnd(call, (body?.size ?: 0).toLong())

      eventListener.responseHeadersStart(call)
      val responseHeaders = stream.headersFuture.get()

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
        stream?.let { pooled.releaseStream(it) }
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

  private fun buildH3Request(
    request: okhttp3.Request,
    userAgent: String,
  ): List<Http3Header> {
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
    if (request.header("user-agent") == null) headers += Http3Header("user-agent", userAgent)
    for ((name, value) in request.headers) {
      val lower = name.lowercase()
      if (lower.startsWith(":")) continue
      if (lower == "host" || lower == "connection" || lower == "transfer-encoding" || lower == "upgrade") continue
      headers += Http3Header(lower, value)
    }
    request.body?.let { body ->
      if (body.contentLength() >= 0 && request.header("content-length") == null) {
        headers += Http3Header("content-length", body.contentLength().toString())
      }
      body.contentType()?.let {
        if (request.header("content-type") == null) headers += Http3Header("content-type", it.toString())
      }
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
