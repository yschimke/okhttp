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

import okhttp3.Request

/**
 * Per-request override for [Quiche4jInterceptor]'s HTTP/3 routing decision. Attach via
 * `Request.Builder.tag(Http3Preference::class.java, ...)`.
 *
 * The interceptor's default decision uses all the available signals (HTTPS DNS record, Alt-Svc
 * cache, whether discovery is configured at all). When a request carries an [Http3Preference]
 * tag, it overrides that.
 *
 * ```kotlin
 * val request = Request.Builder()
 *   .url("https://example.com/")
 *   .tag(Http3Preference::class.java, Http3Preference.Force())
 *   .build()
 * ```
 */
sealed class Http3Preference {
  /**
   * Use the interceptor's default decision logic: HTTPS record > Alt-Svc cache > always-H3-if-
   * discovery-is-off. Equivalent to attaching no tag; provided for symmetry and so callers can
   * set a preference explicitly on a per-call basis.
   */
  object Current : Http3Preference() {
    override fun toString() = "Http3Preference.Current"
  }

  /**
   * Skip HTTP/3 entirely. The interceptor calls `chain.proceed()` and OkHttp's standard H/1.1
   * or H/2 stack handles the request. Useful for requests known to be incompatible with QUIC
   * (for example when using a CONNECT proxy that can't tunnel UDP) or for A/B testing.
   */
  object ForceOff : Http3Preference() {
    override fun toString() = "Http3Preference.ForceOff"
  }

  /**
   * Force the interceptor to serve the request over HTTP/3, bypassing all discovery signals.
   * Fails the call if the origin does not actually speak H/3 on the target port — use the
   * [Current] preference (or just no tag) to let the interceptor auto-detect.
   *
   * @param portOverride Optional UDP port to connect to. Defaults to the port from the request's
   *   URL. Use this when an origin speaks H/3 on a non-standard UDP port but its HTTPS URL
   *   still refers to 443.
   */
  data class Force(
    val portOverride: Int? = null,
  ) : Http3Preference()

  companion object {
    /** Retrieves any [Http3Preference] tag on [request], or [Current] if none is set. */
    fun of(request: Request): Http3Preference =
      request.tag(Http3Preference::class.java) ?: Current
  }
}
