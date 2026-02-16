/*
 * Copyright (C) 2012 Square, Inc.
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

package mockwebserver3

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.net.InetAddress
import mockwebserver3.internal.DEFAULT_REQUEST_LINE_HTTP_1
import mockwebserver3.internal.MockWebServerSocket
import mockwebserver3.internal.RecordedRequest
import mockwebserver3.internal.decodeRequestLine
import mockwebserver3.socket.MockSocket
import okhttp3.Headers
import okhttp3.Headers.Companion.headersOf
import okio.ByteString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(30)
class RecordedRequestTest {
        private val headers: Headers = Headers.EMPTY

        private fun mockWebServerSocket(
                localAddress: InetAddress = InetAddress.getByName("127.0.0.1"),
                localPort: Int = 80,
                remoteAddress: InetAddress = localAddress,
                remotePort: Int = 1234
        ): MockWebServerSocket {
                val (client, server) = MockSocket.pair()
          client.pair(server)
          client.localAddress = localAddress
          client.localPort = localPort
          server.localAddress = remoteAddress
          server.localPort = remotePort
          // TODO these should be valid values
          client.onConnect("localhost", 8080)
          server.onConnect(null, 49152)
                return MockWebServerSocket(client.asSocket())
        }

        @Test
        fun testIPv4() {
                val socket = mockWebServerSocket()
                val request =
                        RecordedRequest(
                                DEFAULT_REQUEST_LINE_HTTP_1,
                                headers,
                                emptyList(),
                                0,
                                ByteString.EMPTY,
                                0,
                                0,
                                socket
                        )
                assertThat(request.url.toString()).isEqualTo("http://127.0.0.1/")
        }

        @Test
        fun testAuthorityForm() {
                val socket = mockWebServerSocket()
                val requestLine = decodeRequestLine("CONNECT example.com:8080 HTTP/1.1")
                val request =
                        RecordedRequest(
                                requestLine,
                                headers,
                                emptyList(),
                                0,
                                ByteString.EMPTY,
                                0,
                                0,
                                socket
                        )
                assertThat(request.target).isEqualTo("example.com:8080")
                assertThat(request.url.toString()).isEqualTo("http://example.com:8080/")
        }

        @Test
        fun testAbsoluteForm() {
                val socket = mockWebServerSocket()
                val requestLine =
                        decodeRequestLine("GET http://example.com:8080/index.html HTTP/1.1")
                val request =
                        RecordedRequest(
                                requestLine,
                                headers,
                                emptyList(),
                                0,
                                ByteString.EMPTY,
                                0,
                                0,
                                socket
                        )
                assertThat(request.target).isEqualTo("http://example.com:8080/index.html")
                assertThat(request.url.toString()).isEqualTo("http://example.com:8080/index.html")
        }

        @Test
        fun testAsteriskForm() {
                val socket = mockWebServerSocket()
                val requestLine = decodeRequestLine("OPTIONS * HTTP/1.1")
                val request =
                        RecordedRequest(
                                requestLine,
                                headers,
                                emptyList(),
                                0,
                                ByteString.EMPTY,
                                0,
                                0,
                                socket,
                        )
                assertThat(request.target).isEqualTo("*")
                assertThat(request.url.toString()).isEqualTo("http://127.0.0.1/")
        }

        @Test
        fun testIpv6() {
                val socket =
                        mockWebServerSocket(
                                localAddress =
                                        InetAddress.getByAddress(
                                                "::1",
                                                byteArrayOf(
                                                        0,
                                                        0,
                                                        0,
                                                        0,
                                                        0,
                                                        0,
                                                        0,
                                                        0,
                                                        0,
                                                        0,
                                                        0,
                                                        0,
                                                        0,
                                                        0,
                                                        0,
                                                        1
                                                )
                                        )
                        )
                val request =
                        RecordedRequest(
                                DEFAULT_REQUEST_LINE_HTTP_1,
                                headers,
                                emptyList(),
                                0,
                                ByteString.EMPTY,
                                0,
                                0,
                                socket
                        )
                assertThat(request.url.toString()).isEqualTo("http://[::1]/")
        }

        @Test
        fun testUsesLocal() {
                val socket = mockWebServerSocket()
                val request =
                        RecordedRequest(
                                DEFAULT_REQUEST_LINE_HTTP_1,
                                headers,
                                emptyList(),
                                0,
                                ByteString.EMPTY,
                                0,
                                0,
                                socket
                        )
                assertThat(request.url.toString()).isEqualTo("http://127.0.0.1/")
        }

        @Test
        fun testHostname() {
                val headers = headersOf("Host", "host-from-header.com")
                val socket =
                        mockWebServerSocket(
                                localAddress =
                                        InetAddress.getByAddress(
                                                "host-from-address.com",
                                                byteArrayOf(
                                                        0,
                                                        0,
                                                        0,
                                                        0,
                                                        0,
                                                        0,
                                                        0,
                                                        0,
                                                        0,
                                                        0,
                                                        0,
                                                        0,
                                                        0,
                                                        0,
                                                        0,
                                                        1
                                                )
                                        )
                        )
                val request =
                        RecordedRequest(
                                DEFAULT_REQUEST_LINE_HTTP_1,
                                headers,
                                emptyList(),
                                0,
                                ByteString.EMPTY,
                                0,
                                0,
                                socket
                        )
                assertThat(request.url.toString()).isEqualTo("http://host-from-header.com/")
        }
}
