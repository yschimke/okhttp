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
@file:OptIn(ExperimentalOkHttpApi::class)

package okhttp3.android

import android.annotation.SuppressLint
import android.net.DnsResolver
import android.net.dns.HttpsEndpoint
import android.os.CancellationSignal
import android.os.HandlerThread
import androidx.annotation.RequiresApi
import java.net.UnknownHostException
import java.util.concurrent.Executor
import okhttp3.AsyncDns
import okhttp3.DnsResult
import okhttp3.ExperimentalOkHttpApi
import okhttp3.internal.SuppressSignatureCheck
import okhttp3.internal.platform.PlatformRegistry
import okio.ByteString.Companion.toByteString

/**
 * An [AsyncDns] backed by Android's [DnsResolver].
 *
 * A single HTTPS/SVCB (type 65) query resolves both the host's IP addresses and any HTTPS service
 * records, so Encrypted Client Hello (ECH) configuration arrives alongside the addresses in one
 * [DnsResult] batch rather than through a side channel.
 *
 * Available on Android 16 (API 36) and newer; ECH application additionally requires API 37.
 */
@RequiresApi(36)
@ExperimentalOkHttpApi
@SuppressSignatureCheck
class AndroidAsyncDns
  @RequiresApi(36)
  internal constructor(
    private val dnsResolver: DnsResolver =
      HandlerThread("OkHttp AsyncDns").let { handlerThread ->
        handlerThread.start()
        DnsResolver(PlatformRegistry.applicationContext!!, handlerThread.looper)
      },
    private val executor: Executor = Executor { it.run() },
    private val timeoutMillis: Int = 5_000,
  ) : AsyncDns {
    override fun newCall(hostname: String): AsyncDns.DnsCall = AndroidDnsCall(hostname)

    private inner class AndroidDnsCall(
      override val hostname: String,
    ) : AsyncDns.DnsCall {
      private val cancellationSignal = CancellationSignal()

      @SuppressLint("NewApi")
      override fun enqueue(callback: AsyncDns.DnsCallback) {
        val call = this
        try {
          @Suppress("WrongConstant")
          dnsResolver.query(
            null,
            hostname,
            DnsResolver.FLAG_EMPTY,
            executor,
            timeoutMillis,
            cancellationSignal,
            object : DnsResolver.Callback<HttpsEndpoint> {
              override fun onAnswer(
                answer: HttpsEndpoint,
                rcode: Int,
              ) {
                callback.onResults(call, answer.toDnsResults(), hasMore = false)
              }

              override fun onError(e: DnsResolver.DnsException) {
                callback.onFailure(call, e.toUnknownHostException(hostname), hasMore = false)
              }
            },
          )
        } catch (e: Exception) {
          // DnsResolver can throw synchronously for malformed/absent parameters.
          // https://issuetracker.google.com/issues/319957694
          callback.onFailure(
            call,
            UnknownHostException("DNS lookup failed for $hostname").apply { initCause(e) },
            hasMore = false,
          )
        }
      }

      override fun cancel() {
        cancellationSignal.cancel()
      }
    }
  }

@SuppressLint("NewApi")
private fun HttpsEndpoint.toDnsResults(): List<DnsResult> {
  val results = ArrayList<DnsResult>(ipAddresses.size + httpsRecords.size)
  for (address in ipAddresses) {
    results += DnsResult.Address(address)
  }
  for (record in httpsRecords) {
    val ech =
      try {
        record.echConfigList?.toBytes()?.toByteString()
      } catch (e: IllegalArgumentException) {
        // The platform can throw on a malformed or absent ECH parameter.
        // https://issuetracker.google.com/issues/319957694
        null
      }
    results += DnsResult.HttpsService(ech = ech)
  }
  return results
}

@SuppressLint("NewApi")
private fun DnsResolver.DnsException.toUnknownHostException(hostname: String): UnknownHostException =
  UnknownHostException("DNS lookup failed for $hostname").apply {
    initCause(this@toUnknownHostException)
  }
