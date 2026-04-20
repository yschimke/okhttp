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

/**
 * Resolves HTTPS DNS records (RFC 9460) for an origin. A single lookup answers "does this origin
 * speak HTTP/3?" and (eventually) "what ECH config should we use?" and "which ports/IPs should
 * we prefer?" in one round trip.
 *
 * Implementations should be thread-safe and block the caller thread — the [Quiche4jInterceptor]
 * calls [lookup] synchronously from `intercept()`.
 */
interface HttpsServiceRecordResolver {
  /**
   * Returns all HTTPS service records for [hostname], sorted highest priority first. Returns an
   * empty list if no records were published (the caller should **not** treat this as "no H/3";
   * many hosts speak H/3 without publishing HTTPS records — see [PLAN.md]).
   *
   * Failures (DNS timeout, malformed response, ...) surface as exceptions. Callers that want a
   * soft-fallback should catch and fall through to the normal stack; see
   * [Quiche4jInterceptor]'s default behaviour.
   */
  fun lookup(hostname: String): List<HttpsServiceRecord>

  companion object {
    /** Default resolver backed by [dnsjava](https://github.com/dnsjava/dnsjava). */
    val DEFAULT: HttpsServiceRecordResolver = DnsJavaHttpsServiceRecordResolver()
  }
}
