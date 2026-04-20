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
 * through [okhttp3.EventListener]. Has no live TCP [Socket]; callers that call [socket] get a
 * closed placeholder. See `PLAN.md` — Stage 2 replaces this with a real `QuicCarrier`.
 */
internal class QuicConnectionHandle(
  private val peer: InetSocketAddress,
  private val protocol: Protocol,
  val handshake: Handshake,
) : Connection {
  private val placeholder = Socket()

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

  override fun socket(): Socket = placeholder

  override fun handshake(): Handshake = handshake

  override fun protocol(): Protocol = protocol
}
