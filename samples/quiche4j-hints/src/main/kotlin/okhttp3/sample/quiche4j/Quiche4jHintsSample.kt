/*
 * Copyright (C) 2026 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package okhttp3.sample.quiche4j

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.quiche4j.AltSvcCache
import okhttp3.quiche4j.AltSvcEntry
import okhttp3.quiche4j.AltSvcOrigin
import okhttp3.quiche4j.HttpsAwareDns
import okhttp3.quiche4j.InMemoryAltSvcCache
import okhttp3.quiche4j.Quiche4jInterceptor

/**
 * Seeds the Alt-Svc cache with known-good HTTP/3 origins so the very first request to each
 * of them uses H/3 instead of falling through to H/1.1 or H/2 while the cache warms up.
 *
 * This mirrors Chrome's / Cronet's "QUIC hints" strategy. Chrome ships a static list of
 * hosts that are known to speak QUIC (`net::HttpServerProperties::SetQuicServerInfoMap` +
 * compile-time hints in `quic_params.cc`); Cronet exposes the same idea as a builder API:
 *
 * ```java
 * CronetEngine.Builder builder = new CronetEngine.Builder(context);
 * builder.addQuicHint("www.google.com", 443, 443);
 * builder.addQuicHint("www.googleapis.com", 443, 443);
 * ```
 *
 * The conservative default in [Quiche4jInterceptor] only attempts HTTP/3 when we've seen
 * positive evidence — an HTTPS DNS record (RFC 9460) advertising `h3`, or an Alt-Svc
 * header cached from a prior response. Pre-seeding is the "positive evidence for
 * bootstrap" piece: say "I know these origins speak h3, go straight there" before the
 * first round-trip has a chance to tell us.
 *
 * Hints are exact-match per host+port. Chrome maintains sub-domain patterns
 * (`*.googleusercontent.com`) internally by enumerating actual sub-domains as they're
 * discovered; we stay simple here. Callers with many sub-domains should plug in an
 * [okhttp3.quiche4j.HttpsAware] [okhttp3.Dns] (e.g. [HttpsAwareDns]) that queries RFC
 * 9460 records on-demand — that's the right long-term answer.
 */
object Quiche4jHintsSample {
  /**
   * A curated list of hosts known to speak HTTP/3 on 443/udp. Sourced from Chrome's QUIC
   * hints (`net/quic/quic_chromium_packet_writer.cc`, `chrome/browser/net/
   * system_network_context_manager.cc`) plus public H/3 endpoint catalogues. Production
   * callers should plumb this from a server-side config / feature flag, not hard-code.
   */
  val KNOWN_H3_HOSTS: List<Pair<String, Int>> =
    listOf(
      // Google properties — the largest H/3 fleet on the public internet.
      "www.google.com" to 443,
      "google.com" to 443,
      "mail.google.com" to 443,
      "drive.google.com" to 443,
      "docs.google.com" to 443,
      "maps.google.com" to 443,
      "www.youtube.com" to 443,
      "youtube.com" to 443,
      "ssl.gstatic.com" to 443,
      "fonts.gstatic.com" to 443,
      "fonts.googleapis.com" to 443,
      "www.googleapis.com" to 443,
      "storage.googleapis.com" to 443,
      "clients1.google.com" to 443,
      // Cloudflare — runs the quiche reference server on this host.
      "www.cloudflare.com" to 443,
      "cloudflare-quic.com" to 443,
      "blog.cloudflare.com" to 443,
      // Meta — fbcdn serves most of the image/video payload.
      "www.facebook.com" to 443,
      "static.xx.fbcdn.net" to 443,
      "scontent.xx.fbcdn.net" to 443,
      // Microsoft / Azure — CDNs speak H/3.
      "outlook.office.com" to 443,
      "www.microsoft.com" to 443,
    )

  /** Seed [cache] with an unexpired `h3` Alt-Svc entry for each [hosts] entry. */
  fun seed(
    cache: AltSvcCache,
    hosts: List<Pair<String, Int>> = KNOWN_H3_HOSTS,
    ttlSeconds: Long = AltSvcEntry.DEFAULT_MA_SECONDS,
  ) {
    val expiresAt = System.currentTimeMillis() + ttlSeconds * 1000L
    for ((host, port) in hosts) {
      val origin = AltSvcOrigin("https", host, port)
      cache.put(
        origin,
        listOf(
          AltSvcEntry(
            protocolId = "h3",
            host = "", // same host
            port = port,
            expiresAtMillis = expiresAt,
          ),
        ),
      )
    }
  }

  @JvmStatic
  fun main(args: Array<String>) {
    // 1. Build the cache and seed it. Pair it with an HttpsAwareDns so origins *not* in the
    //    hint list can still upgrade via their published HTTPS DNS records.
    val cache = InMemoryAltSvcCache()
    seed(cache)

    val interceptor =
      Quiche4jInterceptor
        .Builder()
        .altSvcCache(cache)
        .build()
    val client =
      OkHttpClient
        .Builder()
        .dns(HttpsAwareDns())
        .addInterceptor(interceptor)
        .build()

    // 2. Hit a few of the pre-seeded hosts. Each should take the H/3 path on the FIRST
    //    request — no warm-up round-trip needed, because the hint told the interceptor
    //    which protocol to use before it had to ask the server.
    val urls =
      listOf(
        "https://www.google.com/",
        "https://cloudflare-quic.com/",
        "https://www.cloudflare.com/",
      )
    for (url in urls) {
      val response = client.newCall(Request.Builder().url(url).build()).execute()
      response.use {
        println("$url → ${it.protocol} ${it.code} (${it.body.contentLength().takeIf { l -> l >= 0 } ?: "chunked"})")
      }
    }

    // 3. A host NOT in the hints AND not advertising h3 falls through to H/1.1 / H/2 — no
    //    wasted QUIC handshake.
    val noH3 = client.newCall(Request.Builder().url("https://example.com/").build()).execute()
    noH3.use { println("https://example.com/ → ${it.protocol} ${it.code}") }
  }
}
