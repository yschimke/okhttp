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

import android.net.DnsResolver
import android.net.Network
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Executor
import okhttp3.Dns
import okhttp3.Protocol
import okhttp3.internal.SuppressSignatureCheck
import okhttp3.internal.dns.DnsMessageReader
import okhttp3.internal.dns.RESPONSE_CODE_SUCCESS
import okhttp3.internal.dns.ResourceRecord
import okhttp3.internal.dns.TYPE_HTTPS
import okhttp3.internal.dns.execute
import okio.Buffer

/**
 * A [Dns] backed by Android's [DnsResolver].
 *
 * Each resolution issues independent lookups. The `A` and `AAAA` lookups use
 * [query][DnsResolver.query], which the platform parses into [InetAddress]es for us. Unless
 * [includeServiceMetadata] is false, an `HTTPS` (type 65) lookup also runs for the RFC 9460
 * service record that carries the Encrypted Client Hello (ECH) configuration; the platform has no
 * typed API for that below API 36, so it goes through [rawQuery][DnsResolver.rawQuery] and is
 * decoded with OkHttp's own [DnsMessageReader] — the same decoder `DnsOverHttps` uses — so ALPN,
 * port, address hints and ECH are parsed uniformly.
 *
 * Records are reported to the callback as they arrive; the last query to finish is reported with
 * `last = true`. The HTTPS record's ECH data is surfaced by value as a
 * [Dns.Record.ServiceMetadata], so it survives a resolver that wraps and forwards this one and is
 * bound to the endpoint group it applies to, rather than being stashed in a side channel. Service
 * metadata is best-effort: a failed or absent HTTPS record never fails the lookup.
 *
 * Both APIs are available from Android 10 (API 29); applying ECH to a socket is a separate,
 * later-platform concern that reads [Dns.Record.ServiceMetadata.echConfigList] off the result.
 *
 * Use it via [OkHttpClient.Builder.dns]:
 *
 * ```
 * val client = OkHttpClient.Builder()
 *   .dns(AndroidDns())
 *   .build()
 * ```
 */
