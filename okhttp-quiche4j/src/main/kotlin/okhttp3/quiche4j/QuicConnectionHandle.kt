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

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
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

  override fun socket(): Socket = CLOSED_PLACEHOLDER

  override fun handshake(): Handshake = handshake

  override fun protocol(): Protocol = protocol

  companion object {
    /** Single shared, closed `Socket` handed to every [socket] caller. Avoids leaking FDs. */
    private val CLOSED_PLACEHOLDER: Socket =
      Socket().also {
        try {
          it.close()
        } catch (_: Throwable) {
          // best effort
        }
      }
  }
}
