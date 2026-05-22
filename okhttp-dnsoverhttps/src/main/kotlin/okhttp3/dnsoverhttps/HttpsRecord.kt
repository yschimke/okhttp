/*
 * Copyright (c) 2026 OkHttp Authors
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
package okhttp3.dnsoverhttps

import java.net.InetAddress
import okio.ByteString

/**
 * A decoded HTTPS resource record (RFC 9460 type 65). Carries the parameters relevant to
 * connection establishment, including IP hints, ALPN, port, and the raw `ech` SvcParam bytes.
 *
 * Unknown SvcParam keys are skipped.
 */
class HttpsRecord(
  val priority: Int,
  val target: String,
  val alpn: List<String>,
  val port: Int?,
  val ipv4Hints: List<InetAddress>,
  val ipv6Hints: List<InetAddress>,
  /** The raw `ech` SvcParam value (an ECHConfigList per RFC 9460 §6 / draft-ietf-tls-esni). */
  val ech: ByteString?,
)
