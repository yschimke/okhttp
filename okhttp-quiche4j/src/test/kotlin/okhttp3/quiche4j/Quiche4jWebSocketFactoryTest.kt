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
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import assertk.assertions.messageContains
import assertk.fail
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.jupiter.api.Test

class Quiche4jWebSocketFactoryTest {
  @Test fun `plaintext ws is rejected by the H3-only factory`() {
    val factory = Quiche4jWebSocketFactory.Builder().client(OkHttpClient()).build()
    val request = Request.Builder().url("http://example.test/chat").build()
    try {
      factory.newWebSocket(request, NoopListener)
      fail("expected IllegalArgumentException")
    } catch (e: IllegalArgumentException) {
      assertThat(e).messageContains("wss://")
    }
  }

  /**
   * Returning quickly is the point of the async contract. Driving the handshake with a
   * single-threaded executor the test controls lets us observe the pre-connect state
   * without racing against a real network dial.
   */
  @Test fun `newWebSocket returns before the handshake runs`() {
    val latchExec = CapturingExecutor()
    val factory =
      Quiche4jWebSocketFactory
        .Builder()
        .client(OkHttpClient())
        .connectExecutor(latchExec)
        .build()
    val listenerRecord = RecordingListener()
    val request = Request.Builder().url("https://10.255.255.1:1/chat").build()
    val ws = factory.newWebSocket(request, listenerRecord)
    // Handshake task captured but not yet executed — no onOpen / onFailure yet.
    assertThat(listenerRecord.openedCount.get()).isEqualTo(0)
    assertThat(listenerRecord.failureCount.get()).isEqualTo(0)

    // Pre-connect: send() must queue and return true, never block.
    assertThat(ws.send("hi")).isTrue()
    assertThat(ws.queueSize()).isEqualTo(2L) // approx — matches RealWebSocket's measure
  }

  @Test fun `Http3Preference ForceOff produces NoHttp3Route before touching the engine`() {
    val inline = Executor { it.run() }
    val factory =
      Quiche4jWebSocketFactory
        .Builder()
        .client(OkHttpClient())
        .connectExecutor(inline)
        .build()
    val listener = RecordingListener()
    val request =
      Request
        .Builder()
        .url("https://10.255.255.1:1/chat")
        .tag<Http3Preference>(Http3Preference.ForceOff)
        .build()
    factory.newWebSocket(request, listener)
    assertThat(listener.failureCount.get()).isEqualTo(1)
    assertThat(listener.lastFailure.get()).isNotNull()
    // Must be the specific "don't bother retrying" subtype so FailoverWebSocketFactory can
    // distinguish it from a real handshake failure if they're composed.
    val cause = listener.lastFailure.get()!!
    check(cause is NoHttp3Route) { "expected NoHttp3Route, got ${cause.javaClass.name}: $cause" }
  }

  @Test fun `discovery without h3 advertisement surfaces NoHttp3Route`() {
    val inline = Executor { it.run() }
    // An explicit HTTPS resolver that returns "no h3" is the cheap way to exercise the
    // discovery-respecting branch without needing real DNS.
    val resolverWithoutH3 =
      object : HttpsServiceRecordResolver {
        override fun lookup(hostname: String): List<HttpsServiceRecord> =
          listOf(
            HttpsServiceRecord(
              priority = 1,
              targetName = "",
              port = null,
              alpnIds = listOf("h2"),
              ipAddressHints = emptyList(),
              echConfigList = null,
            ),
          )
      }
    val factory =
      Quiche4jWebSocketFactory
        .Builder()
        .client(OkHttpClient())
        .connectExecutor(inline)
        .httpsServiceRecordResolver(resolverWithoutH3)
        .build()
    val listener = RecordingListener()
    val request = Request.Builder().url("https://10.255.255.1:1/chat").build()
    factory.newWebSocket(request, listener)
    assertThat(listener.failureCount.get()).isEqualTo(1)
    val cause = listener.lastFailure.get()!!
    check(cause is NoHttp3Route) { "expected NoHttp3Route, got ${cause.javaClass.name}: $cause" }
  }

