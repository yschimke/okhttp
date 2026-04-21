/*
 * Copyright (C) 2026 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package okhttp3.containers

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import java.nio.file.Files
import java.nio.file.Path
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.quiche4j.Http3Preference
import okhttp3.quiche4j.Quiche4jInterceptor
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

/**
 * Live end-to-end HTTP/3 fetch against Caddy 2.x in a Docker container. Proves the quiche4j
 * transport works against a real QUIC server we control — body payload, cert chain, and
 * protocol negotiation — without depending on an external service.
 *
 * Requires Docker. Runs with `-PcontainerTests=true`.
 *
 * The container uses `network_mode=host` so UDP port 8443 is reachable at localhost:8443 on
 * the test host — testcontainers' default port mapping doesn't cover UDP, and H/3 needs UDP
 * to work. That restricts this test to Linux hosts; on macOS / Windows CI, skip or replace.
 */
class CaddyQuiche4jTest {
  companion object {
    private const val PORT = 8443
    private lateinit var tempDir: Path
    private lateinit var cert: HeldCertificate
    private lateinit var handshakeCertificates: HandshakeCertificates
    private lateinit var caddy: GenericContainer<*>

    @BeforeAll
    @JvmStatic
    fun start() {
      tempDir = Files.createTempDirectory("caddy-h3")
      cert =
        HeldCertificate
          .Builder()
          .addSubjectAlternativeName("localhost")
          .addSubjectAlternativeName("127.0.0.1")
          .commonName("localhost")
          .build()
      val certPem = tempDir.resolve("server.crt").toFile().apply { writeText(cert.certificatePem()) }
      val keyPem = tempDir.resolve("server.key").toFile().apply { writeText(cert.privateKeyPkcs8Pem()) }
      val caddyfile =
        tempDir.resolve("Caddyfile").toFile().apply {
          writeText(
            """
            {
              auto_https off
              admin off
            }

            :$PORT {
              tls /etc/caddy/server.crt /etc/caddy/server.key
              respond "hello from caddy h3" 200
            }
            """.trimIndent(),
          )
        }

      handshakeCertificates =
        HandshakeCertificates
          .Builder()
          .addTrustedCertificate(cert.certificate)
          .build()

      caddy =
        GenericContainer(DockerImageName.parse("caddy:2"))
          .withNetworkMode("host")
          .withFileSystemBind(certPem.absolutePath, "/etc/caddy/server.crt", BindMode.READ_ONLY)
          .withFileSystemBind(keyPem.absolutePath, "/etc/caddy/server.key", BindMode.READ_ONLY)
          .withFileSystemBind(caddyfile.absolutePath, "/etc/caddy/Caddyfile", BindMode.READ_ONLY)
          .waitingFor(Wait.forLogMessage(".*serving initial configuration.*\n", 1))
      caddy.start()
    }

    @AfterAll
    @JvmStatic
    fun stop() {
      caddy.stop()
      tempDir.toFile().deleteRecursively()
    }
  }

  @Test fun h3FetchAgainstCaddy() {
    val interceptor = Quiche4jInterceptor.Builder().build()
    val client =
      OkHttpClient
        .Builder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).addInterceptor(interceptor)
        .build()
    val request =
      Request
        .Builder()
        .url("https://localhost:$PORT/")
        .tag(Http3Preference::class.java, Http3Preference.Force())
        .build()
    client.newCall(request).execute().use { response ->
      assertThat(response.protocol).isEqualTo(Protocol.HTTP_3)
      assertThat(response.code).isEqualTo(200)
      val body = response.body.string()
      assertThat(body).contains("hello from caddy h3")
      val handshake = checkNotNull(response.handshake)
      assertThat(handshake.peerCertificates).isNotEmpty()
    }
  }

  /**
   * Fire a large number of concurrent requests at the same origin. Confirms:
   *  * the pool still produces exactly one `QuicPooledConnection` (all calls share it),
   *  * the I/O thread handles concurrent stream openings without dropping events,
   *  * we aren't leaking threads or deadlocking under thread contention (`newCachedThreadPool`
   *    would silently scale to N threads — the default pool is bounded to a handful).
   *
   * Tunable via `-Pquiche4jConcurrentRequests=N`; defaults to 50.
   */
  @Test fun concurrentFetchesShareASinglePooledConnection() {
    val n = (System.getProperty("quiche4jConcurrentRequests"))?.toIntOrNull() ?: 50
    val interceptor = Quiche4jInterceptor.Builder().build()
    val client =
      OkHttpClient
        .Builder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).addInterceptor(interceptor)
        .build()
    val executor = java.util.concurrent.Executors.newFixedThreadPool(16) { r ->
      Thread(r, "quiche4j-concurrent-test").apply { isDaemon = true }
    }
    val failures = java.util.concurrent.atomic.AtomicInteger(0)
    val successes = java.util.concurrent.atomic.AtomicInteger(0)
    val latch = java.util.concurrent.CountDownLatch(n)
    try {
      repeat(n) {
        executor.submit {
          try {
            val req =
              Request
                .Builder()
                .url("https://localhost:$PORT/")
                .tag(Http3Preference::class.java, Http3Preference.Force())
                .build()
            client.newCall(req).execute().use { resp ->
              if (resp.protocol == Protocol.HTTP_3 && resp.code == 200) {
                resp.body.string() // drain
                successes.incrementAndGet()
              } else {
                failures.incrementAndGet()
              }
            }
          } catch (_: Throwable) {
            failures.incrementAndGet()
          } finally {
            latch.countDown()
          }
        }
      }
      check(latch.await(60, java.util.concurrent.TimeUnit.SECONDS)) {
        "concurrent fetches didn't finish in 60s: succeeded=${successes.get()} failed=${failures.get()}"
      }
    } finally {
      executor.shutdownNow()
    }
    assertThat(failures.get()).isEqualTo(0)
    assertThat(successes.get()).isEqualTo(n)
    // The whole point: a burst of concurrent requests to one origin does not spawn multiple
    // QuicPooledConnections. The pool's lock around (host, port) acquisition serialises
    // handshakes; once the first connection is established every other call reuses it.
    assertThat(interceptor.pooledConnectionCount).isEqualTo(1)
  }
}
