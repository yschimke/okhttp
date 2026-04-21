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
import assertk.assertions.isSameInstanceAs
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.jupiter.api.Test

/**
 * Unit tests for [Quiche4jWebSocketFactory]'s routing decisions that don't need a real
 * H/3 server. The live extended-CONNECT handshake is covered (or will be) by a container
 * test when we have a WebSocket-capable H/3 server on the Caddy-or-equivalent path.
 */
class Quiche4jWebSocketFactoryTest {
  private val sentinelWs =
    object : WebSocket {
      override fun request(): Request = Request.Builder().url("https://example.test/").build()

      override fun queueSize(): Long = 0

      override fun send(text: String): Boolean = true

      override fun send(bytes: ByteString): Boolean = true

      override fun close(
        code: Int,
        reason: String?,
      ): Boolean = true

      override fun cancel() {}
    }

  /** Records the last [Request] it saw so tests can assert delegation happened. */
  private class RecordingFactory(
    private val returning: WebSocket,
  ) : WebSocket.Factory {
    val lastRequest = AtomicReference<Request>()

    override fun newWebSocket(
      request: Request,
      listener: WebSocketListener,
    ): WebSocket {
      lastRequest.set(request)
      return returning
    }
  }

  @Test fun `plaintext ws delegates to fallback without attempting H3`() {
    val fallback = RecordingFactory(sentinelWs)
    val factory =
      Quiche4jWebSocketFactory
        .Builder()
        .client(OkHttpClient())
        .fallback(fallback)
        .build()

    val request = Request.Builder().url("http://example.test/chat").build()
    val ws = factory.newWebSocket(request, NoopListener)

    assertThat(ws).isSameInstanceAs(sentinelWs)
    assertThat(fallback.lastRequest.get()).isSameInstanceAs(request)
  }

  @Test fun `Http3Preference ForceOff short-circuits to fallback`() {
    val fallback = RecordingFactory(sentinelWs)
    val factory =
      Quiche4jWebSocketFactory
        .Builder()
        .client(OkHttpClient())
        .fallback(fallback)
        .build()

    val request =
      Request
        .Builder()
        .url("wss://example.test/chat")
        .tag<Http3Preference>(Http3Preference.ForceOff)
        .build()
    val ws = factory.newWebSocket(request, NoopListener)
    assertThat(ws).isSameInstanceAs(sentinelWs)
  }

  @Test fun `builder defaults fallback to the supplied client`() {
    // No explicit fallback — an OkHttpClient is itself a WebSocket.Factory, and the builder
    // should reuse it. The test just proves the builder doesn't NPE.
    val client = OkHttpClient()
    val factory = Quiche4jWebSocketFactory.Builder().client(client).build()
    assertThat(factory.fallback).isSameInstanceAs(client)
  }

  private object NoopListener : WebSocketListener() {
    override fun onOpen(
      webSocket: WebSocket,
      response: Response,
    ) {}
  }
}
