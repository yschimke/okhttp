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
 * HTTP/3 for this call, and on what UDP port? Pure function — no I/O except an
 * optional HTTPS-record resolver consult, and only if the cheaper signals didn't
 * already decide.
 *
 * Precedence (highest wins):
 *
 * 1. [Http3Preference.ForceOff] request tag — always [Decision.Skip].
 * 2. [Http3Preference.Force] request tag — always [Decision.Attempt], honouring
 *    [Http3Preference.Force.portOverride] when set.
 * 3. No [OkHttpClient.http3Engine] installed — [Decision.Skip].
 * 4. [Protocol.HTTP_3] not in [Address.protocols] — [Decision.Skip].
 * 5. Unexpired `h3` entry in [OkHttpClient.altSvcCache] for this origin —
 *    [Decision.Attempt] with the entry's [okhttp3.AltSvcEntry.port] as the override
 *    if it differs from the address's default.
 * 6. [OkHttpClient.httpsServiceRecordResolver] returns a record advertising `h3` —
 *    [Decision.Attempt] with the record's port override if present. Resolver
 *    failures are swallowed as "no signal" and fall through.
 * 7. Otherwise — [Decision.Skip].
 */
internal object Http3Decision {
  sealed class Decision {
    /** No HTTP/3 attempt for this call. */
    object Skip : Decision()

    /**
     * Attempt HTTP/3 for this call. [portOverride] is non-null when discovery
     * signalled a port distinct from [Address.url]'s default (e.g. an HTTPS record
     * with `port=8443` or an Alt-Svc entry like `h3=":8443"`).
     */
    data class Attempt(
      val portOverride: Int? = null,
    ) : Decision()
  }

  fun decide(
    client: OkHttpClient,
    request: Request,
    address: Address,
  ): Decision {
    return when (val pref = Http3Preference.of(request)) {
      is Http3Preference.ForceOff -> Decision.Skip
      is Http3Preference.Force -> Decision.Attempt(portOverride = pref.portOverride)
      is Http3Preference.Current -> decideFromDiscovery(client, address)
    }
  }

  private fun decideFromDiscovery(
    client: OkHttpClient,
    address: Address,
  ): Decision {
    // 3: no engine, no H/3.
    if (client.http3Engine == null) return Decision.Skip

    // 4: caller removed HTTP/3 from the protocol list at client level.
    if (Protocol.HTTP_3 !in address.protocols) return Decision.Skip

    // 5: cached Alt-Svc advertisement. Use the entry's port if it differs from the
    // address's default (RFC 7838 "h3=:8443" wins over the origin port).
    val defaultPort = address.url.port
    val origin = AltSvcOrigin(scheme = address.url.scheme, host = address.url.host, port = defaultPort)
    val cached = client.altSvcCache.get(origin)
    val h3Entry = cached.firstOrNull { it.protocolId.equals("h3", ignoreCase = true) }
    if (h3Entry != null) {
      val portOverride = h3Entry.port.takeIf { it != defaultPort }
      return Decision.Attempt(portOverride = portOverride)
    }

    // 6: HTTPS DNS record (RFC 9460) — optional, only if installed.
    val resolver = client.httpsServiceRecordResolver
    if (resolver != null) {
      try {
        val records = resolver.lookup(address.url.host)
        val h3Record = records.firstOrNull { it.supportsHttp3 }
        if (h3Record != null) {
          val portOverride = h3Record.port?.takeIf { it != defaultPort }
          return Decision.Attempt(portOverride = portOverride)
        }
      } catch (_: Exception) {
        // Treat resolver failure as "no signal" — don't let a DNS hiccup block the
        // TCP path. The AltSvcCache will still catch advertisements from subsequent
        // responses.
      }
    }

    // 7: no signal.
    return Decision.Skip
  }

  /** @deprecated Kept for tests that don't care about the port override. */
  fun shouldAttempt(
    client: OkHttpClient,
    request: Request,
    address: Address,
  ): Boolean = decide(client, request, address) is Decision.Attempt
}
