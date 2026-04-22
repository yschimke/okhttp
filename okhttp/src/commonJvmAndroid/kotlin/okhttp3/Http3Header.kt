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

import okio.ByteString
import okio.ByteString.Companion.encodeUtf8

/**
 * A single HTTP/3 header field: name/value pair (RFC 9114 §4.3). Pseudo-headers
 * (`:method`, `:path`, `:scheme`, `:authority`, `:status`) are passed as regular names
 * that start with a colon, matching the QPACK wire encoding.
 *
 * Used by the [Http3Engine] SPI to hand request headers to the transport and receive
 * response headers back. The public [Headers] type can't represent pseudo-headers so it
 * isn't a good fit for the transport boundary.
 *
 * HTTP/3 requires all header names to be lowercase. Keep that in mind if you construct
 * these directly; the codec does the lowercasing when converting a [Request] to a header
 * list before handing it to the engine.
 */
data class Http3Header(
  /** Name in case-insensitive ASCII, lowercase on the wire. */
  @JvmField val name: ByteString,
  /** Value in UTF-8 encoding. */
  @JvmField val value: ByteString,
) {
  constructor(name: String, value: String) : this(name.encodeUtf8(), value.encodeUtf8())

  constructor(name: ByteString, value: String) : this(name, value.encodeUtf8())

  override fun toString(): String = "${name.utf8()}: ${value.utf8()}"

  companion object {
    /** `:status` pseudo-header (response only, RFC 9114 §4.3.2). */
    @JvmField val RESPONSE_STATUS: ByteString = ":status".encodeUtf8()

    /** `:method` pseudo-header (request only, RFC 9114 §4.3.1). */
    @JvmField val TARGET_METHOD: ByteString = ":method".encodeUtf8()

    /** `:path` pseudo-header (request only). */
    @JvmField val TARGET_PATH: ByteString = ":path".encodeUtf8()

    /** `:scheme` pseudo-header (request only). */
    @JvmField val TARGET_SCHEME: ByteString = ":scheme".encodeUtf8()

    /** `:authority` pseudo-header (request only). */
    @JvmField val TARGET_AUTHORITY: ByteString = ":authority".encodeUtf8()
  }
}
