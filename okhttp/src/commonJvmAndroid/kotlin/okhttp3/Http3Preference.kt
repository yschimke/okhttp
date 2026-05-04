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
 * Per-request override for OkHttp's HTTP/3 routing decision. Attach with OkHttp's generic
 * Kotlin `tag<T>(tag)` extension, or from Java via
 * `Request.Builder.tag(Http3Preference::class.java, ...)`.
 *
 * OkHttp's default decision uses the available discovery signals (HTTPS DNS record,
 * Alt-Svc cache, whether HTTP/3 is enabled on the client at all). When a request carries
 * an [Http3Preference] tag, it overrides that decision.
 *
 * ```kotlin
 * val request = Request.Builder()
 *   .url("https://example.com/")
 *   .tag<Http3Preference>(Http3Preference.Force())
 *   .build()
 * ```
 */
sealed class Http3Preference {
  /**
   * Use the client's default decision logic: HTTPS record > Alt-Svc cache > whether the
   * client has [OkHttpClient.Builder.protocols] listing [Protocol.HTTP_3]. Equivalent to
   * attaching no tag; provided for symmetry and so callers can set a preference
   * explicitly on a per-call basis.
   */
  object Current : Http3Preference() {
    override fun toString() = "Http3Preference.Current"
  }

  /**
   * Skip HTTP/3 entirely for this request. OkHttp's standard H/1.1 or H/2 stack handles
   * it. Useful for requests known to be incompatible with QUIC (e.g. behind a CONNECT
   * proxy that can't tunnel UDP) or for A/B testing.
   */
  object ForceOff : Http3Preference() {
    override fun toString() = "Http3Preference.ForceOff"
  }

  /**
   * Force HTTP/3 for this request, bypassing all discovery signals.
   *
   * @param portOverride Optional UDP port to connect to. Defaults to the port from the
   *   request's URL. Use this when an origin speaks H/3 on a non-standard UDP port but
   *   its HTTPS URL still refers to 443.
   * @param fallback When `true` (default), the client falls back to its standard
   *   H/1.1 / H/2 stack if the HTTP/3 attempt fails (handshake timeout, UDP unreachable,
   *   transport error, ...). When `false`, the original H/3 error propagates to the
   *   caller. Use `false` for tests or debugging where masking the H/3 failure would be
   *   misleading.
   */
  data class Force(
    val portOverride: Int? = null,
    val fallback: Boolean = true,
  ) : Http3Preference()

  companion object {
    /** Retrieves any [Http3Preference] tag on [request], or [Current] if none is set. */
    @JvmStatic
    fun of(request: Request): Http3Preference = request.tag<Http3Preference>() ?: Current
  }
}
