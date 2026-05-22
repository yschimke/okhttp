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

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import java.io.IOException
import java.net.InetAddress
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.test.assertFailsWith
import okhttp3.Dns
import okhttp3.EchAware
import okhttp3.ech.EchConfig
import okhttp3.ech.EchMode
import org.junit.jupiter.api.Test

/**
 * Exercises [ConscryptEchModeConfiguration]'s policy without touching a real Conscrypt build.
 *
 * The reflective binding to `Conscrypt.setEchConfigList(SSLSocket, byte[])` is best-effort: when
 * the linked Conscrypt does not expose ECH the helper degrades to a no-op. These tests focus on
 * the inputs around the binding (mode handling, DNS lookup, error surfaces) since the binding
 * itself can only be exercised end-to-end with the DEfO Conscrypt fork (see samples/jvm-ech).
 */
class ConscryptEchModeConfigurationTest {
  // A real (but unconnected) SSLSocket so we exercise the policy code without mocks. None of
  // these tests reach the reflective Conscrypt call so no TLS handshake actually happens.
  private val sslSocket: SSLSocket by lazy {
    val context = SSLContext.getInstance("TLS").apply { init(null, null, null) }
    context.socketFactory.createSocket() as SSLSocket
  }

  @Test
  fun appliesNoEchWhenDnsHasNone() {
    val config = ConscryptEchModeConfiguration()
    val applied = config.applyEch(sslSocket, EchMode.Opportunistic, "example.com", emptyDns)
    assertThat(applied).isNull()
  }

  @Test
  fun failsClosedWhenRequiredEchUnavailable() {
    val config = ConscryptEchModeConfiguration()
    assertFailsWith<IOException> {
      config.applyEch(sslSocket, EchMode.FailClosed, "example.com", emptyDns)
    }
  }

  @Test
  fun usesDefaultModeForUnknownHost() {
    val opportunistic = ConscryptEchModeConfiguration(defaultMode = EchMode.Opportunistic)
    assertThat(opportunistic.echMode("anywhere")).isEqualTo(EchMode.Opportunistic)

    val failClosed = ConscryptEchModeConfiguration(defaultMode = EchMode.FailClosed)
    assertThat(failClosed.echMode("anywhere")).isEqualTo(EchMode.FailClosed)
  }

  @Test
  fun classifiesEchSpecificSslErrors() {
    val config = ConscryptEchModeConfiguration()
    assertThat(config.isEchConfigError(javax.net.ssl.SSLException("ECH rejected"))).isEqualTo(true)
    assertThat(config.isEchConfigError(javax.net.ssl.SSLException("ech_required from server")))
      .isEqualTo(true)
    assertThat(config.isEchConfigError(javax.net.ssl.SSLException("Connection reset")))
      .isEqualTo(false)
  }

  private val emptyDns: Dns =
    object : Dns, EchAware {
      override fun lookup(hostname: String): List<InetAddress> = listOf()

      override fun getEchConfig(host: String): EchConfig? = null
    }
}
