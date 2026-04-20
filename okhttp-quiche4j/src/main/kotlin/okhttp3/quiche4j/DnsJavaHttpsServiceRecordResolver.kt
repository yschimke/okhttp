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

import java.net.InetAddress
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.xbill.DNS.HTTPSRecord
import org.xbill.DNS.Lookup
import org.xbill.DNS.SVCBBase
import org.xbill.DNS.Type

/**
 * [HttpsServiceRecordResolver] backed by [dnsjava](https://github.com/dnsjava/dnsjava). Works on
 * any JVM or pre-Android-36 device since it issues the HTTPS-type (65) query and parses the
 * response itself.
 *
 * By default uses dnsjava's built-in resolver (system DNS), which is fine for most use cases. To
 * route the lookup through a specific resolver — for example DoH via OkHttp's own
 * `okhttp-dnsoverhttps` — wrap with a custom `org.xbill.DNS.Resolver` implementation.
 */
class DnsJavaHttpsServiceRecordResolver : HttpsServiceRecordResolver {
  override fun lookup(hostname: String): List<HttpsServiceRecord> {
    val lookup = Lookup(hostname, Type.HTTPS).apply { run() }
    val records = lookup.answers ?: return emptyList()
    return records
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
    val hints: List<InetAddress> =
      (v4?.addresses.orEmpty()) + (v6?.addresses.orEmpty())
    return HttpsServiceRecord(
      priority = svcPriority,
      targetName = targetName.toString(true), // strip trailing dot
      port = port?.port,
      alpnIds = alpn?.values.orEmpty(),
      ipAddressHints = hints,
      echConfigList =
        ech?.data?.let { if (it.isEmpty()) null else it.toByteString() as ByteString },
    )
  }
}
