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
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Shared state for the HTTP/3 transport: configuration defaults, and in the future a shared
 * [java.nio.channels.DatagramChannel] + I/O selector thread + [QUIC][io.quiche4j.Connection]
 * connection pool.
 *
 * Stage 1 POC: no pooling. Each call builds a fresh QUIC connection. The engine is just a bag of
 * configuration at this point. See `PLAN.md` for the M3 migration plan to a true shared engine.
 */
internal class Quiche4jEngine(
  val trustedCaPemFile: String?,
  val trustedCaDirectory: String?,
  val allowInsecure: Boolean,
  val userAgent: String,
) {
  private val pool = ConcurrentHashMap<PoolKey, QuicPooledConnection>()
  private val poolLock = ReentrantLock()

  /**
   * Returns a QUIC connection usable for [host]/[port]. Reuses a pooled connection if one is
   * alive; otherwise handshakes a new one.
   *
   * Called on the caller thread; blocks for the duration of the QUIC handshake on first use.
   */
  fun acquire(
    host: String,
    port: Int,
    peer: InetSocketAddress,
    handshakeTimeoutMillis: Long,
    maxIdleTimeoutMillis: Long,
  ): QuicPooledConnection {
    val key = PoolKey(host, port)
    poolLock.withLock {
      val existing = pool[key]
      if (existing != null && !existing.closed) return existing
      if (existing != null && existing.closed) pool.remove(key, existing)
      val fresh =
        QuicPooledConnection.connect(key, peer, this, handshakeTimeoutMillis, maxIdleTimeoutMillis)
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

  fun newConfig(maxIdleTimeoutMillis: Long): io.quiche4j.Config {
    val builder =
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
        .withVerifyPeer(!allowInsecure)

    if (!allowInsecure) {
      when {
        trustedCaPemFile != null -> builder.loadVerifyLocationsFromFile(trustedCaPemFile)
        trustedCaDirectory != null -> builder.loadVerifyLocationsFromDirectory(trustedCaDirectory)
        else -> {
          // Best-effort: probe well-known OS locations so most Linux/macOS installs Just Work.
          val sysFile = defaultSystemCaBundleFile()
          if (sysFile != null) {
            builder.loadVerifyLocationsFromFile(sysFile)
          } else {
            val sysDir = defaultSystemCaDirectory()
            if (sysDir != null) builder.loadVerifyLocationsFromDirectory(sysDir)
          }
        }
      }
    }

    return builder.build()
  }

  companion object {
    const val MAX_DATAGRAM_SIZE: Int = 1350

    private val CA_BUNDLE_CANDIDATES =
      listOf(
        "/etc/ssl/certs/ca-certificates.crt", // Debian/Ubuntu/Arch
        "/etc/pki/tls/certs/ca-bundle.crt", // Fedora/RHEL
        "/etc/ssl/cert.pem", // Alpine, macOS (via openssl)
        "/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem", // RHEL modern
      )

    private val CA_DIR_CANDIDATES =
      listOf(
        "/etc/ssl/certs",
        "/etc/pki/tls/certs",
      )

    private fun defaultSystemCaBundleFile(): String? =
      CA_BUNDLE_CANDIDATES.firstOrNull { File(it).isFile }

    private fun defaultSystemCaDirectory(): String? =
      CA_DIR_CANDIDATES.firstOrNull { File(it).isDirectory }
  }
}