@RequiresApi(29)
@SuppressSignatureCheck
class AndroidDns
  @JvmOverloads
  constructor(
    private val dnsResolver: DnsResolver = DnsResolver.getInstance(),
    private val network: Network? = null,
    /**
     * True to also query the `HTTPS` record for service metadata such as ECH. Set this to false to
     * save a query when only IP addresses are needed.
     */
    private val includeServiceMetadata: Boolean = true,
    // Runs inline; the executor only hands off DnsResolver's callbacks.
    private val executor: Executor = Executor { it.run() },
  ) : Dns {
    /**
     * Resolves addresses only, for callers using the legacy blocking API. This delegates to
     * [newCall] and drops HTTPS/ECH metadata, which [Dns] cannot carry.
     */
    override fun lookup(hostname: String): List<InetAddress> =
      newCall(Dns.Request(hostname))
        .execute()
        .filterIsInstance<Dns.Record.IpAddress>()
        .map { it.address }

    override fun newCall(request: Dns.Request): Dns.Call = AndroidDnsCall(request)

    private inner class AndroidDnsCall(
      override val request: Dns.Request,
    ) : Dns.Call {
      private val cancellationSignal = CancellationSignal()
      private val lock = Any()
      private var callback: Dns.Callback? = null

      /** Outstanding queries: `A`, `AAAA`, and `HTTPS` if [includeServiceMetadata]. */
      private var pending = if (includeServiceMetadata) 3 else 2
      private var terminated = false
      private var anyRecordsEmitted = false
      private val failures = mutableListOf<IOException>()

      override fun enqueue(callback: Dns.Callback) {
        synchronized(lock) {
          check(this.callback == null) { "already enqueued" }
          if (terminated) {
            callback.onFailure(this, IOException("canceled"))
            return
          }
          this.callback = callback
        }

        queryAddresses(DnsResolver.TYPE_A)
        queryAddresses(DnsResolver.TYPE_AAAA)
        if (includeServiceMetadata) queryServiceMetadata()
      }

      /**
       * Asks [DnsResolver] for `A` or `AAAA` records. The platform parses these for us, so this
       * yields [InetAddress]es directly.
       */
      private fun queryAddresses(type: Int) {
        val queryCallback =
          object : DnsResolver.Callback<List<InetAddress>> {
            override fun onAnswer(
              answer: List<InetAddress>,
              rcode: Int,
            ) {
              // A non-zero rcode with no addresses (NXDOMAIN, SERVFAIL) is a failure. An empty
              // success is just a family this host doesn't publish.
              val failure =
                when {
                  answer.isEmpty() && rcode != RESPONSE_CODE_SUCCESS -> {
                    UnknownHostException("DNS lookup failed for ${request.hostname} (rcode $rcode)")
                  }

                  else -> {
                    null
                  }
                }
              deliver(
                records = answer.map { Dns.Record.IpAddress(request.hostname, it) },
                failure = failure,
              )
            }

            override fun onError(e: DnsResolver.DnsException) {
              deliver(
                records = listOf(),
                failure = request.hostname.toUnknownHostException(e),
              )
            }
          }

        try {
          dnsResolver.query(
            network,
            request.hostname,
            type,
            DnsResolver.FLAG_EMPTY,
            executor,
            cancellationSignal,
            queryCallback,
          )
        } catch (e: Exception) {
          // query can reject a hostname synchronously (e.g. IDN.toASCII on an underscore name).
          deliver(
            records = listOf(),
            failure = request.hostname.toUnknownHostException(e),
          )
        }
      }

      /**
       * Asks [DnsResolver] for the `HTTPS` record. The platform has no typed API for this below
       * API 36, so we request the raw message and decode it with OkHttp's own [DnsMessageReader] —
       * the same decoder `DnsOverHttps` uses. Failures here are never fatal: service metadata is
       * best-effort, and the address queries carry the lookup.
       */
      private fun queryServiceMetadata() {
        val queryCallback =
          object : DnsResolver.Callback<ByteArray> {
            override fun onAnswer(
              answer: ByteArray,
              rcode: Int,
            ) {
              val records =
                try {
                  decodeAnswers(answer)
                } catch (_: IOException) {
                  listOf() // TODO should a malformed HTTPS record fail the lookup?
                }
              deliver(records = records, failure = null)
            }

            override fun onError(e: DnsResolver.DnsException) {
              deliver(records = listOf(), failure = null)
            }
          }

        try {
          dnsResolver.rawQuery(
            network,
            request.hostname,
            DnsResolver.CLASS_IN,
            TYPE_HTTPS,
            DnsResolver.FLAG_EMPTY,
            executor,
            cancellationSignal,
            queryCallback,
          )
        } catch (_: Exception) {
          // TODO consider whether this is right and how Android network policy can be enforced?
          deliver(records = listOf(), failure = null)
        }
      }

      private fun decodeAnswers(answer: ByteArray): List<Dns.Record> {
        val message = DnsMessageReader(Buffer().write(answer)).read()
        return message.answers.map { resourceRecord ->
          when (resourceRecord) {
            is ResourceRecord.IpAddress -> {
              Dns.Record.IpAddress(
                hostname = request.hostname,
                address = resourceRecord.address,
              )
            }

            is ResourceRecord.Https -> {
              Dns.Record.ServiceMetadata(
                hostname = resourceRecord.targetName.ifEmpty { request.hostname },
                alpnIds =
                  resourceRecord.alpnIds?.mapNotNull { alpnId ->
                    try {
                      Protocol.get(alpnId)
                    } catch (_: IOException) {
                      null // Skip an unrecognized ALPN ID.
                    }
                  },
                port = resourceRecord.port,
                ipAddressHints = resourceRecord.ipAddressHints,
                echConfigList = resourceRecord.echConfigList,
              )
            }
          }
        }
      }

      /**
       * Delivers the outcome of one query to the callback. Calls are serialized by [lock]; the last
       * query to finish reports `last = true`, or reports failure if nothing resolved at all.
       */
      private fun deliver(
        records: List<Dns.Record>,
        failure: IOException?,
      ) {
        synchronized(lock) {
          val callback = callback
          if (terminated || callback == null) return

          if (failure != null) failures += failure
          val last = --pending == 0

          when {
            !last -> {
              if (records.isNotEmpty()) {
                anyRecordsEmitted = true
                callback.onRecords(this, last = false, records = records)
              }
            }

            records.isNotEmpty() || anyRecordsEmitted || failures.isEmpty() -> {
              terminated = true
              callback.onRecords(this, last = true, records = records)
            }

            else -> {
              terminated = true
              callback.onFailure(this, failures.combined())
            }
          }
        }
      }

      override fun cancel() {
        val callback =
          synchronized(lock) {
            if (terminated) return
            cancellationSignal.cancel()
            terminated = true
            callback
          }
        callback?.onFailure(this, IOException("canceled"))
      }

      override fun isCanceled(): Boolean = cancellationSignal.isCanceled
    }
  }

private fun List<IOException>.combined(): IOException {
  val first = first()
  for (i in 1 until size) first.addSuppressed(this[i])
  return first
}

private fun String.toUnknownHostException(cause: Throwable): UnknownHostException =
  UnknownHostException("DNS lookup failed for $this").apply {
    initCause(cause)
  }
