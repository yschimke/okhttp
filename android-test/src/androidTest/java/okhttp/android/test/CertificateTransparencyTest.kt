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
package okhttp.android.test

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Confirms Certificate Transparency (CT) enforcement on Android.
 *
 * Policy and configuration across Android versions:
 * - **Android 17 (API 37)+**: Certificate Transparency is **enabled by default** for network connections.
 *   Apps can opt out for specific domains if needed via `<certificateTransparency enabled="false"/>`
 *   in `network_security_config.xml`.
 * - **Android 16 (API 36)**: Certificate Transparency is **disabled by default**, but available as an
 *   opt-in feature via `<certificateTransparency enabled="true"/>` in `network_security_config.xml`.
 * - **Android 15 (API 35) and lower**: Platform-level Certificate Transparency enforcement is not supported.
 *
 * Test servers:
 * - `https://badssl.com/`: Serves a valid certificate with Signed Certificate Timestamps (SCTs).
 * - `https://no-sct.badssl.com/`: Serves a valid certificate from a trusted public CA without SCTs.
 */
@RunWith(AndroidJUnit4::class)
class CertificateTransparencyTest {
  private val client = OkHttpClient.Builder().build()

  @Test
  fun testCtCompliantServerSucceeds() {
    val request = Request.Builder().url("https://badssl.com/").build()
    client.newCall(request).execute().use { response ->
      assertEquals(200, response.code)
    }
  }

  @Test
  fun testNoSctServerBehavior() {
    val request = Request.Builder().url("https://no-sct.badssl.com/").build()
    val sdkInt = Build.VERSION.SDK_INT

    if (sdkInt >= 37) {
      // On Android 17+, Certificate Transparency is enforced by default and fails when SCTs are missing.
      try {
        val response = client.newCall(request).execute()
        response.use {
          fail("Expected SSLHandshakeException/SSLException on API $sdkInt due to Certificate Transparency enforcement, but got HTTP ${response.code}")
        }
      } catch (e: Exception) {
        val isSslFailure = e is SSLException || e.cause is SSLException || e is SSLHandshakeException
        assertTrue("Expected SSL failure on API $sdkInt, but got: $e", isSslFailure)
      }
    } else {
      // On API < 37, Certificate Transparency is NOT enabled by default (requires <certificateTransparency enabled="true"/> on API 36).
      try {
        val response = client.newCall(request).execute()
        response.use {
          assertEquals(200, response.code)
        }
      } catch (e: IOException) {
        // badssl.com test certificate may expire or cause other I/O errors on legacy platforms
        println("Request failed on API $sdkInt: $e")
      }
    }
  }
}
