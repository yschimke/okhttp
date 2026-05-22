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
package okhttp3.sample.ech

import java.io.IOException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import okhttp3.Dns
import okhttp3.EchAware
import okhttp3.ech.EchConfig
import okhttp3.ech.EchMode
import okhttp3.ech.EchModeConfiguration
import org.conscrypt.Conscrypt

/**
 * [EchModeConfiguration] that applies an ECHConfigList to a Conscrypt [SSLSocket].
 *
 * Requires the DEfO Conscrypt fork (https://github.com/defo-project/conscrypt) on the classpath
 * — stock `org.conscrypt:conscrypt-openjdk-uber` does not declare `Conscrypt.setEchConfigList`
 * and this class would fail to link. Build DEfO Conscrypt locally and pin its version via
 * `-PdefoConscryptVersion=<your-build>` (see this module's README).
 *
 * No reflection: `Conscrypt.setEchConfigList(...)` is called directly. Linking this class
 * against stock Conscrypt is a compile-time error, which is the intended guard against
 * shipping with a TLS provider that silently strips ECH.
 *
 * The ECHConfigList itself is supplied by an [EchAware] DNS implementation
 * (typically `EchAwareDnsOverHttps` from `okhttp-dnsoverhttps`).
 */
class ConscryptEchModeConfiguration(
  private val defaultMode: EchMode = EchMode.Opportunistic,
) : EchModeConfiguration {
  override fun echMode(host: String): EchMode = defaultMode

  override fun applyEch(
    sslSocket: SSLSocket,
    echMode: EchMode,
    host: String,
    dns: Dns,
  ): EchConfig? {
    val echConfig = (dns as? EchAware)?.getEchConfig(host)

    if (echConfig != null) {
      Conscrypt.setEchConfigList(sslSocket, echConfig.config.toByteArray())
      return echConfig
    }

    if (echMode.require) {
      throw IOException("Unable to apply required ECH config for $host")
    }
    return null
  }

  /**
   * Reads back the server-supplied retry config after a failed handshake. Callers that want to
   * retry with a refreshed configuration should consult this and feed the bytes into a new
   * connection's [EchConfig].
   */
  fun retryConfig(sslSocket: SSLSocket): ByteArray? = Conscrypt.getEchConfigList(sslSocket)

  /** True if the handshake on [sslSocket] actually used ECH. */
  fun echAccepted(sslSocket: SSLSocket): Boolean = Conscrypt.echAccepted(sslSocket)

  override fun isEchConfigError(e: SSLException): Boolean {
    val msg = e.message ?: return false
    return msg.contains("ECH", ignoreCase = false) || msg.contains("ech_required", ignoreCase = true)
  }
}
