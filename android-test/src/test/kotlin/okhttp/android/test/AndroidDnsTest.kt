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
@file:OptIn(OkHttpInternalApi::class)

package okhttp.android.test

import android.net.DnsResolver
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Executor
import okhttp3.Dns
import okhttp3.Protocol
import okhttp3.android.AndroidDns
import okhttp3.dnsoverhttps.internal.DnsMessage
import okhttp3.dnsoverhttps.internal.DnsMessageWriter
import okhttp3.dnsoverhttps.internal.ResourceRecord
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.dns.execute
import okio.Buffer
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

/**
 * Drives [AndroidDns] against a shadowed [DnsResolver]. Address lookups go through the platform's
 * typed `query` API, so those are answered with [InetAddress]es directly; the `HTTPS` lookup goes
 * through `rawQuery`, so that one is answered with DNS wire bytes built by [DnsMessageWriter].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [ShadowDnsResolver::class])
class AndroidDnsTest {
  private val host = "example.com"
  private val ipv4 = InetAddress.getByAddress("5db8d822".decodeHex().toByteArray())
  private val ipv6 =
    InetAddress.getByAddress(
      "20010db8000000000000000000000001".decodeHex().toByteArray(),
    )
  private val echConfigList = "this is an encrypted client hello".encodeUtf8()

  private val resolver = DnsResolver.getInstance()
  private val shadow = Shadow.extract<ShadowDnsResolver>(resolver)
  private val dns = AndroidDns(dnsResolver = resolver, executor = Executor { it.run() })

  @Test
  fun resolvesIpv4AndIpv6() {
    shadow.responder = { request ->
      when (request.nsType) {
        DnsResolver.TYPE_A -> request.callback.onAnswer(listOf(ipv4), 0)
        else -> request.callback.onAnswer(listOf(ipv6), 0)
      }
    }
    shadow.rawResponder = { it.callback.onAnswer(response(), 0) }

    val records = dns.newCall(Dns.Request(host)).execute()

    assertThat(records.filterIsInstance<Dns.Record.IpAddress>().map { it.address })
      .containsExactly(ipv4, ipv6)
  }

  @Test
  fun httpsRecordCarriesEchAlpnAndPort() {
    shadow.responder = { request ->
      when (request.nsType) {
        DnsResolver.TYPE_A -> request.callback.onAnswer(listOf(ipv4), 0)
        else -> request.callback.onAnswer(listOf(), 0)
      }
    }
    shadow.rawResponder = { request ->
      request.callback.onAnswer(
        response(
          ResourceRecord.Https(
            name = host,
            timeToLive = 5,
            alpnIds = listOf("h2"),
            port = 8443,
            echConfigList = echConfigList,
          ),
        ),
        0,
      )
    }

    val records = dns.newCall(Dns.Request(host)).execute()

    val serviceMetadata = records.filterIsInstance<Dns.Record.ServiceMetadata>().single()
    assertThat(serviceMetadata.hostname).isEqualTo(host)
    assertThat(serviceMetadata.echConfigList).isEqualTo(echConfigList)
    assertThat(serviceMetadata.port).isEqualTo(8443)
    assertThat(serviceMetadata.alpnIds).isNotNull().containsExactly(Protocol.HTTP_2)
  }

  @Test
  fun includeServiceMetadataFalseSkipsHttpsQuery() {
    val queriedTypes = mutableListOf<Int>()
    var rawQueries = 0
    shadow.responder = { request ->
      queriedTypes += request.nsType
      when (request.nsType) {
        DnsResolver.TYPE_A -> request.callback.onAnswer(listOf(ipv4), 0)
        else -> request.callback.onAnswer(listOf(), 0)
      }
    }
    shadow.rawResponder = { rawQueries++ }

    val addressesOnly =
      AndroidDns(
        dnsResolver = resolver,
        includeServiceMetadata = false,
        executor = Executor { it.run() },
      )
    val records = addressesOnly.newCall(Dns.Request(host)).execute()

    assertThat(queriedTypes).containsExactly(DnsResolver.TYPE_A, DnsResolver.TYPE_AAAA)
    assertThat(rawQueries).isEqualTo(0)
    assertThat(records.filterIsInstance<Dns.Record.ServiceMetadata>()).isEmpty()
    assertThat(records.filterIsInstance<Dns.Record.IpAddress>().map { it.address })
      .containsExactly(ipv4)
  }

  @Test
  fun oneAddressFamilyFailingIsNotFatal() {
    shadow.responder = { request ->
      when (request.nsType) {
        // NXDOMAIN for A, but AAAA resolves.
        DnsResolver.TYPE_A -> request.callback.onAnswer(listOf(), 3)
        else -> request.callback.onAnswer(listOf(ipv6), 0)
      }
    }
    shadow.rawResponder = { it.callback.onAnswer(response(), 0) }

    val records = dns.newCall(Dns.Request(host)).execute()

    assertThat(records.filterIsInstance<Dns.Record.IpAddress>().map { it.address })
      .containsExactly(ipv6)
  }

  @Test
  fun bothAddressFamiliesFailing() {
    shadow.responder = { it.callback.onAnswer(listOf(), 3) } // NXDOMAIN for A and AAAA.
    shadow.rawResponder = { it.callback.onAnswer(response(), 0) }

    assertFailure {
      dns.newCall(Dns.Request(host)).execute()
    }.isInstanceOf(UnknownHostException::class)
  }

  @Test
  fun malformedHttpsRecordIsNotFatal() {
    shadow.responder = { request ->
      when (request.nsType) {
        DnsResolver.TYPE_A -> request.callback.onAnswer(listOf(ipv4), 0)
        else -> request.callback.onAnswer(listOf(), 0)
      }
    }
    shadow.rawResponder = { it.callback.onAnswer(byteArrayOf(1, 2, 3), 0) }

    val records = dns.newCall(Dns.Request(host)).execute()

    assertThat(records.filterIsInstance<Dns.Record.ServiceMetadata>()).isEmpty()
    assertThat(records.filterIsInstance<Dns.Record.IpAddress>().map { it.address })
      .containsExactly(ipv4)
  }

  @Test
  fun cancelNotifiesCallback() {
    // Responders that never answer, leaving the call in flight.
    shadow.responder = { }
    shadow.rawResponder = { }

    val call = dns.newCall(Dns.Request(host))
    val failures = mutableListOf<IOException>()
    call.enqueue(
      object : Dns.Callback {
        override fun onRecords(
          call: Dns.Call,
          last: Boolean,
          records: List<Dns.Record>,
        ) = error("unexpected records")

        override fun onFailure(
          call: Dns.Call,
          e: IOException,
        ) {
          failures += e
        }
      },
    )

    call.cancel()

    assertThat(failures).hasSize(1)
    assertThat(call.isCanceled()).isTrue()
  }

  /** Serializes [answers] into a DNS response message (QR=1, RD=1, RA=1, rcode 0). */
  private fun response(vararg answers: ResourceRecord): ByteArray {
    val buffer = Buffer()
    DnsMessageWriter(buffer).write(
      DnsMessage(
        id = 0,
        flags = 0b1___0000__0__0__1__1_000__0000,
        questions = listOf(),
        answers = answers.toList(),
      ),
    )
    return buffer.readByteArray()
  }
}
