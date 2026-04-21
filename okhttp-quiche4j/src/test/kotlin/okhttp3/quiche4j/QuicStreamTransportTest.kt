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

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNull
import assertk.assertions.messageContains
import java.io.IOException
import java.util.concurrent.TimeUnit
import okio.Buffer
import org.junit.jupiter.api.Test

/**
 * Narrow unit tests that exercise [QuicStream] + [QuicBodySource] in isolation, without
 * touching [QuicPooledConnection] or a real QUIC connection. Each test manipulates the
 * stream's [bodyQueue][QuicStream.bodyQueue] directly so we can inject crafted event
 * sequences — the [QuicPooledConnection] argument is never dereferenced by these paths.
 */
class QuicStreamTransportTest {
  // Bypass-constructor stub. The real QuicPooledConnection handshakes in its constructor;
  // the test-only factory on the companion lets us hand a QuicStream a non-null
  // `connection` argument without wiring a real pool. Safe because these tests only
  // exercise the read path — none of them call close() / sendBodyChunk() / cancel.
  private val stubPool: QuicPooledConnection = QuicPooledConnection.newStubForTesting()

  private fun newStream(): QuicStream = QuicStream(streamId = 0L, connection = stubPool)

  @Test fun `source drains one chunk per BodyEvent_Bytes`() {
    val stream = newStream()
    stream.deliverBody(Buffer().apply { write(byteArrayOf(1, 2, 3)) })
    stream.deliverBody(Buffer().apply { write(byteArrayOf(4, 5)) })
    stream.deliverEnd()

    val source = QuicBodySourceForTests(stream)
    val buf = Buffer()
    var total = 0L
    while (true) {
      val read = source.read(buf, Long.MAX_VALUE)
      if (read == -1L) break
      total += read
    }
    assertThat(total).isEqualTo(5L)
    assertThat(buf.readByteArray().toList()).isEqualTo(listOf<Byte>(1, 2, 3, 4, 5))
  }

  @Test fun `source read translates BodyEvent_Error into IOException`() {
    val stream = newStream()
    stream.deliverFailure(IOException("boom"))

    val source = QuicBodySourceForTests(stream)
    val thrown =
      try {
        source.read(Buffer(), 16)
        null
      } catch (e: IOException) {
        e
      }
    assertThat(thrown!!).messageContains("boom")
  }

  @Test fun `source read honours the Timeout when no bytes arrive`() {
    val stream = newStream()
    val source = QuicBodySourceForTests(stream, readTimeoutMillis = 50)
    val start = System.nanoTime()
    val thrown =
      try {
        source.read(Buffer(), 16)
        null
      } catch (e: IOException) {
        e
      }
    val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    // Must surface a timeout, not hang. The 250ms ceiling is generous — a real bug here
    // would be a full-hang (hitting the junit timeout) or a zero-wait (no elapsed time).
    assertThat(thrown!!).messageContains("timeout")
    assertThat(elapsedMs).isGreaterThan(40L)
  }

  /**
   * Trailers in HTTP/3 arrive as a second `Headers` event on the same stream (RFC 9114
   * §4.1). First headers → `headersFuture`; second headers → `trailersFuture`. Pseudo-
   * header fields ("`:...`") must not appear in trailers and are dropped defensively.
   */
  @Test fun `trailers arrive as a second headers event and populate trailersFuture`() {
    val stream = newStream()
    stream.deliverHeaders(200, listOf("content-type" to "text/plain"))
    // Second onHeaders = trailers. The -1 status is what the wire parser produces when
    // the frame has no :status pseudo-header (i.e. trailers).
    stream.deliverHeaders(-1, listOf(":dropped" to "x", "grpc-status" to "0"))

    val headers = stream.headersFuture.get()
    assertThat(headers.status).isEqualTo(200)
    assertThat(headers.headers).isEqualTo(listOf("content-type" to "text/plain"))

    val trailers = stream.trailersFuture.get()
    assertThat(trailers["grpc-status"]).isEqualTo("0")
    // Pseudo-header fields (leading ":") are disallowed in trailers.
    assertThat(trailers[":dropped"]).isNull()
  }

  @Test fun `stream end without trailers completes trailersFuture empty`() {
    val stream = newStream()
    stream.deliverHeaders(200, listOf("content-type" to "text/plain"))
    stream.deliverEnd()
    assertThat(stream.trailersFuture.get().size).isEqualTo(0)
  }

  /**
   * Adapter giving [QuicBodySource] a set of EventListener stubs so it can be
   * instantiated in a unit test. None of the listener signals we fire are load-bearing
   * for the test logic.
   */
  private class QuicBodySourceForTests(
    stream: QuicStream,
    readTimeoutMillis: Long = 0L,
  ) {
    private val delegate =
      QuicBodySource(
        stream = stream,
        eventListener = object : okhttp3.EventListener() {},
        call =
          okhttp3.OkHttpClient()
            .newCall(okhttp3.Request.Builder().url("https://example.test/").build()),
        connectionHandle =
          QuicConnectionHandle(
            peer = java.net.InetSocketAddress("example.test", 443),
            protocol = okhttp3.Protocol.HTTP_3,
            handshake =
              okhttp3.Handshake.get(
                tlsVersion = okhttp3.TlsVersion.TLS_1_3,
                cipherSuite = okhttp3.CipherSuite.TLS_AES_128_GCM_SHA256,
                peerCertificates = emptyList(),
                localCertificates = emptyList(),
              ),
          ),
        readTimeoutMillis = readTimeoutMillis,
      )

    fun read(
      sink: Buffer,
      byteCount: Long,
    ): Long = delegate.read(sink, byteCount)
  }
}
