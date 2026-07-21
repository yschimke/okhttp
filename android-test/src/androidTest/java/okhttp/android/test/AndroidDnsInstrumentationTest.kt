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

import android.os.Build
import assertk.assertThat
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Dns
import okhttp3.android.AndroidDns
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Live, on-device tests for [AndroidDns] using Android's real `DnsResolver`. Tagged `Remote`
 * because they depend on external DNS, so they are skipped in normal CI runs.
 */
@Tag("Remote")
class AndroidDnsInstrumentationTest {
  @Test
  fun resolvesAddresses() {
    assumeTrue(Build.VERSION.SDK_INT >= 29)

    val records = AndroidDns().resolve("cloudflare.com")

    assertThat(records.filterIsInstance<Dns.Record.IpAddress>()).isNotEmpty()
  }

  @Test
  fun resolvesEchConfig() {
    assumeTrue(Build.VERSION.SDK_INT >= 29)

    val records = AndroidDns().resolve("crypto.cloudflare.com")

    val serviceMetadata = records.filterIsInstance<Dns.Record.ServiceMetadata>().firstOrNull()
    assertThat(serviceMetadata?.echConfigList).isNotNull()
  }

  /** Blocking bridge over the public async API. */
  private fun AndroidDns.resolve(hostname: String): List<Dns.Record> {
    val latch = CountDownLatch(1)
    val collected = mutableListOf<Dns.Record>()
    val failure = AtomicReference<IOException?>()

    newCall(Dns.Request(hostname)).enqueue(
      object : Dns.Callback {
        override fun onRecords(
          call: Dns.Call,
          last: Boolean,
          records: List<Dns.Record>,
        ) {
          synchronized(collected) { collected += records }
          if (last) latch.countDown()
        }

        override fun onFailure(
          call: Dns.Call,
          e: IOException,
        ) {
          failure.set(e)
          latch.countDown()
        }
      },
    )

    assertThat(latch.await(10, SECONDS)).isTrue()
    failure.get()?.let { throw it }
    return synchronized(collected) { collected.toList() }
  }
}
