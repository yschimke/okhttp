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
}