  @Test fun `Force bypasses HTTPS-record discovery that would otherwise say no`() {
    val inline = Executor { it.run() }
    val resolverWithoutH3 =
      object : HttpsServiceRecordResolver {
        override fun lookup(hostname: String): List<HttpsServiceRecord> =
          listOf(
            HttpsServiceRecord(
              priority = 1,
              targetName = "",
              port = null,
              alpnIds = listOf("h2"),
              ipAddressHints = emptyList(),
              echConfigList = null,
            ),
          )
      }
    val factory =
      Quiche4jWebSocketFactory
        .Builder()
        .client(OkHttpClient())
        .connectExecutor(inline)
        .httpsServiceRecordResolver(resolverWithoutH3)
        .build()
    val listener = RecordingListener()
    // Force means "try anyway". The handshake will still fail (no real server), so we get
    // a failure — but not NoHttp3Route. That proves discovery was bypassed.
    val request =
      Request
        .Builder()
        .url("https://10.255.255.1:1/chat")
        .tag<Http3Preference>(Http3Preference.Force())
        .build()
    factory.newWebSocket(request, listener)
    assertThat(listener.failureCount.get()).isEqualTo(1)
    val cause = listener.lastFailure.get()!!
    check(cause !is NoHttp3Route) {
      "Force should bypass discovery, but got NoHttp3Route: $cause"
    }
  }

  @Test fun `connect failure fires onFailure without onOpen`() {
    // A URL that won't resolve so acquire() throws fast. Run the handshake on the calling
    // thread via an inline executor so the failure is observable synchronously.
    val inline = Executor { it.run() }
    val factory =
      Quiche4jWebSocketFactory
        .Builder()
        .client(OkHttpClient())
        .connectExecutor(inline)
        .build()
    val listener = RecordingListener()
    val request = Request.Builder().url("https://not-a-real-host.invalid.example/").build()
    factory.newWebSocket(request, listener)
    assertThat(listener.openedCount.get()).isEqualTo(0)
    assertThat(listener.failureCount.get()).isEqualTo(1)
  }

  @Test fun `FailoverWebSocketFactory opens on primary success`() {
    val primary = RecordingFactory(acceptConnect = true)
    val secondary = RecordingFactory(acceptConnect = true)
    val factory = FailoverWebSocketFactory(primary, secondary)

    val listener = RecordingListener()
    val ws = factory.newWebSocket(Request.Builder().url("wss://example.test/").build(), listener)
    // The primary's listener is passed by the factory; fire onOpen on it.
    primary.lastListener.get()!!.onOpen(primary.lastWebSocket.get()!!, fakeResponse())
    assertThat(listener.openedCount.get()).isEqualTo(1)
    assertThat(secondary.newCount.get()).isEqualTo(0)

    ws.send("hello")
    assertThat(primary.lastWebSocket.get()!!.sendCount.get()).isEqualTo(1)
  }

  @Test fun `FailoverWebSocketFactory fails over before onOpen`() {
    val primary = RecordingFactory(acceptConnect = true)
    val secondary = RecordingFactory(acceptConnect = true)
    val factory = FailoverWebSocketFactory(primary, secondary)

    val listener = RecordingListener()
    val ws = factory.newWebSocket(Request.Builder().url("wss://example.test/").build(), listener)
    // Primary fails before opening.
    primary.lastListener.get()!!.onFailure(
      primary.lastWebSocket.get()!!,
      java.io.IOException("boom"),
      null,
    )
    // Secondary must have been started.
    assertThat(secondary.newCount.get()).isEqualTo(1)
    // User's listener hasn't seen a failure — failover swallowed it.
    assertThat(listener.failureCount.get()).isEqualTo(0)

    // Secondary opens.
    secondary.lastListener.get()!!.onOpen(secondary.lastWebSocket.get()!!, fakeResponse())
    assertThat(listener.openedCount.get()).isEqualTo(1)

    // Messages sent BEFORE either opened should have been queued; sends after commit go
    // straight to secondary.
    ws.send("hi")
    assertThat(secondary.lastWebSocket.get()!!.sendCount.get()).isEqualTo(1)
  }

