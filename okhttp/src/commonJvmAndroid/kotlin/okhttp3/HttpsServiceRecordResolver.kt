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
 * Resolves HTTPS DNS records (RFC 9460) for an origin. A single lookup answers "does this
 * origin speak HTTP/3?" and (eventually) "what ECH config should we use?" and "which
 * ports/IPs should we prefer?" in one round trip.
 *
 * Plug an implementation in via [OkHttpClient.Builder.httpsServiceRecordResolver] to let
 * the route planner consult HTTPS records when deciding whether to attempt HTTP/3.
 *
 * OkHttp does not ship a default implementation — the on-the-wire HTTPS/SVCB parser lives
 * in an optional dependency (e.g. dnsjava on the JVM, `android.net.DnsResolver` on
 * Android 29+). Implementations must be thread-safe. [lookup] is called synchronously
 * from the route-planning thread, so implementations should either be cheap or provide
 * their own caching.
 */
fun interface HttpsServiceRecordResolver {
  /**
   * Returns all HTTPS service records for [hostname], sorted highest priority first.
   * An empty list does **not** mean "no HTTP/3" — many hosts speak H/3 without publishing
   * HTTPS records. It just means this source has nothing to say; the caller may still
   * attempt H/3 based on other signals (Alt-Svc, explicit request preference).
   *
   * Failures (DNS timeout, malformed response, ...) surface as exceptions. The route
   * planner treats any exception as "no H/3 signal from this source" and falls through.
   */
  fun lookup(hostname: String): List<HttpsServiceRecord>
}
