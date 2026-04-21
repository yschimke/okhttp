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
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import java.net.InetSocketAddress
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.TlsVersion
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class QuicConnectionHandleTest {
  private val peer = InetSocketAddress("cloudflare-quic.com", 443)
  private val handshake =
    Handshake.get(
      tlsVersion = TlsVersion.TLS_1_3,
      cipherSuite = okhttp3.CipherSuite.TLS_AES_128_GCM_SHA256,
      peerCertificates = emptyList(),
      localCertificates = emptyList(),
    )
  private val handle = QuicConnectionHandle(peer, Protocol.HTTP_3, handshake)

  @Test fun `socket operations throw with a descriptive message`() {
    val socket = handle.socket()
    // Spot-check the operations an observability / debugging caller is most likely to try.
    val inputError = assertThrows<UnsupportedOperationException> { socket.getInputStream() }
    assertThat(inputError.message ?: "").contains("QUIC")
    assertThat(inputError.message ?: "").contains("Connection.route()")

    assertThrows<UnsupportedOperationException> { socket.getOutputStream() }
    assertThrows<UnsupportedOperationException> { socket.remoteSocketAddress }
    assertThrows<UnsupportedOperationException> { socket.localSocketAddress }
    assertThrows<UnsupportedOperationException> { socket.port }
    assertThrows<UnsupportedOperationException> { socket.localPort }
    assertThrows<UnsupportedOperationException> { socket.inetAddress }
  }

  @Test fun `socket is reported as closed and not connected`() {
    val socket = handle.socket()
    assertThat(socket.isClosed).isTrue()
    assertThat(socket.isConnected).isFalse()
    assertThat(socket.isBound).isFalse()
  }

  @Test fun `socket close is a no-op and idempotent`() {
    val socket = handle.socket()
    socket.close()
    socket.close()
    // Still usable (well, not really — but close didn't throw, and the instance is shared so
    // we need repeated close()s to be safe).
    assertThat(socket.isClosed).isTrue()
  }

  @Test fun `every handle returns the same shared placeholder`() {
    val a = handle.socket()
    val b = QuicConnectionHandle(peer, Protocol.HTTP_3, handshake).socket()
    // A shared instance means zero per-call allocation and no chance of an FD leak regardless
    // of what callers do with it.
    assertThat(a === b).isTrue()
  }

  @Test fun `route returns a synthetic route pointing at the peer`() {
    val route = handle.route()
    assertThat(route.proxy.type()).isEqualTo(java.net.Proxy.Type.DIRECT)
    assertThat(route.socketAddress).isEqualTo(peer)
    assertThat(route.address.url.host).isEqualTo(peer.hostString)
    assertThat(route.address.url.port).isEqualTo(peer.port)
    // Synthetic by construction — the route's dns / socketFactory / connectionSpecs are
    // placeholders, not what the caller configured. Documented in QuicConnectionHandle kdoc.
  }
}
