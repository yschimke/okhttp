/*
 * Copyright (c) 2026 OkHttp Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package okhttp3.sample.ech

import java.security.Security
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.dnsoverhttps.EchAwareDnsOverHttps
import org.conscrypt.Conscrypt

/**
 * End-to-end demonstration of Encrypted Client Hello on the JVM using OkHttp + DEfO Conscrypt.
 *
 * Run with:
 *   ./gradlew :samples:jvm-ech:run --args="https://crypto.cloudflare.com/cdn-cgi/trace"
 *
 * Cloudflare's `crypto.cloudflare.com` reflects whether the request was made with ECH.
 *
 * Steps:
 *   1. Install ECH-enabled Conscrypt as the first JSSE provider.
 *   2. Build a DoH client and wrap it with [EchAwareDnsOverHttps] so the `ech` SvcParam from
 *      the RFC 9460 HTTPS DNS record is surfaced via [okhttp3.EchAware].
 *   3. Wire a [ConscryptEchModeConfiguration] onto the OkHttpClient. There is no reflection:
 *      this module links directly against the DEfO Conscrypt API at compile time.
 */
fun main(args: Array<String>) {
  val targetUrl = (args.firstOrNull() ?: "https://crypto.cloudflare.com/cdn-cgi/trace").toHttpUrl()

  // 1. Install Conscrypt at the top of the provider list.
  Security.insertProviderAt(Conscrypt.newProvider(), 1)
  println("Conscrypt version: ${Conscrypt.version()}")

  // 2. ECH-aware DoH bootstrap. Cloudflare's 1.1.1.1 resolver is used.
  val bootstrap = OkHttpClient()
  val doh =
    DnsOverHttps
      .Builder()
      .client(bootstrap)
      .url("https://1.1.1.1/dns-query".toHttpUrl())
      .includeIPv6(true)
      .post(true)
      .build()
  val echAwareDns = EchAwareDnsOverHttps(doh)

  // 3. Real client. The ECH config is supplied by `echAwareDns` (via EchAware) and applied
  //    by `ConscryptEchModeConfiguration` during the TLS handshake.
  val echConfig = ConscryptEchModeConfiguration()
  val client =
    OkHttpClient
      .Builder()
      .dns(echAwareDns)
      .echModeConfiguration(echConfig)
      .build()

  // Force DNS so EchAware is populated before we report.
  val addresses = echAwareDns.lookup(targetUrl.host)
  println("Resolved ${targetUrl.host}: $addresses")
  println("ECH config from DNS: ${echAwareDns.getEchConfig(targetUrl.host) != null}")

  client.newCall(Request.Builder().url(targetUrl).build()).execute().use { response ->
    println("HTTP ${response.code} ${response.message}")
    val body = response.body.string()
    println("--- response body ---")
    println(body)
    println("--- end ---")
    // crypto.cloudflare.com reflects `sni=encrypted` for ECH-protected handshakes.
    println("SNI was encrypted: ${"sni=encrypted" in body}")
  }
}
