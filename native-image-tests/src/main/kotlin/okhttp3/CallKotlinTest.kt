/*
 * Copyright (C) 2013 Square, Inc.
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

import mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.tls.internal.TlsUtil.localhost
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.security.cert.X509Certificate

class CallKotlinTest {
  val server = MockWebServer()

  private var client = OkHttpClient()

  @Test
  fun legalToExecuteTwiceCloning() {
    server.enqueue(MockResponse().setBody("abc"))
    server.enqueue(MockResponse().setBody("def"))

    val request = Request.Builder()
        .url(server.url("/"))
        .build()

    val call = client.newCall(request)
    val response1 = call.execute()

    val cloned = call.clone()
    val response2 = cloned.execute()

    assertEquals("abc", response1.body!!.string())
    assertEquals("def", response2.body!!.string())
  }

  @Test
  fun testMockWebserverRequest() {
    enableTls()

    server.enqueue(MockResponse().setBody("abc"))

    val request = Request.Builder().url(server.url("/")).build()

    val response = client.newCall(request).execute()

    response.use {
      assertEquals(200, response.code)
      assertEquals("CN=localhost",
          (response.handshake!!.peerCertificates.single() as X509Certificate).subjectDN.name)
    }
  }

  private fun enableTls() {
    val handshakeCertificates = localhost()

    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager)
        .build()
    server.useHttps(handshakeCertificates.sslSocketFactory(), false)
  }
}
