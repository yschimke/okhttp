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
import assertk.assertions.containsAtLeast
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.quiche4j.Http3Preference
import okhttp3.quiche4j.Quiche4jWebSocketFactory
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

/**
 * End-to-end HTTP/3 WebSocket test. Layout:
 *
 *  * `jmalloc/echo-server` bound to `127.0.0.1:$ECHO_PORT` — plain WebSocket echo on H/1.1.
 *  * `caddy:2` bound to `127.0.0.1:$CADDY_PORT` with H/3 enabled, `reverse_proxy` to the
 *    echo server — does the H/3 extended-CONNECT ↔ H/1.1 Upgrade translation for us.
 *  * Client side: [Quiche4jWebSocketFactory] with a self-signed cert, talking to Caddy.
 *
 * Both containers use `network_mode=host` so we get UDP reachability on localhost
 * (testcontainers' default port forwarding is TCP only). Linux-only; skip elsewhere.
 *
 * Runs with `-PcontainerTests=true`.
 *
 * **Currently @Disabled**: Caddy's HTTP/3 server supports regular H/3 requests but, as of
 * writing, does not appear to translate RFC 9220 extended CONNECT (`:method=CONNECT`,
 * `:protocol=websocket`) into an H/1.1 Upgrade on the reverse_proxy side. The client
 * completes the H/3 handshake, the factory returns a WebSocket, and onClosed fires
 * without any message ever being echoed back. Re-enable when Caddy (or an alternative
 * H/3 WebSocket echo server like aioquic's demo) supports the full pipeline. The test
 * shape is kept so the harness exists for that day.
 */
@Disabled(
  "Needs an H/3 WebSocket-capable server. Caddy 2 doesn't translate extended CONNECT " +
    "to backend H/1.1 Upgrade yet; leaving the scaffolding for when the infra matures.",
)
class CaddyQuiche4jWebSocketTest {
  companion object {
    private const val CADDY_PORT = 8444
    private const val ECHO_PORT = 18081
    private lateinit var tempDir: Path
    private lateinit var cert: HeldCertificate
    private lateinit var handshakeCertificates: HandshakeCertificates
    private lateinit var caddy: GenericContainer<*>
    private lateinit var echo: GenericContainer<*>

    @BeforeAll
    @JvmStatic
    fun start() {
      tempDir = Files.createTempDirectory("caddy-h3-ws")
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

            :$CADDY_PORT {
              tls /etc/caddy/server.crt /etc/caddy/server.key
              reverse_proxy 127.0.0.1:$ECHO_PORT
            }
            """.trimIndent(),
          )
        }

      handshakeCertificates =
        HandshakeCertificates
          .Builder()
          .addTrustedCertificate(cert.certificate)
          .build()

      echo =
        GenericContainer(DockerImageName.parse("jmalloc/echo-server:latest"))
          .withNetworkMode("host")
          .withEnv("PORT", ECHO_PORT.toString())
          .waitingFor(Wait.forLogMessage(".*(Echo server listening|listening).*", 1))
      echo.start()

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
      echo.stop()
      tempDir.toFile().deleteRecursively()
    }
  }

  @Test fun wsFetchAgainstCaddy() {
    val client =
      OkHttpClient
        .Builder()
        .sslSocketFactory(
          handshakeCertificates.sslSocketFactory(),
          handshakeCertificates.trustManager,
        ).build()
    val factory = Quiche4jWebSocketFactory.Builder(client).build()

    val received = CopyOnWriteArrayList<String>()
    val opened = CountDownLatch(1)
    val closed = CountDownLatch(1)
    val failures = CopyOnWriteArrayList<Throwable>()
    val listener =
      object : WebSocketListener() {
        override fun onOpen(
          webSocket: WebSocket,
          response: Response,
        ) {
          opened.countDown()
          webSocket.send("hello h3 websocket")
          // Close after a short pause so the echo has time to bounce our message back.
        }

        override fun onMessage(
          webSocket: WebSocket,
          text: String,
        ) {
          received += text
          if (received.any { it.contains("hello h3 websocket") }) {
            webSocket.close(1000, "bye")
          }
        }

        override fun onClosed(
          webSocket: WebSocket,
          code: Int,
          reason: String,
        ) {
          closed.countDown()
        }

        override fun onFailure(
          webSocket: WebSocket,
          t: Throwable,
          response: Response?,
        ) {
          failures += t
          opened.countDown()
          closed.countDown()
        }
      }

    val request =
      Request
        .Builder()
        .url("https://localhost:$CADDY_PORT/.ws")
        // jmalloc/echo-server exposes the WS endpoint at /.ws; the root also works but
        // returns the HTTP echo instead. Force ensures the factory bypasses HTTPS-record
        // discovery (which has no entry for a self-signed localhost).
        .tag<Http3Preference>(Http3Preference.Force())
        .build()

    factory.newWebSocket(request, listener)

    assertThat(opened.await(30, TimeUnit.SECONDS)).isTrue()
    if (failures.isNotEmpty()) throw AssertionError("WebSocket failed", failures.first())
    assertThat(closed.await(30, TimeUnit.SECONDS)).isTrue()

    // jmalloc/echo-server echoes each frame with a "Message-Type=Text" ACK payload plus the
    // original content. We just need to see our payload in one of the echoed frames.
    assertThat(received.any { it.contains("hello h3 websocket") }).isTrue()
  }
}
