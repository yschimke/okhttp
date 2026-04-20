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

import java.net.InetAddress
import okio.ByteString

/**
 * A DNS HTTPS service record (RFC 9460), or a rough cross-platform approximation of one.
 *
 * An HTTPS record tells a client — in a single DNS round-trip — *which* application protocols an
 * origin speaks (`"h3"`, `"h2"`, ...), *where* to send them (alias targets, alternate ports,
 * pre-resolved address hints), and, in future, the ECH config blob needed for Encrypted Client
 * Hello. This is the clean path to HTTP/3 discovery without the Alt-Svc dance.
 *
 * For the quiche4j transport today we only look at [alpnIds]; see `PLAN.md` for the roadmap
 * covering `port`, `ipAddressHints`, and `echConfigList`.
 */
data class HttpsServiceRecord(
  val priority: Int,
  val targetName: String,
  val port: Int?,
  val alpnIds: List<String>,
  val ipAddressHints: List<InetAddress>,
  val echConfigList: ByteString?,
) {
  /** Convenience: does this record advertise HTTP/3 (`"h3"` in ALPN)? */
  val supportsHttp3: Boolean
    get() = alpnIds.any { it.equals("h3", ignoreCase = true) }
}
