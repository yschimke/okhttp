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

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Request

/**
 * Shared HTTP/3 selection logic. Used by both [Quiche4jInterceptor] and
 * [Quiche4jWebSocketFactory] so the two surfaces agree on "is this origin reachable over
 * H/3 right now" without duplicating the tag / HTTPS-record / Alt-Svc plumbing.
 *
 * **Conservative by default** — we only attempt HTTP/3 when we have a positive signal:
 *
 *  1. [Http3Preference.Force] on the request → always yes.
 *  2. HTTPS DNS record (RFC 9460) advertises h3 → yes.
 *  3. Alt-Svc cache has an unexpired `h3` entry for this origin → yes.
 *  4. Otherwise → no (the interceptor falls through; the WebSocket factory fires
 *     [NoHttp3Route]).
 *
 * Rationale: attempting an unsolicited QUIC handshake to an origin that doesn't speak
 * HTTP/3 costs a UDP round-trip and a TLS handshake's worth of CPU before the handshake
 * times out — much more than the cost of adding an interceptor that never engages. The
 * positive-signal policy matches how Chrome and Cronet handle QUIC selection: they ship
 * a pre-seeded list of known-good origins ("QUIC hints") and discover the rest via HTTPS
 * records and Alt-Svc. Callers who want the same pre-seeding should populate
 * [AltSvcCache] on the `Quiche4jInterceptor.Builder` / `Quiche4jWebSocketFactory.Builder`
 * at startup — see the H/3 hints sample in the module README.
 *
 * [Http3Preference.ForceOff] is handled before calling this — callers should short-circuit
 * there. `ForceOff` on a path that must use H/3 surfaces as [NoHttp3Route] rather than
 * reaching this helper.
 */
internal object Http3Decision {
  fun shouldAttempt(
    request: Request,
    dns: Dns,
    httpsResolver: HttpsServiceRecordResolver?,
    altSvcCache: AltSvcCache,
  ): Boolean {
    val preference = Http3Preference.of(request)
    if (preference is Http3Preference.Force) return true

    val hostname = request.url.host
    val record = resolveHttpsRecord(hostname, dns, httpsResolver)
    if (record?.supportsHttp3 == true) return true

    val origin = AltSvcOrigin.of(request.url)
    if (altSvcCache.get(origin).any { it.protocolId.equals("h3", ignoreCase = true) }) return true

    return false
  }

  private fun resolveHttpsRecord(
    hostname: String,
    dns: Dns,
    httpsResolver: HttpsServiceRecordResolver?,
  ): HttpsServiceRecord? =
    when {
      dns is HttpsAware -> {
        try {
          dns.lookup(hostname)
        } catch (_: Throwable) {
          // Best-effort — callers will re-issue real DNS when they dial.
        }
        dns.getHttpsServiceRecord(hostname)
      }
      httpsResolver != null ->
        try {
          httpsResolver.lookup(hostname).firstOrNull()
        } catch (_: Throwable) {
          null
        }
      else -> null
    }
}

/**
 * Signals "HTTP/3 isn't a viable route for this request" — either the caller set
 * [Http3Preference.ForceOff] or discovery (HTTPS record + Alt-Svc) said the origin
 * doesn't advertise h3. Distinct from a QUIC handshake failure so
 * [FailoverWebSocketFactory] can tell "never try this again" from "this attempt didn't
 * pan out".
 */
class NoHttp3Route internal constructor(
  message: String,
) : java.io.IOException(message)

/** Convenience extension mirroring [AltSvcOrigin.of] for places that already have a URL in hand. */
internal fun HttpUrl.altSvcOrigin(): AltSvcOrigin = AltSvcOrigin.of(this)
