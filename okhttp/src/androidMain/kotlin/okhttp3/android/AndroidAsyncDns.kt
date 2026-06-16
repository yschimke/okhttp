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
package okhttp3.android

import android.annotation.SuppressLint
import android.net.DnsResolver
import android.net.dns.HttpsEndpoint
import android.os.CancellationSignal
import android.os.HandlerThread
import androidx.annotation.RequiresApi
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.AsyncDns
import okhttp3.DnsResult
import okhttp3.internal.SuppressSignatureCheck
import okhttp3.internal.platform.PlatformRegistry
import okio.ByteString.Companion.toByteString

/**
 * An [AsyncDns] backed by Android's [DnsResolver].
 *
 * A single resolution issues three independent queries: `A` and `AAAA` for the host's authoritative
 * IP addresses, and an HTTPS/SVCB (type 65) query for the service record carrying Encrypted Client
 * Hello (ECH) configuration. Each query's answer is delivered as its own [DnsResult] batch; the last
 * one to complete is reported with `hasMore = false`.
 *
 * Available on Android 16 (API 36) and newer; ECH application additionally requires API 37.
 */
@Suppress("NewApi")
@RequiresApi(36)
@SuppressSignatureCheck
internal class AndroidAsyncDns
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
    override fun newCall(
      hostname: String,
      addressesOnly: Boolean,
    ): AsyncDns.DnsCall = AndroidDnsCall(hostname, addressesOnly)

    private inner class AndroidDnsCall(
      override val hostname: String,
      private val addressesOnly: Boolean,
    ) : AsyncDns.DnsCall {
      private val cancellationSignal = CancellationSignal()

      override fun enqueue(callback: AsyncDns.DnsCallback) {
        // A, AAAA and (unless addresses-only) HTTPS resolve independently; the last to finish
        // reports hasMore = false.
        val remaining = AtomicInteger(if (addressesOnly) 2 else 3)
        queryAddresses(DnsResolver.TYPE_A, callback, remaining)
        queryAddresses(DnsResolver.TYPE_AAAA, callback, remaining)
        if (!addressesOnly) queryHttps(callback, remaining)
      }

      override fun cancel() {
        cancellationSignal.cancel()
      }

      private fun queryAddresses(
        type: Int,
        callback: AsyncDns.DnsCallback,
        remaining: AtomicInteger,
      ) {
        val call = this
        try {
          dnsResolver.query(
            null,
            hostname,
            type,
            DnsResolver.FLAG_EMPTY,
            executor,
            cancellationSignal,
            object : DnsResolver.Callback<List<InetAddress>> {
              override fun onAnswer(
                answer: List<InetAddress>,
                rcode: Int,
              ) {
                callback.onResults(call, answer.map { DnsResult.Address(it) }, remaining.last())
              }

              override fun onError(e: DnsResolver.DnsException) {
                callback.onFailure(call, e.toUnknownHostException(hostname), remaining.last())
              }
            },
          )
        } catch (e: Exception) {
          callback.onFailure(call, hostname.toUnknownHostException(e), remaining.last())
        }
      }

      private fun queryHttps(
        callback: AsyncDns.DnsCallback,
        remaining: AtomicInteger,
      ) {
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
                callback.onResults(call, answer.toHttpsServices(), remaining.last())
              }

              override fun onError(e: DnsResolver.DnsException) {
                // ECH is best-effort; a missing/failed HTTPS record is not a lookup failure.
                callback.onResults(call, listOf(), remaining.last())
              }
            },
          )
        } catch (e: Exception) {
          callback.onResults(call, listOf(), remaining.last())
        }
      }
    }
  }

/** Decrements the outstanding-query counter and returns whether further batches will follow. */
private fun AtomicInteger.last(): Boolean = decrementAndGet() > 0

@SuppressLint("NewApi")
private fun HttpsEndpoint.toHttpsServices(): List<DnsResult.HttpsService> =
  httpsRecords.map { record ->
    val ech =
      try {
        record.echConfigList?.toBytes()?.toByteString()
      } catch (e: IllegalArgumentException) {
        // The platform can throw on a malformed or absent ECH parameter.
        // https://issuetracker.google.com/issues/319957694
        null
      }
    DnsResult.HttpsService(ech = ech)
  }

@SuppressLint("NewApi")
private fun DnsResolver.DnsException.toUnknownHostException(hostname: String): UnknownHostException =
  UnknownHostException("DNS lookup failed for $hostname").apply {
    initCause(this@toUnknownHostException)
  }

private fun String.toUnknownHostException(cause: Throwable): UnknownHostException =
  UnknownHostException("DNS lookup failed for $this").apply {
    initCause(cause)
  }