  @Test fun `FailoverWebSocketFactory post-open failure propagates`() {
    val primary = RecordingFactory(acceptConnect = true)
    val secondary = RecordingFactory(acceptConnect = true)
    val factory = FailoverWebSocketFactory(primary, secondary)

    val listener = RecordingListener()
    factory.newWebSocket(Request.Builder().url("wss://example.test/").build(), listener)
    primary.lastListener.get()!!.onOpen(primary.lastWebSocket.get()!!, fakeResponse())
    // Mid-session failure should reach the user, not trigger failover.
    primary.lastListener.get()!!.onFailure(
      primary.lastWebSocket.get()!!,
      java.io.IOException("mid-session"),
      null,
    )
    assertThat(listener.failureCount.get()).isEqualTo(1)
    assertThat(secondary.newCount.get()).isEqualTo(0)
  }

  @Test fun `FailoverWebSocketFactory drains pre-open sends to the winner`() {
    val primary = RecordingFactory(acceptConnect = true)
    val secondary = RecordingFactory(acceptConnect = true)
    val factory = FailoverWebSocketFactory(primary, secondary)

    val ws =
      factory.newWebSocket(
        Request.Builder().url("wss://example.test/").build(),
        RecordingListener(),
      )
    // Enqueue three messages before either opens.
    ws.send("a")
    ws.send("b")
    ws.send("c")
    assertThat(ws.queueSize()).isEqualTo(3L)

    primary.lastListener.get()!!.onOpen(primary.lastWebSocket.get()!!, fakeResponse())
    assertThat(primary.lastWebSocket.get()!!.sendCount.get()).isEqualTo(3)
  }

  private fun fakeResponse(): Response =
    Response
      .Builder()
      .request(Request.Builder().url("https://example.test/").build())
      .protocol(okhttp3.Protocol.HTTP_3)
      .code(200)
      .message("")
      .build()

  private class CapturingExecutor : Executor {
    val captured = AtomicReference<Runnable?>()

    override fun execute(command: Runnable) {
      captured.set(command)
    }
  }

  private class RecordingWebSocket(
    private val request: Request,
  ) : WebSocket {
    val sendCount = java.util.concurrent.atomic.AtomicInteger()
    val closeCount = java.util.concurrent.atomic.AtomicInteger()
    val cancelCount = java.util.concurrent.atomic.AtomicInteger()

    override fun request(): Request = request

    override fun queueSize(): Long = 0L

    override fun send(text: String): Boolean {
      sendCount.incrementAndGet()
      return true
    }

    override fun send(bytes: ByteString): Boolean {
      sendCount.incrementAndGet()
      return true
    }

    override fun close(
      code: Int,
      reason: String?,
    ): Boolean {
      closeCount.incrementAndGet()
      return true
    }

    override fun cancel() {
      cancelCount.incrementAndGet()
    }
  }

  private class RecordingFactory(
    @Suppress("unused") private val acceptConnect: Boolean,
  ) : WebSocket.Factory {
    val newCount = java.util.concurrent.atomic.AtomicInteger()
    val lastListener = AtomicReference<WebSocketListener?>()
    val lastWebSocket = AtomicReference<RecordingWebSocket?>()

    override fun newWebSocket(
      request: Request,
      listener: WebSocketListener,
    ): WebSocket {
      newCount.incrementAndGet()
      val ws = RecordingWebSocket(request)
      lastListener.set(listener)
      lastWebSocket.set(ws)
      return ws
    }
  }

  private class RecordingListener : WebSocketListener() {
    val openedCount = java.util.concurrent.atomic.AtomicInteger()
    val failureCount = java.util.concurrent.atomic.AtomicInteger()
    val lastFailure = AtomicReference<Throwable?>()

    override fun onOpen(
      webSocket: WebSocket,
      response: Response,
    ) {
      openedCount.incrementAndGet()
    }

    override fun onFailure(
      webSocket: WebSocket,
      t: Throwable,
      response: Response?,
    ) {
      failureCount.incrementAndGet()
      lastFailure.set(t)
    }
  }

  private object NoopListener : WebSocketListener()
}
