/*
 * Copyright (C) 2026 Square, Inc.
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
package okhttp3

/**
 * An origin keyed in [AltSvcCache]: scheme + host + port. Matches the "origin" concept from
 * RFC 7838 §2.
 */
data class AltSvcOrigin(
  val scheme: String,
  val host: String,
  val port: Int,
) {
  companion object {
    @JvmStatic
    fun of(url: HttpUrl): AltSvcOrigin = AltSvcOrigin(url.scheme, url.host, url.port)
  }
}

/**
 * One parsed entry from an `Alt-Svc` response header (RFC 7838).
 *
 * Example: `Alt-Svc: h3=":443"; ma=86400` produces
 * `AltSvcEntry("h3", host = "", port = 443, expiresAtMillis = now + 86400*1000)`.
 *
 * The [host] field is empty when the server advertises the same host as the origin
 * (the most common case). Consumers should fall back to the origin host when routing.
 */
data class AltSvcEntry(
  val protocolId: String,
  val host: String,
  val port: Int,
  val expiresAtMillis: Long,
  val persist: Boolean = false,
) {
  val isExpired: Boolean
    get() = System.currentTimeMillis() > expiresAtMillis

  /** Back to `protocol=":port"; ma=N` form, useful for serialization. */
  fun toHeaderValue(): String =
    buildString {
      append(protocolId)
      append('=')
      append('"')
      append(host)
      append(':')
      append(port)
      append('"')
      val maSeconds = ((expiresAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
      append("; ma=")
      append(maSeconds)
      if (persist) append("; persist=1")
    }

  companion object {
    /**
     * Parse an `Alt-Svc` header value into its entries. Returns an empty list for the special
     * `clear` value (the caller should treat that as "purge the cache for this origin").
     *
     * Only handles the common subset of RFC 7838: comma-separated entries, each
     * `protocol-id=authority; param=value` with `ma` (seconds) and `persist`. Unknown
     * parameters are ignored. Quoted values may contain escapes; we pass them through
     * unchanged, which is good enough for the hostnames / ports that appear in practice.
     */
    @JvmStatic
    @JvmOverloads
    fun parseHeader(
      value: String,
      receivedAtMillis: Long = System.currentTimeMillis(),
    ): List<AltSvcEntry> {
      val trimmed = value.trim()
      if (trimmed.equals("clear", ignoreCase = true)) return emptyList()
      val results = mutableListOf<AltSvcEntry>()
      for (rawEntry in splitAtCommas(trimmed)) {
        val entry = rawEntry.trim()
        if (entry.isEmpty()) continue
        val parts = entry.split(';').map { it.trim() }
        val head = parts.firstOrNull() ?: continue
        val eq = head.indexOf('=')
        if (eq < 0) continue
        val protoId = head.substring(0, eq).trim()
        val authority = unquote(head.substring(eq + 1).trim())
        val (host, port) = splitAuthority(authority) ?: continue
        var ma: Long = DEFAULT_MA_SECONDS
        var persist = false
        for (p in parts.drop(1)) {
          val pEq = p.indexOf('=')
          if (pEq < 0) continue
          val name = p.substring(0, pEq).trim().lowercase()
          val v = unquote(p.substring(pEq + 1).trim())
          when (name) {
            "ma" -> ma = v.toLongOrNull() ?: ma
            "persist" -> persist = v == "1"
          }
        }
        results +=
          AltSvcEntry(
            protocolId = protoId,
            host = host,
            port = port,
            expiresAtMillis = receivedAtMillis + ma * 1000,
            persist = persist,
          )
      }
      return results
    }

    /** Default `max-age` per RFC 7838 §3: 24 hours. */
    const val DEFAULT_MA_SECONDS: Long = 24 * 60 * 60

    private fun splitAtCommas(input: String): List<String> {
      // Commas inside quotes don't delimit entries. An unbalanced quote (no closing ")
      // used to swallow the rest of the header; match browsers' robust behaviour by
      // treating a malformed run as "one entry" — the entry parser will then drop it
      // for being malformed.
      val parts = mutableListOf<String>()
      var inQuotes = false
      var start = 0
      var i = 0
      while (i < input.length) {
        val c = input[i]
        when {
          c == '"' -> inQuotes = !inQuotes
          c == ',' && !inQuotes -> {
            parts += input.substring(start, i)
            start = i + 1
          }
          c == '\\' && inQuotes && i + 1 < input.length -> i++
        }
        i++
      }
      parts += input.substring(start)
      return parts
    }

    private fun unquote(s: String): String {
      if (s.length >= 2 && s.first() == '"' && s.last() == '"') {
        val inner = s.substring(1, s.length - 1)
        return buildString(inner.length) {
          var i = 0
          while (i < inner.length) {
            val c = inner[i]
            if (c == '\\' && i + 1 < inner.length) {
              append(inner[i + 1])
              i += 2
            } else {
              append(c)
              i++
            }
          }
        }
      }
      return s
    }

    private fun splitAuthority(authority: String): Pair<String, Int>? {
      // RFC 7838 §3 requires `alt-authority = [ uri-host ] ":" port`. IPv6 literals
      // (RFC 3986 §3.2.2) are bracketed: `[2001:db8::1]:443`. Pure `lastIndexOf(':')`
      // alone would pick a colon inside an IPv6 address — handle the bracketed form
      // explicitly.
      if (authority.startsWith("[")) {
        val close = authority.indexOf(']')
        if (close < 0 || close == authority.length - 1) return null
        if (authority[close + 1] != ':') return null
        val host = authority.substring(0, close + 1)
        val port = authority.substring(close + 2).toIntOrNull() ?: return null
        return host to port
      }
      val colon = authority.lastIndexOf(':')
      if (colon < 0) return null
      // Reject unbracketed IPv6 (multiple colons outside brackets) — RFC 7838 doesn't
      // permit it and the header is almost certainly malformed.
      if (authority.indexOf(':') != colon) return null
      val host = authority.substring(0, colon)
      val port = authority.substring(colon + 1).toIntOrNull() ?: return null
      return host to port
    }
  }
}
