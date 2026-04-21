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
 * Priority, highest first:
 *
 *  1. [Http3Preference.Force] on the request → always yes.
 *  2. Discovery is disabled (no [HttpsAware] [Dns], no explicit resolver, no Alt-Svc cache
 *     entry yet) → assume yes, because the caller chose this interceptor / factory.
 *  3. HTTPS DNS record (RFC 9460) advertises h3 → yes.
 *  4. Alt-Svc cache has an unexpired `h3` entry → yes.
 *  5. Otherwise → no.
 *
 * [Http3Preference.ForceOff] is handled before calling this — callers should short-circuit
 * there. `ForceOff` on a path that must use H/3 (e.g. [Quiche4jWebSocketFactory] in
 * isolation) surfaces as [NoHttp3Route] rather than reaching this helper.
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
    val origin = AltSvcOrigin.of(request.url)
    val altSvcHasH3 = altSvcCache.get(origin).any { it.protocolId.equals("h3", ignoreCase = true) }

    val discoveryEnabled =
      dns is HttpsAware ||
        httpsResolver != null ||
        altSvcCache.get(origin).isNotEmpty()

    return when {
      !discoveryEnabled -> true
      record?.supportsHttp3 == true -> true
      altSvcHasH3 -> true
      else -> false
    }
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
