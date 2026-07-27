/*
 * Copyright (c) 2026 OkHttp Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp.android.test

import android.annotation.SuppressLint
import android.net.ssl.InvalidEchDataException
import android.os.Build
import app.cash.burst.Burst
import app.cash.burst.burstValues
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isIn
import assertk.assertions.isTrue
import java.io.IOException
import java.net.UnknownHostException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.android.EchAwareDns
import okhttp3.dnsoverhttps.DnsOverHttps
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail

/**
 * Exercises the numbered ECH interoperability cases at https://test.defo.ie/iframe_tests.html.
 *
 * Test with both [okhttp3.android.AndroidDns] and [DnsOverHttps], on the standard port and each
 * test case's non-standard port when one is published.
 *
 * See `res/xml/network_security_config.xml` for the opportunistic ECH policy.
 */
@SuppressLint("NewApi")
@Tag("Remote")
@Burst
class DefoEchTest(
  private val useDoh: Boolean = false,
  private val port: Int? = burstValues(null, 15443, 15447, 15448),
) {
  private lateinit var client: OkHttpClient

  @BeforeEach
  fun setUp() {
    // EchAwareDns reads API 37 NetworkSecurityPolicy.getDomainEncryptionMode().
    assumeTrue(Build.VERSION.SDK_INT >= 37)

    client =
      OkHttpClient
        .Builder()
        .dns(dns())
        .build()
  }

  /** 1. A minimal HTTPS record containing only an ECH config is sufficient. */
  @Test
  fun minimalHttpsRecord() {
    assertNginxEchSuccess("min-ng")
  }

  /** 2. Address hints alongside the ECH config don't interfere with ECH. */
  @Test
  fun nominalHttpsRecordWithAddressHints() {
    assertNginxEchSuccess("v1-ng")
  }

  /** 3. Explicit HTTP/1.1 and h2 ALPN values alongside address hints work. */
  @Test
  fun nominalHttpsRecordWithAlpnAndAddressHints() {
    assertNginxEchSuccess("v2-ng")
  }

  /** 4. Of two usable service bindings, the client can select one and use its ECH config. */
  @Test
  fun twoUsableServiceBindings() {
    assertNginxEchSuccess("v3-ng")
  }

  /**
   * 5. DNS answer ordering determines whether the selected binding is the usable X25519 record or
   * one of the records with unsupported KEM 0xcccc. A client that selects an unusable record may
   * omit ECH or send a GREASE ECH extension.
   */
  @Test
  fun badGoodBadServiceBindings() {
    if (useDoh) {
      assertNginxEchNotAccepted("v4-ng")
    } else {
      assertNginxEchSuccessOrNotAccepted("v4-ng")
    }
  }

  /** 6. An ECH config with unsupported KEM 0xcccc isn't accepted; the client may send GREASE. */
  @Test
  fun unsupportedKemDoesNotAcceptEch() {
    assertNginxEchNotAccepted("bk1-ng")
  }

  /** 7. Conscrypt rejects a zero-length ECHConfig while starting the TLS handshake. */
  @Test
  fun zeroLengthEchConfigFails() {
    val url = nginxUrl("bk2-ng")
    val failure =
      assertThrows<IOException> {
        client.get(url)
      }

    assertThat(failure.causesAndSuppressed().any { it is InvalidEchDataException }).isTrue()
  }

  /** 8. An ECH config with unsupported version 0xcccc isn't accepted; the client may send GREASE. */
  @Test
  fun unsupportedEchVersionDoesNotAcceptEch() {
    assertNginxEchNotAccepted("bv-ng")
  }

  /**
   * 9. OkHttp doesn't use HTTPS-record ALPN metadata to configure TLS, so the unusable "+" value
   * doesn't prevent ECH or OkHttp's normal HTTP/1.1 and h2 offers.
   */
  @Test
  fun unsupportedHttpsRecordAlpnDoesNotOverrideOkHttpAlpn() {
    assertNginxEchSuccess("badalpn-ng")
  }

  /** 10. Address hints are not treated as A/AAAA answers, so the hostname cannot be resolved. */
  @Test
  fun addressHintsWithoutAddressesFailResolution() {
    val url = nginxUrl("noaddr-ng")
    assertThrows<UnknownHostException> {
      client.get(url)
    }
  }

  /** 11. The resolver handles an HTTPS response containing twenty service bindings. */
  @Test
  fun twentyServiceBindings() {
    assertNginxEchSuccess("many-ng")
  }

  /**
   * 12. Android and OkHttp ignore the invalid mixture of AliasMode and ServiceMode records and use
   * the ServiceMode ECH config.
   */
  @Test
  fun mixedAliasAndServiceModeUsesEch() {
    assertNginxEchSuccess("mixedmode-ng")
  }

  /**
   * 13. Android 37 doesn't accept a P-256, HKDF-SHA384, ChaCha20 config; it may send GREASE.
   */
  @Test
  fun p256HkdfSha384AndChaCha20Poly1305DoesNotAcceptEch() {
    assertNginxEchNotAccepted("p256-ng")
  }

  /**
   * 14. X25519 succeeds and P-256 isn't accepted. With the same service priority, DNS answer
   * ordering determines which one is selected. An unusable selection may send GREASE.
   */
  @Test
  fun x25519AndP256AtSamePriority() {
    if (useDoh) {
      assertNginxEchNotAccepted("curves1-ng")
    } else {
      assertNginxEchSuccessOrNotAccepted("curves1-ng")
    }
  }

  /**
   * 15. Android's resolver selects the priority-1 X25519 record. The DoH path currently retains
   * the final record for the hostname instead, which is the priority-2 P-256 record.
   */
  @Test
  fun x25519BeforeP256() {
    if (useDoh) {
      assertNginxEchNotAccepted("curves2-ng")
    } else {
      assertNginxEchSuccessOrNotAccepted("curves2-ng")
    }
  }

  /**
   * 16. X25519 succeeds and P-256 isn't accepted. DoH retains the final X25519 record, while the
   * platform resolver's answer ordering can select either record and may send GREASE.
   */
  @Test
  fun p256BeforeX25519() {
    if (useDoh) {
      assertNginxEchSuccess("curves3-ng")
    } else {
      assertNginxEchSuccessOrNotAccepted("curves3-ng")
    }
  }

  /** 17. An HTTPS record advertising only h2 interoperates with OkHttp. */
  @Test
  fun h2OnlyAlpn() {
    assertNginxEchSuccess("h2alpn-ng")
  }

  /** 18. An HTTPS record advertising only HTTP/1.1 interoperates with OkHttp. */
  @Test
  fun http11OnlyAlpn() {
    assertNginxEchSuccess("h1alpn-ng")
  }

  /** 19. Unknown ALPN IDs mixed with HTTP/1.1 and h2 don't prevent ECH. */
  @Test
  fun mixedKnownAndUnknownAlpnIds() {
    assertNginxEchSuccess("mixedalpn-ng")
  }

  /** 20. A long ALPN list ending in HTTP/1.1 and h2 doesn't prevent ECH. */
  @Test
  fun longAlpnList() {
    assertNginxEchSuccess("longalpn-ng")
  }

  /** 21. An ECHConfigList containing X25519 followed by P-256 permits ECH. */
  @Test
  fun x25519ThenP256InOneEchConfigList() {
    assertNginxEchSuccess("2thenp-ng")
  }

  /** 22. An ECHConfigList containing P-256 followed by X25519 permits ECH. */
  @Test
  fun p256ThenX25519InOneEchConfigList() {
    assertNginxEchSuccess("pthen2-ng")
  }

  /** 23. Unknown ECHConfig extensions are ignored. */
  @Test
  fun unknownEchConfigExtensions() {
    assertNginxEchSuccess("withext-ng")
  }

  /** 24. The baseline nginx configuration uses ECH. */
  @Test
  fun nginxServer() {
    assertNginxEchSuccess("ng")
  }

  /** 25. Apache reports a successful ECH handshake. */
  @Test
  fun apacheServer() {
    assertThat(client.get(testUrl("ap.test.defo.ie", "echstat.php?format=json")))
      .contains("\"SSL_ECH_STATUS\": \"success\"")
  }

  /** 26. lighttpd reports a successful ECH handshake. */
  @Test
  fun lighttpdServer() {
    assertThat(client.get(testUrl("ly.test.defo.ie", "echstat.php?format=json")))
      .contains("\"SSL_ECH_STATUS\": \"SSL_ECH_STATUS_SUCCESS\"")
  }

  /**
   * 27. OpenSSL s_server reports a successful ECH handshake.
   *
   * The direct backend is currently inaccessible: IPv6 is unroutable from the emulator, and IPv4
   * also times out from a physical device.
   */
  @Test
  fun opensslServer() {
    assumeFalse(port == 15447, "ss.test.defo.ie:15447 is currently inaccessible")
    assertThat(client.get(testUrl("ss.test.defo.ie", "stats", nonStandardPort = 15447)))
      .contains("ECH success")
  }

  /**
   * 28. ECH survives a TLS HelloRetryRequest from OpenSSL s_server.
   *
   * The direct backend is currently inaccessible: IPv6 is unroutable from the emulator, and IPv4
   * also times out from a physical device.
   */
  @Test
  fun opensslServerForcingHelloRetryRequest() {
    assumeFalse(port == 15448, "sshrr.test.defo.ie:15448 is currently inaccessible")
    assertThat(client.get(testUrl("sshrr.test.defo.ie", "stats", nonStandardPort = 15448)))
      .contains("ECH success")
  }

  private fun assertNginxEchSuccess(host: String) {
    assertNginxEchOutcome(host, "success")
  }

  private fun assertNginxEchNotAccepted(host: String) {
    assertNginxEchOutcome(host, "not attempted", "GREASEd ECH")
  }

  private fun assertNginxEchSuccessOrNotAccepted(host: String) {
    assertNginxEchOutcome(host, "success", "not attempted", "GREASEd ECH")
  }

  private fun assertNginxEchOutcome(
    host: String,
    vararg expected: String,
  ) {
    val body = client.get(nginxUrl(host))
    val status =
      ECH_STATUS_PATTERN
        .find(body)
        ?.groupValues
        ?.get(1)
        ?: fail("no SSL_ECH_STATUS in response from $host: $body")

    assertThat(status).isIn(*expected)
  }

  private fun nginxUrl(host: String): String =
    testUrl(
      hostname = "$host.test.defo.ie",
      path = "echstat.php?format=json",
      nonStandardPort = 15443,
    )

  private fun testUrl(
    hostname: String,
    path: String,
    nonStandardPort: Int? = null,
  ): String {
    assumeTrue(
      port == null || port == nonStandardPort,
      "$hostname does not publish a test URL on port $port",
    )

    val portSuffix = port?.let { ":$it" }.orEmpty()
    return "https://$hostname$portSuffix/$path"
  }

  private fun Throwable.causesAndSuppressed(): Sequence<Throwable> =
    sequence {
      yield(this@causesAndSuppressed)
      cause?.let { yieldAll(it.causesAndSuppressed()) }
      for (suppressedException in suppressed) {
        yieldAll(suppressedException.causesAndSuppressed())
      }
    }

  /**
   * [EchAwareDns] over the platform resolver, or over DoH when [useDoh]. Both arms use the same
   * source: the ECH one carries service metadata, the other doesn't.
   */
  private fun dns(): EchAwareDns =
    when {
      useDoh -> {
        val bootstrapClient = OkHttpClient()
        EchAwareDns(
          echDns = dnsOverHttps(bootstrapClient, includeServiceMetadata = true),
          addressOnlyDns = dnsOverHttps(bootstrapClient, includeServiceMetadata = false),
        )
      }
      else -> EchAwareDns()
    }

  /** Addressed by IP, so resolving the resolver doesn't need a resolver. */
  private fun dnsOverHttps(
    bootstrapClient: OkHttpClient,
    includeServiceMetadata: Boolean,
  ): DnsOverHttps =
    DnsOverHttps
      .Builder()
      .client(bootstrapClient)
      .url("https://1.1.1.1/dns-query".toHttpUrl())
      .includeServiceMetadata(includeServiceMetadata)
      .build()

  private fun OkHttpClient.get(url: String): String =
    newCall(Request.Builder().url(url).build()).execute().use { response ->
      response.body.string()
    }

  companion object {
    private val ECH_STATUS_PATTERN = """"SSL_ECH_STATUS"\s*:\s*"([^"]+)"""".toRegex()
  }
}
