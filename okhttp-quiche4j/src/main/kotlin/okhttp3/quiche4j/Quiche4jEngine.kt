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

import io.quiche4j.ConfigBuilder
import io.quiche4j.Quiche
import io.quiche4j.http3.Http3
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.withLock

/**
 * Shared state for the HTTP/3 transport: quiche configuration defaults + the pool of per-origin
 * [QuicPooledConnection]s.
 *
 * Peer verification is **not** delegated to BoringSSL inside quiche; we disable quiche's own
 * `verify_peer` and do the full chain + hostname verification in Java via the caller's
 * [X509TrustManager] and [HostnameVerifier]. That way the quiche4j transport honours the same
 * trust configuration as the `OkHttpClient` (including Android's system trust store, custom
 * pinning via a delegated TM, etc.) without us needing a quiche-specific PEM bundle.
 */
internal class Quiche4jEngine {
  private val pool = ConcurrentHashMap<PoolKey, QuicPooledConnection>()
  private val poolLock = ReentrantLock()

  /**
   * Returns a QUIC connection usable for [host]/[port]. Reuses a pooled connection if one is
   * alive; otherwise handshakes a new one and verifies the peer certificate chain against
   * [trustManager] + [hostnameVerifier] before publishing it to the pool.
   */
  fun acquire(
    host: String,
    port: Int,
    peer: InetSocketAddress,
    trustManager: X509TrustManager,
    hostnameVerifier: HostnameVerifier,
    handshakeTimeoutMillis: Long,
    maxIdleTimeoutMillis: Long,
  ): QuicPooledConnection {
    val key = PoolKey(host, port)
    poolLock.withLock {
      val existing = pool[key]
      if (existing != null && !existing.closed) return existing
      if (existing != null && existing.closed) pool.remove(key, existing)
      val fresh =
        QuicPooledConnection.connect(
          key,
          peer,
          this,
          trustManager,
          hostnameVerifier,
          handshakeTimeoutMillis,
          maxIdleTimeoutMillis,
        )
      pool[key] = fresh
      return fresh
    }
  }

  /** Returns the number of connections currently in the pool. Used by tests. */
  fun pooledConnectionCount(): Int = pool.size

  /**
   * Close all pooled connections. Intended for test cleanup; regular callers should not need
   * this — idle connections are torn down by quiche's idle-timeout.
   */
  fun close() {
    poolLock.withLock {
      pool.values.forEach { it.close() }
      pool.clear()
    }
  }

  fun newConfig(maxIdleTimeoutMillis: Long): io.quiche4j.Config =
    ConfigBuilder(Quiche.PROTOCOL_VERSION)
      .withApplicationProtos(Http3.APPLICATION_PROTOCOL)
      .withMaxIdleTimeout(maxIdleTimeoutMillis)
      .withMaxUdpPayloadSize(MAX_DATAGRAM_SIZE.toLong())
      .withInitialMaxData(10_000_000)
      .withInitialMaxStreamDataBidiLocal(1_000_000)
      .withInitialMaxStreamDataBidiRemote(1_000_000)
      .withInitialMaxStreamDataUni(1_000_000)
      .withInitialMaxStreamsBidi(100)
      .withInitialMaxStreamsUni(100)
      .withDisableActiveMigration(true)
      // Verification is done in Java post-handshake; quiche's trust stack would need a PEM
      // bundle we don't want to maintain, and it can't see Android per-app network-security
      // config or user-installed CAs.
      .withVerifyPeer(false)
      .build()

  companion object {
    const val MAX_DATAGRAM_SIZE: Int = 1350
  }
}
