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
package okhttp3.internal.http3

import okhttp3.Address
import okhttp3.AltSvcOrigin
import okhttp3.Http3Preference
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request

/**
 * Discovery policy: given a client, request, and address, should OkHttp attempt
 * HTTP/3 for this call? Pure function — no I/O except an optional HTTPS-record
 * resolver consult, and only if the cheaper signals didn't already decide.
 *
 * Precedence (highest wins):
 *
 * 1. [Http3Preference.ForceOff] request tag — always no.
 * 2. [Http3Preference.Force] request tag — always yes (skips every other check).
 * 3. No [OkHttpClient.http3Engine] installed — no.
 * 4. [Protocol.HTTP_3] not in [Address.protocols] — no (caller opted out at client
 *    level).
 * 5. Unexpired `h3` entry in [OkHttpClient.altSvcCache] for this origin — yes.
 * 6. [OkHttpClient.httpsServiceRecordResolver] returns a record advertising `h3` —
 *    yes. Resolver failures are swallowed as "no signal" and fall through.
 * 7. Otherwise — no.
 */
internal object Http3Decision {
  fun shouldAttempt(
    client: OkHttpClient,
    request: Request,
    address: Address,
  ): Boolean {
    // 1 + 2: explicit per-request preference wins over everything.
    return when (Http3Preference.of(request)) {
      is Http3Preference.ForceOff -> false
      is Http3Preference.Force -> true
      is Http3Preference.Current -> shouldAttemptFromDiscovery(client, address)
    }
  }

  private fun shouldAttemptFromDiscovery(
    client: OkHttpClient,
    address: Address,
  ): Boolean {
    // 3: no engine, no H/3.
    if (client.http3Engine == null) return false

    // 4: caller removed HTTP/3 from the protocol list at client level.
    if (Protocol.HTTP_3 !in address.protocols) return false

    // 5: cached Alt-Svc advertisement.
    val origin = AltSvcOrigin(scheme = address.url.scheme, host = address.url.host, port = address.url.port)
    val cached = client.altSvcCache.get(origin)
    if (cached.any { it.protocolId.equals("h3", ignoreCase = true) }) {
      return true
    }

    // 6: HTTPS DNS record (RFC 9460) — optional, only if installed.
    val resolver = client.httpsServiceRecordResolver
    if (resolver != null) {
      try {
        val records = resolver.lookup(address.url.host)
        if (records.any { it.supportsHttp3 }) return true
      } catch (_: Exception) {
        // Treat resolver failure as "no signal" — don't let a DNS hiccup block the
        // TCP path. The AltSvcCache will still catch advertisements from subsequent
        // responses.
      }
    }

    // 7: no signal.
    return false
  }
}
