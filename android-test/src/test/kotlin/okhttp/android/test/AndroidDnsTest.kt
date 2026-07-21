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
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import java.io.IOException
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.Protocol
import okhttp3.android.AndroidDns
import okhttp3.dnsoverhttps.internal.DnsMessage
import okhttp3.dnsoverhttps.internal.DnsMessageWriter
import okhttp3.dnsoverhttps.internal.ResourceRecord
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.dns.execute
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

/**
 * Drives [AndroidDns] under Robolectric. IP addresses come from the real system resolver, so we
 * resolve `localhost` for a deterministic, offline answer; the `HTTPS`/ECH record is fed through a
 * shadowed [DnsResolver.rawQuery] using the same [DnsMessageWriter] the `DnsOverHttps` tests use.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [ShadowDnsResolver::class])
class AndroidDnsTest {
  private val echConfigList = "this is an encrypted client hello".encodeUtf8()

  private val resolver = DnsResolver.getInstance()
  private val shadow = Shadow.extract<ShadowDnsResolver>(resolver)

  @Test
  fun resolvesAddressesWithServiceMetadata() {
    shadow.rawResponder = { request ->
      request.callback.onAnswer(
        response(
          ResourceRecord.Https(
            name = "localhost",
            timeToLive = 5,
            alpnIds = listOf("h2"),
            port = 8443,
            echConfigList = echConfigList,
          ),
        ),
        0,
      )
    }

    val records = AndroidDns(resolver).newCall(Dns.Request("localhost")).execute()

    assertThat(records.filterIsInstance<Dns.Record.IpAddress>()).isNotEmpty()

    val serviceMetadata = records.filterIsInstance<Dns.Record.ServiceMetadata>().single()
    assertThat(serviceMetadata.hostname).isEqualTo("localhost")
    assertThat(serviceMetadata.echConfigList).isEqualTo(echConfigList)
    assertThat(serviceMetadata.port).isEqualTo(8443)
    assertThat(serviceMetadata.alpnIds).isNotNull().containsExactly(Protocol.HTTP_2)
  }

  @Test
  fun missingServiceMetadataIsNotFatal() {
    // Default responder answers empty (unparseable) bytes, i.e. no usable HTTPS record.
    val records = AndroidDns(resolver).newCall(Dns.Request("localhost")).execute()

    assertThat(records.filterIsInstance<Dns.Record.IpAddress>()).isNotEmpty()
    assertThat(records.filterIsInstance<Dns.Record.ServiceMetadata>()).isEmpty()
  }

  @Test
  fun serviceMetadataDisabledSkipsHttpsQuery() {
    var httpsQueried = false
    shadow.rawResponder = {
      httpsQueried = true
      it.callback.onAnswer(ByteArray(0), 0)
    }

    val records =
      AndroidDns(resolver, includeServiceMetadata = false)
        .newCall(Dns.Request("localhost"))
        .execute()

    assertThat(records.filterIsInstance<Dns.Record.IpAddress>()).isNotEmpty()
    assertThat(httpsQueried).isFalse()
  }

  @Test
  fun unknownHostFails() {
    assertFailure {
      AndroidDns(resolver, includeServiceMetadata = false)
        .newCall(Dns.Request("host.invalid"))
        .execute()
    }.isInstanceOf(UnknownHostException::class)
  }

  @Test
  fun cancelNotifiesCallback() {
    // A responder that never answers, so the call stays in flight until canceled.
    shadow.rawResponder = { }

    val call = AndroidDns(resolver).newCall(Dns.Request("localhost"))
    val failures = mutableListOf<IOException>()
    call.enqueue(
      object : Dns.Callback {
        override fun onRecords(
          call: Dns.Call,
          last: Boolean,
          records: List<Dns.Record>,
        ) = Unit

        override fun onFailure(
          call: Dns.Call,
          e: IOException,
        ) {
          synchronized(failures) { failures += e }
        }
      },
    )

    call.cancel()

    assertThat(synchronized(failures) { failures.toList() }).hasSize(1)
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
