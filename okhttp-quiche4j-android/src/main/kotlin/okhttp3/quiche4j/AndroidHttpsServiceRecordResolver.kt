/*
 * Copyright (C) 2026 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package okhttp3.quiche4j

import android.net.DnsResolver
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okio.ByteString.Companion.toByteString
import org.xbill.DNS.HTTPSRecord
import org.xbill.DNS.Message
import org.xbill.DNS.SVCBBase
import org.xbill.DNS.Section

/**
 * [HttpsServiceRecordResolver] for Android API 29+ that routes HTTPS (SVCB type 65) DNS queries
 * through the platform's [DnsResolver]. This works inside the Android app sandbox where
 * dnsjava's default UDP-to-:53 resolver doesn't, and returns raw DNS answer bytes that we decode
 * with dnsjava's [Message] + [HTTPSRecord] parsers.
 *
 * Lives in the android-test module for the POC — a production build would move this into an
 * Android variant of okhttp-quiche4j. Mirrors the pattern prototyped in
 * [square/okhttp#9383](https://github.com/square/okhttp/pull/9383)'s `AndroidDnsResolverDns`,
 * but uses dnsjava for parsing instead of API-36-only `HttpsRecord`.
 */
@RequiresApi(29)
class AndroidHttpsServiceRecordResolver(
  private val dnsResolver: DnsResolver = DnsResolver.getInstance(),
  private val timeoutMillis: Long = 2_000,
  /**
   * Executor `DnsResolver.rawQuery` uses for its callback. Defaults to a shared, bounded
   * daemon pool — DNS queries complete quickly and a couple of threads is enough. Pass a
   * different executor (e.g. `OkHttpClient.dispatcher.executorService`) to fold into
   * whatever pool the rest of the app already runs on.
   */
  private val executor: Executor = DEFAULT_EXECUTOR,
) : HttpsServiceRecordResolver {

  override fun lookup(hostname: String): List<HttpsServiceRecord> {
    val future = CompletableFuture<ByteArray?>()
    val cancellation = CancellationSignal()
    dnsResolver.rawQuery(
      null, // default network
      hostname,
      DnsResolver.CLASS_IN,
      HTTPS_DNS_TYPE,
      DnsResolver.FLAG_EMPTY,
      executor,
      cancellation,
      object : DnsResolver.Callback<ByteArray> {
        override fun onAnswer(
          answer: ByteArray,
          rcode: Int,
        ) {
          future.complete(if (rcode == 0) answer else null)
        }

        override fun onError(error: DnsResolver.DnsException) {
          future.complete(null)
        }
      },
    )
    val raw =
      try {
        future.get(timeoutMillis, TimeUnit.MILLISECONDS)
      } catch (_: Throwable) {
        cancellation.cancel()
        null
      } ?: return emptyList()
    return parse(raw)
  }

  private fun parse(raw: ByteArray): List<HttpsServiceRecord> {
    val message = Message(raw)
    val answers = message.getSection(Section.ANSWER) ?: return emptyList()
    return answers
      .filterIsInstance<HTTPSRecord>()
      .sortedBy { it.svcPriority }
      .map { it.toServiceRecord() }
  }

  private fun HTTPSRecord.toServiceRecord(): HttpsServiceRecord {
    val alpn = getSvcParamValue(SVCBBase.ALPN) as? SVCBBase.ParameterAlpn
    val port = getSvcParamValue(SVCBBase.PORT) as? SVCBBase.ParameterPort
    val v4 = getSvcParamValue(SVCBBase.IPV4HINT) as? SVCBBase.ParameterIpv4Hint
    val v6 = getSvcParamValue(SVCBBase.IPV6HINT) as? SVCBBase.ParameterIpv6Hint
    val ech = getSvcParamValue(SVCBBase.ECH) as? SVCBBase.ParameterEch
    val hints = (v4?.addresses.orEmpty()) + (v6?.addresses.orEmpty())
    return HttpsServiceRecord(
      priority = svcPriority,
      targetName = targetName.toString(true),
      port = port?.port,
      alpnIds = alpn?.values.orEmpty(),
      ipAddressHints = hints,
      echConfigList = ech?.data?.let { if (it.isEmpty()) null else it.toByteString() },
    )
  }

  private companion object {
    /** DNS resource record type 65 = HTTPS (RFC 9460). */
    const val HTTPS_DNS_TYPE = 65

    /**
     * Shared daemon pool used when the caller doesn't supply an [Executor]. Bounded so a
     * burst of lookups can't explode thread count.
     */
    private val DEFAULT_EXECUTOR: Executor =
      Executors.newFixedThreadPool(2) { r ->
        Thread(r, "okhttp-quiche4j-android-dns").apply { isDaemon = true }
      }
  }
}
