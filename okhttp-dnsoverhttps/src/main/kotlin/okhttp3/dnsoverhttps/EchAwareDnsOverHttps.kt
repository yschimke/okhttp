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
package okhttp3.dnsoverhttps

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Dns
import okhttp3.EchAware
import okhttp3.ech.EchConfig
import okio.ByteString

/**
 * A [Dns] that resolves `A` / `AAAA` records via [DnsOverHttps] *and* concurrently queries the
 * RFC 9460 `HTTPS` record so the `ech` SvcParam can be surfaced via [EchAware.getEchConfig].
 *
 * The HTTPS-record lookup is best-effort: when it fails or the record carries no `ech` parameter,
 * the address resolution still succeeds and [getEchConfig] returns `null`. ECH then falls back to
 * standard TLS per the configured [okhttp3.ech.EchMode].
 *
 * Successful HTTPS records are cached in-memory until the next [lookup] for that host.
 */
class EchAwareDnsOverHttps(
  private val doh: DnsOverHttps,
) : Dns,
  EchAware {
  private val echConfigs = ConcurrentHashMap<String, EchConfig>()

  @Throws(UnknownHostException::class)
  override fun lookup(hostname: String): List<InetAddress> {
    // Run the HTTPS-record lookup on the common ForkJoinPool so it overlaps with the address
    // lookup. We avoid OkHttp's dispatcher to keep this off the HTTP request scheduler.
    val httpsFuture =
      CompletableFuture.supplyAsync<HttpsRecord?> {
        try {
          doh.lookupHttpsRecord(hostname)
        } catch (_: Exception) {
          null
        }
      }

    val addresses =
      try {
        doh.lookup(hostname)
      } finally {
        try {
          val ech = httpsFuture.get()?.ech
          if (ech != null) {
            echConfigs[hostname] = EchConfigBytes(ech)
          } else {
            echConfigs.remove(hostname)
          }
        } catch (_: Exception) {
          echConfigs.remove(hostname)
        }
      }

    return addresses
  }

  override fun getEchConfig(host: String): EchConfig? = echConfigs[host]

  private data class EchConfigBytes(
    override val config: ByteString,
  ) : EchConfig
}
