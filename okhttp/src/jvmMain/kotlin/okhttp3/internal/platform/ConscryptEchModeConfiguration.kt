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
package okhttp3.internal.platform

import java.io.IOException
import java.lang.reflect.Method
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import okhttp3.Dns
import okhttp3.EchAware
import okhttp3.ech.EchConfig
import okhttp3.ech.EchMode
import okhttp3.ech.EchModeConfiguration
import okio.ByteString

/**
 * [EchModeConfiguration] backed by Conscrypt's ECH-enabled fork (the DEfO Conscrypt build).
 *
 * Stock `org.conscrypt:conscrypt-openjdk-uber` does not expose ECH APIs; this implementation
 * reflectively binds to `Conscrypt.setEchConfigList(SSLSocket, byte[])` so the same OkHttp build
 * works against both stock and ECH-enabled Conscrypts. When the method is absent the
 * implementation treats ECH as unsupported and behaves like [EchModeConfiguration.Unspecified].
 *
 * The ECH bytes themselves come from a [Dns] that implements [EchAware] — typically
 * `EchAwareDnsOverHttps` from `okhttp-dnsoverhttps`, which surfaces the `ech` SvcParam from
 * an RFC 9460 HTTPS DNS record.
 */
internal class ConscryptEchModeConfiguration(
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

    if (echConfig != null && setEchConfigList != null) {
      try {
        setEchConfigList!!.invoke(null, sslSocket, echConfig.config.toByteArray())
        return echConfig
      } catch (e: ReflectiveOperationException) {
        if (echMode.require) {
          throw IOException("Failed to apply ECH config for $host", e)
        }
        return null
      }
    }

    if (echMode.require) {
      throw IOException("Unable to apply required ECH config for $host")
    }
    return null
  }

  /**
   * Returns true when [e] indicates the server rejected the ECH config and supplied a `retry_config`.
   *
   * Conscrypt surfaces this via an `SSLHandshakeException` whose message contains ECH-related text;
   * we match on substring since the exact exception type varies across forks. Callers should also
   * consult `Conscrypt.getEchConfigList(socket)` after the failed handshake to obtain the new
   * config for a follow-up attempt.
   */
  override fun isEchConfigError(e: SSLException): Boolean {
    val msg = e.message ?: return false
    return msg.contains("ECH", ignoreCase = false) || msg.contains("ech_required", ignoreCase = true)
  }

  companion object {
    /** Reflective handle to `org.conscrypt.Conscrypt.setEchConfigList(SSLSocket, byte[])`. */
    private val setEchConfigList: Method? =
      try {
        val conscryptClass = Class.forName("org.conscrypt.Conscrypt")
        conscryptClass.getMethod("setEchConfigList", SSLSocket::class.java, ByteArray::class.java)
      } catch (_: ClassNotFoundException) {
        null
      } catch (_: NoSuchMethodException) {
        null
      }

    /** True when the linked Conscrypt build exposes the ECH API (i.e. it's the DEfO fork). */
    val isSupported: Boolean get() = setEchConfigList != null

    fun simpleEchConfig(bytes: ByteString): EchConfig =
      object : EchConfig {
        override val config: ByteString = bytes
      }
  }
}
