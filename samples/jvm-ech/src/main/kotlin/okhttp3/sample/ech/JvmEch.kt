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
 * Steps performed:
 *   1. Install ECH-enabled Conscrypt as the first JSSE provider.
 *   2. Build a DoH client and wrap it with [EchAwareDnsOverHttps] so that the `ech` SvcParam
 *      from the HTTPS DNS record is surfaced via [okhttp3.EchAware].
 *   3. Issue the user-supplied request; OkHttp's `ConscryptPlatform.echModeConfiguration` will
 *      reflectively apply the ECH config list to the TLS socket if Conscrypt supports it.
 */
fun main(args: Array<String>) {
  val targetUrl = (args.firstOrNull() ?: "https://crypto.cloudflare.com/cdn-cgi/trace").toHttpUrl()

  // 1. Install Conscrypt at the top of the provider list. The DEfO fork uses the same
  //    `Conscrypt.newProvider()` entry point as upstream so this code is identical.
  Security.insertProviderAt(Conscrypt.newProvider(), 1)
  println("Conscrypt version: ${Conscrypt.version()}")
  println("ECH-enabled Conscrypt: ${hasEchApi()}")

  // 2. DoH bootstrap client (no DNS-of-DNS dependency). Cloudflare's 1.1.1.1 is used here.
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

  // 3. Real client that uses the ECH-aware Dns. The platform-supplied EchModeConfiguration
  //    (ConscryptEchModeConfiguration when Conscrypt is the active platform) wires the
  //    EchConfig from DNS into the TLS handshake.
  val client =
    OkHttpClient
      .Builder()
      .dns(echAwareDns)
      .build()

  // Force DNS to run so getEchConfig is populated before the connection.
  val addresses = echAwareDns.lookup(targetUrl.host)
  println("Resolved ${targetUrl.host}: $addresses")
  println("ECH config available from DNS: ${echAwareDns.getEchConfig(targetUrl.host) != null}")

  client.newCall(Request.Builder().url(targetUrl).build()).execute().use { response ->
    println("HTTP ${response.code} ${response.message}")
    val body = response.body.string()
    println("--- response body ---")
    println(body)
    println("--- end ---")
    // crypto.cloudflare.com reflects `sni=encrypted` for ECH-protected handshakes.
    val protectedByEch = body.contains("sni=encrypted")
    println("SNI was encrypted: $protectedByEch")
  }
}

private fun hasEchApi(): Boolean =
  try {
    Class
      .forName("org.conscrypt.Conscrypt")
      .getMethod(
        "setEchConfigList",
        javax.net.ssl.SSLSocket::class.java,
        ByteArray::class.java,
      )
    true
  } catch (_: ReflectiveOperationException) {
    false
  }
