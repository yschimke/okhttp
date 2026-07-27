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
 * The protocol expectations come from
 * [RFC 9849](https://www.rfc-editor.org/rfc/rfc9849.html#section-6),
 * [RFC 9848](https://www.rfc-editor.org/rfc/rfc9848.html#section-5), and
 * [RFC 9460](https://www.rfc-editor.org/rfc/rfc9460.html#section-3). Each test below also links to
 * its public DEfO fixture. The DEfO index has no per-test anchors, so the KDoc retains its number.
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

  /**
   * 1. A minimal HTTPS record containing only an ECH config is sufficient.
   *
   * [DEfO test 1 fixture](https://min-ng.test.defo.ie/echstat.php?format=json), expected: success.
   */
  @Test
  fun minimalHttpsRecord() {
    assertNginxEchSuccess("min-ng")
  }

  /**
   * 2. Address hints alongside the ECH config don't interfere with ECH.
   *
   * Known OkHttp gap: the hints are parsed but [okhttp3.internal.connection.RouteSelector] doesn't
   * use them as candidate addresses.
   *
   * [DEfO test 2 fixture](https://v1-ng.test.defo.ie/echstat.php?format=json), expected: success.
   */
  @Test
  fun nominalHttpsRecordWithAddressHints() {
    assertNginxEchSuccess("v1-ng")
    fail("Known OkHttp gap: HTTPS-record address hints are ignored")
  }

  /**
   * 3. Explicit HTTP/1.1 and h2 ALPN values alongside address hints work.
   *
   * Known OkHttp gap: the ALPN values and address hints are parsed but
   * [okhttp3.internal.connection.RouteSelector] doesn't use them when constructing routes.
   *
   * [DEfO test 3 fixture](https://v2-ng.test.defo.ie/echstat.php?format=json), expected: success.
   */
  @Test
  fun nominalHttpsRecordWithAlpnAndAddressHints() {
    assertNginxEchSuccess("v2-ng")
    fail("Known OkHttp gap: HTTPS-record ALPN and address hints are ignored")
  }

  /**
   * 4. Of two usable service bindings, the client can select one and use its ECH config.
   *
   * Known OkHttp gap: SvcPriority is discarded and service metadata is collapsed by hostname, so
   * DNS answer order selects the binding instead of priority.
   *
   * [DEfO test 4 fixture](https://v3-ng.test.defo.ie/echstat.php?format=json), expected: success.
   */
  @Test
  fun twoUsableServiceBindings() {
    assertNginxEchSuccess("v3-ng")
    fail("Known OkHttp gap: multiple service bindings aren't retained and ordered by priority")
  }

  /**
   * 5. DNS answer ordering determines whether the selected binding is the usable X25519 record or
   * one of the records with unsupported KEM 0xcccc. A client that selects an unusable record may
   * omit ECH or send a GREASE ECH extension.
   *
   * Known OkHttp gap: this result is currently answer-order-dependent because SvcPriority is
   * discarded and only one service binding per hostname survives route construction.
   *
   * [DEfO test 5 fixture](https://v4-ng.test.defo.ie/echstat.php?format=json), expected: "error, but
   * maybe arguable". This assertion records the Android 37 outcomes rather than narrowing that
   * client-dependent result.
   */
  @Test
  fun badGoodBadServiceBindings() {
    assertNginxEchSuccessOrNotAccepted("v4-ng")
    fail("Known OkHttp gap: service bindings aren't retained and ordered by priority")
  }

  /**
   * 6. An ECH config with unsupported KEM 0xcccc isn't accepted; the client may send GREASE.
   *
   * [DEfO test 6 fixture](https://bk1-ng.test.defo.ie/echstat.php?format=json), expected: error.
   * [RFC 9849 §6.1](https://www.rfc-editor.org/rfc/rfc9849.html#section-6.1) requires a supported KEM
   * for a compatible ECHConfig and recommends GREASE when none is compatible.
   */
  @Test
  fun unsupportedKemDoesNotAcceptEch() {
    assertNginxEchNotAccepted("bk1-ng")
  }

  /**
   * 7. Conscrypt rejects a zero-length ECHConfig while starting the TLS handshake.
   *
   * [DEfO test 7 fixture](https://bk2-ng.test.defo.ie/echstat.php?format=json), expected: error.
   */
  @Test
  fun zeroLengthEchConfigFails() {
    val url = nginxUrl("bk2-ng")
    val failure =
      assertThrows<IOException> {
        client.get(url)
      }

    assertThat(failure.causesAndSuppressed().any { it is InvalidEchDataException }).isTrue()
  }

  /**
   * 8. An ECH config with unsupported version 0xcccc isn't accepted; the client may send GREASE.
   *
   * [DEfO test 8 fixture](https://bv-ng.test.defo.ie/echstat.php?format=json), expected: error.
   * [RFC 9849 §4](https://www.rfc-editor.org/rfc/rfc9849.html#section-4) requires clients to ignore
   * unsupported ECHConfig versions.
   */
  @Test
  fun unsupportedEchVersionDoesNotAcceptEch() {
    assertNginxEchNotAccepted("bv-ng")
  }

  /**
   * 9. OkHttp doesn't use HTTPS-record ALPN metadata to configure TLS, so the unusable "+" value
   * doesn't prevent ECH or OkHttp's normal HTTP/1.1 and h2 offers.
   *
   * Known OkHttp gap: RFC 9460 endpoint compatibility should account for the intersection between
   * the service binding's ALPN set and the protocols OkHttp supports.
   *
   * [DEfO test 9 fixture](https://badalpn-ng.test.defo.ie/echstat.php?format=json), expected:
   * client-dependent.
   */
  @Test
  fun unsupportedHttpsRecordAlpnDoesNotOverrideOkHttpAlpn() {
    assertNginxEchSuccess("badalpn-ng")
    fail("Known OkHttp gap: HTTPS-record ALPN isn't used for endpoint compatibility")
  }

  /**
   * 10. Address hints are not treated as A/AAAA answers, so the hostname cannot be resolved.
   *
   * Known OkHttp gap: RFC 9460 permits using address hints provisionally while A/AAAA resolution
   * continues, but OkHttp drops the hints before route construction. DEfO calls an error the
   * expected result, so implementing this standards-permitted optimization may intentionally
   * produce a different result.
   *
   * [DEfO test 10 fixture](https://noaddr-ng.test.defo.ie/echstat.php?format=json), expected: error.
   * [RFC 9460 §7.4](https://www.rfc-editor.org/rfc/rfc9460.html#section-7.4) says address hints do not
   * replace address resolution.
   */
  @Test
  fun addressHintsWithoutAddressesFailResolution() {
    val url = nginxUrl("noaddr-ng")
    assertThrows<UnknownHostException> {
      client.get(url)
    }
    fail("Known OkHttp gap: HTTPS-record address hints aren't available to route construction")
  }

  /**
   * 11. The resolver handles an HTTPS response containing twenty service bindings.
   *
   * Known OkHttp gap: the twenty bindings are parsed, but route construction collapses them to one
   * binding selected by DNS answer order.
   *
   * [DEfO test 11 fixture](https://many-ng.test.defo.ie/echstat.php?format=json), expected: success.
   */
  @Test
  fun twentyServiceBindings() {
    assertNginxEchSuccess("many-ng")
    fail("Known OkHttp gap: multiple service bindings are collapsed during route construction")
  }

  /**
   * 12. Android and OkHttp currently ignore the AliasMode record in this invalid mixed-mode RRset
   * and use the ServiceMode ECH config.
   *
   * Known OkHttp gap: AliasMode isn't represented by [okhttp3.Dns.Record.ServiceMetadata], so
   * OkHttp cannot ignore the ServiceMode records, follow the alias, or detect alias loops.
   *
   * [DEfO test 12 fixture](https://mixedmode-ng.test.defo.ie/echstat.php?format=json), expected:
   * "error, but likely ignored". This is observed Android behavior, not the normative behavior:
   * [RFC 9460 §2.4.1](https://www.rfc-editor.org/rfc/rfc9460.html#section-2.4.1) requires ServiceMode
   * records to be ignored when an AliasMode record is present.
   */
  @Test
  fun mixedAliasAndServiceModeUsesEch() {
    assertNginxEchSuccess("mixedmode-ng")
    fail("Known OkHttp gap: HTTPS AliasMode resolution isn't implemented")
  }

  /**
   * 13. Use of a P-256, HKDF-SHA384, ChaCha20 config is client-dependent. Android 37 doesn't
   * accept it and may send GREASE; a later Android version may support it.
   *
   * Known Android gap: Android 37's TLS provider doesn't select this otherwise valid ECH
   * configuration. OkHttp cannot add the missing HPKE algorithms through the SSLSocket API.
   *
   * [DEfO test 13 fixture](https://p256-ng.test.defo.ie/echstat.php?format=json), expected: success
   * but client-dependent.
   */
  @Test
  fun p256HkdfSha384AndChaCha20Poly1305IsClientDependent() {
    assertNginxEchSuccessOrNotAccepted("p256-ng")
    fail("Known Android gap: P-256/HKDF-SHA384/ChaCha20 ECH isn't supported")
  }

  /**
   * 14. X25519 succeeds and P-256 isn't accepted. With the same service priority, DNS answer
   * ordering determines which one is selected. An unusable selection may send GREASE.
   *
   * Known OkHttp gap: equal-priority bindings should both be retained and shuffled, but only the
   * last binding for the hostname survives. Android's missing P-256 support is a separate gap.
   *
   * [DEfO test 14 fixture](https://curves1-ng.test.defo.ie/echstat.php?format=json), expected:
   * success but client-dependent. [RFC 9460 §2.4.1](https://www.rfc-editor.org/rfc/rfc9460.html#section-2.4.1)
   * permits selection among records at the same priority.
   */
  @Test
  fun x25519AndP256AtSamePriority() {
    assertNginxEchSuccessOrNotAccepted("curves1-ng")
    fail("Known gaps: equal-priority bindings aren't retained, and Android lacks P-256 ECH")
  }

  /**
   * 15. The priority-1 X25519 record is preferred to the priority-2 P-256 record. The result still
   * depends on which service metadata the resolver API exposes and which configuration the client
   * supports.
   *
   * Known OkHttp gap: priority is discarded, so the later P-256 binding can replace the preferred
   * X25519 binding even though the latter is supported.
   *
   * [DEfO test 15 fixture](https://curves2-ng.test.defo.ie/echstat.php?format=json), expected:
   * success but client-dependent. [RFC 9460 §3](https://www.rfc-editor.org/rfc/rfc9460.html#section-3)
   * orders compatible ServiceMode records by ascending priority.
   */
  @Test
  fun x25519BeforeP256() {
    assertNginxEchSuccessOrNotAccepted("curves2-ng")
    fail("Known OkHttp gap: the priority-1 X25519 binding isn't reliably selected")
  }

  /**
   * 16. The priority-1 P-256 record is preferred to the priority-2 X25519 record. The result depends
   * on which service metadata the resolver API exposes and which configuration the client supports.
   *
   * Known gaps: OkHttp discards priority and Android 37 doesn't support the preferred P-256 ECH
   * configuration. A standards-compliant implementation may therefore GREASE rather than silently
   * selecting the lower-priority X25519 binding.
   *
   * [DEfO test 16 fixture](https://curves3-ng.test.defo.ie/echstat.php?format=json), expected:
   * success but client-dependent. [RFC 9460 §3](https://www.rfc-editor.org/rfc/rfc9460.html#section-3)
   * orders compatible ServiceMode records by ascending priority.
   */
  @Test
  fun p256BeforeX25519() {
    assertNginxEchSuccessOrNotAccepted("curves3-ng")
    fail("Known gaps: SvcPriority is ignored, and Android lacks P-256 ECH")
  }

  /**
   * 17. An HTTPS record advertising only h2 interoperates with OkHttp.
   *
   * Known OkHttp gap: this passes because OkHttp independently offers h2, not because it honors the
   * service binding's ALPN set.
   *
   * [DEfO test 17 fixture](https://h2alpn-ng.test.defo.ie/echstat.php?format=json), expected: success.
   */
  @Test
  fun h2OnlyAlpn() {
    assertNginxEchSuccess("h2alpn-ng")
    fail("Known OkHttp gap: HTTPS-record ALPN isn't used during route construction")
  }

  /**
   * 18. An HTTPS record advertising only HTTP/1.1 interoperates with OkHttp.
   *
   * Known OkHttp gap: this passes because OkHttp independently offers HTTP/1.1, not because it
   * honors the service binding's ALPN set.
   *
   * [DEfO test 18 fixture](https://h1alpn-ng.test.defo.ie/echstat.php?format=json), expected: success.
   */
  @Test
  fun http11OnlyAlpn() {
    assertNginxEchSuccess("h1alpn-ng")
    fail("Known OkHttp gap: HTTPS-record ALPN isn't used during route construction")
  }

  /**
   * 19. Unknown ALPN IDs mixed with HTTP/1.1 and h2 don't prevent ECH.
   *
   * Known OkHttp gap: the parser filters unknown IDs, but route construction ignores even the
   * recognized ALPN values instead of using them to determine endpoint compatibility.
   *
   * [DEfO test 19 fixture](https://mixedalpn-ng.test.defo.ie/echstat.php?format=json), expected:
   * success.
   */
  @Test
  fun mixedKnownAndUnknownAlpnIds() {
    assertNginxEchSuccess("mixedalpn-ng")
    fail("Known OkHttp gap: parsed HTTPS-record ALPN values are ignored")
  }

  /**
   * 20. A long ALPN list ending in HTTP/1.1 and h2 doesn't prevent ECH.
   *
   * Known OkHttp gap: this exercises parsing, but OkHttp doesn't use the recognized ALPN values
   * when constructing endpoint candidates.
   *
   * [DEfO test 20 fixture](https://longalpn-ng.test.defo.ie/echstat.php?format=json), expected:
   * success.
   */
  @Test
  fun longAlpnList() {
    assertNginxEchSuccess("longalpn-ng")
    fail("Known OkHttp gap: parsed HTTPS-record ALPN values are ignored")
  }

  /**
   * 21. An ECHConfigList containing X25519 followed by P-256 permits ECH.
   *
   * [DEfO test 21 fixture](https://2thenp-ng.test.defo.ie/echstat.php?format=json), expected: success.
   * [RFC 9849 §4](https://www.rfc-editor.org/rfc/rfc9849.html#section-4) defines ECHConfigList as a
   * preference-ordered sequence.
   */
  @Test
  fun x25519ThenP256InOneEchConfigList() {
    assertNginxEchSuccess("2thenp-ng")
  }

  /**
   * 22. An ECHConfigList containing P-256 followed by X25519 permits ECH.
   *
   * [DEfO test 22 fixture](https://pthen2-ng.test.defo.ie/echstat.php?format=json), expected: success.
   * [RFC 9849 §6.1](https://www.rfc-editor.org/rfc/rfc9849.html#section-6.1) has the client choose a
   * suitable ECHConfig, allowing it to skip unsupported entries.
   */
  @Test
  fun p256ThenX25519InOneEchConfigList() {
    assertNginxEchSuccess("pthen2-ng")
  }

  /**
   * 23. Unknown non-mandatory ECHConfig extensions are ignored.
   *
   * [DEfO test 23 fixture](https://withext-ng.test.defo.ie/echstat.php?format=json), expected:
   * success. [RFC 9849 §4.2](https://www.rfc-editor.org/rfc/rfc9849.html#section-4.2) distinguishes
   * ignorable extensions from unsupported mandatory extensions.
   */
  @Test
  fun unknownEchConfigExtensions() {
    assertNginxEchSuccess("withext-ng")
  }

  /**
   * 24. The baseline nginx configuration uses ECH.
   *
   * [DEfO test 24 fixture](https://ng.test.defo.ie/echstat.php?format=json), expected: success.
   */
  @Test
  fun nginxServer() {
    assertNginxEchSuccess("ng")
  }

  /**
   * 25. Apache reports a successful ECH handshake.
   *
   * [DEfO test 25 fixture](https://ap.test.defo.ie/echstat.php?format=json), expected: success.
   */
  @Test
  fun apacheServer() {
    assertThat(client.get(testUrl("ap.test.defo.ie", "echstat.php?format=json")))
      .contains("\"SSL_ECH_STATUS\": \"success\"")
  }

  /**
   * 26. lighttpd reports a successful ECH handshake.
   *
   * [DEfO test 26 fixture](https://ly.test.defo.ie/echstat.php?format=json), expected: success.
   */
  @Test
  fun lighttpdServer() {
    assertThat(client.get(testUrl("ly.test.defo.ie", "echstat.php?format=json")))
      .contains("\"SSL_ECH_STATUS\": \"SSL_ECH_STATUS_SUCCESS\"")
  }

  /**
   * 27. OpenSSL s_server reports a successful ECH handshake.
   *
   * [DEfO test 27 fixture](https://ss.test.defo.ie/stats), expected: success.
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
   * [DEfO test 28 fixture](https://sshrr.test.defo.ie/stats), expected: success.
   * [RFC 9849 §7.1.1](https://www.rfc-editor.org/rfc/rfc9849.html#section-7.1.1) specifies ECH
   * processing after HelloRetryRequest.
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

    if (port != null) {
      // Known OkHttp gap: RouteSelector drops the URL port from Dns.Request, and the DNS state
      // machine consequently queries `hostname` instead of `_$port._https.hostname`. These
      // invocations currently test only transport reachability, not the published port-specific
      // HTTPS record.
      fail("Known OkHttp gap: non-standard-port HTTPS query naming isn't implemented")
    }

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
