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

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import okhttp3.Address
import okhttp3.Connection
import okhttp3.Handshake
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.Route

/**
 * A synthetic [Connection] used only to signal `connectionAcquired`/`connectionReleased`
 * through [okhttp3.EventListener].
 *
 * This class does **not** represent a real TCP socket or the underlying QUIC transport:
 *
 *  * [socket] returns a shared closed placeholder — creating a fresh `Socket()` per call
 *    would leak an OS file descriptor per H/3 request until GC.
 *  * [route] returns a *synthetic* [Route] using `Dns.SYSTEM`, the default `SocketFactory`,
 *    etc. — **not** the values the caller configured on their `OkHttpClient`. Observability
 *    code that reads the route expecting the client's real DNS/socket-factory will see
 *    placeholders.
 *
 * Stage 2 replaces this with a real `QuicCarrier` backed by the pooled QUIC connection.
 */
internal class QuicConnectionHandle(
  private val peer: InetSocketAddress,
  private val protocol: Protocol,
  val handshake: Handshake,
) : Connection {
  override fun route(): Route {
    val dummyUrl = "https://${peer.hostString}:${peer.port}/".toHttpUrl()
    val address =
      Address(
        uriHost = dummyUrl.host,
        uriPort = dummyUrl.port,
        dns = okhttp3.Dns.SYSTEM,
        socketFactory = javax.net.SocketFactory.getDefault(),
        sslSocketFactory = null,
        hostnameVerifier = null,
        certificatePinner = null,
        proxyAuthenticator = okhttp3.Authenticator.NONE,
        proxy = null,
        protocols = listOf(protocol),
        connectionSpecs = listOf(okhttp3.ConnectionSpec.CLEARTEXT),
        proxySelector = java.net.ProxySelector.getDefault(),
      )
    return Route(address, Proxy.NO_PROXY, peer)
  }

  override fun socket(): Socket = PLACEHOLDER_SOCKET

  override fun handshake(): Handshake = handshake

  override fun protocol(): Protocol = protocol

  companion object {
    /**
     * Single shared placeholder returned by [socket]. A QUIC connection has no TCP socket;
     * instead of a closed real [Socket] (which fails with a confusing
     * `SocketException: Socket is closed`), this subclass throws a clear
     * [UnsupportedOperationException] from every accessor a caller might reasonably try.
     * Peer info lives on [route] — observability code should read from there.
     */
    private val PLACEHOLDER_SOCKET: Socket = PlaceholderSocket
  }
}

/**
 * A [Socket] that is never connected and throws with a descriptive error on every meaningful
 * operation. Shared singleton — one instance across every [QuicConnectionHandle], so there's no
 * per-call allocation and no FD can leak no matter what the caller does.
 *
 * [close] is a no-op: the instance is never opened, and EventListener implementations sometimes
 * close the returned socket reflexively after the call finishes.
 */
private object PlaceholderSocket : Socket() {
  private fun fail(op: String): Nothing =
    throw UnsupportedOperationException(
      "Socket.$op is not available on okhttp-quiche4j's Connection: this is a QUIC (HTTP/3) " +
        "connection, not a TCP socket. The Connection handle exists only so EventListener " +
        "implementations see connectionAcquired / connectionReleased. Read peer information " +
        "from Connection.route() or Connection.handshake() instead.",
    )

  override fun getInputStream(): InputStream = fail("getInputStream()")

  override fun getOutputStream(): OutputStream = fail("getOutputStream()")

  override fun getInetAddress(): InetAddress? = fail("getInetAddress()")

  override fun getLocalAddress(): InetAddress = fail("getLocalAddress()")

  override fun getPort(): Int = fail("getPort()")

  override fun getLocalPort(): Int = fail("getLocalPort()")

  override fun getRemoteSocketAddress(): SocketAddress? = fail("getRemoteSocketAddress()")

  override fun getLocalSocketAddress(): SocketAddress? = fail("getLocalSocketAddress()")

  override fun getChannel(): java.nio.channels.SocketChannel? = fail("getChannel()")

  override fun isConnected(): Boolean = false

  override fun isBound(): Boolean = false

  override fun isClosed(): Boolean = true

  override fun shutdownInput() = fail("shutdownInput()")

  override fun shutdownOutput() = fail("shutdownOutput()")

  override fun close() {
    // no-op; we were never open.
  }
}
