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
package okhttp3.internal.connection

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isSameAs
import assertk.assertions.isTrue
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import javax.net.SocketFactory
import okhttp3.Address
import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.Handshake
import okhttp3.Http3Header
import okhttp3.Http3Session
import okhttp3.Http3Stream
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Route
import okhttp3.TlsVersion
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.http3.Http3ExchangeCodec
import org.junit.jupiter.api.Test

class Http3RealConnectionTest {
  private val client = OkHttpClient()
  private val listener = ConnectionListener.NONE

  @Test fun `newCodec returns Http3ExchangeCodec bound to this connection`() {
    val session = FakeHttp3Session()
    val connection = newConnection(session)

    val codec = connection.newCodec(client, chain = fakeChain(client))

    assertThat(codec).isInstanceOf(Http3ExchangeCodec::class.java)
    assertThat(codec.carrier).isSameAs(connection)
  }

  @Test fun `protocol is HTTP_3 regardless of session state`() {
    val connection = newConnection(FakeHttp3Session())
    assertThat(connection.protocol()).isEqualTo(Protocol.HTTP_3)
  }

  @Test fun `route and handshake delegate to session`() {
    val session = FakeHttp3Session()
    val connection = newConnection(session)

    assertThat(connection.route()).isSameAs(session.route)
    assertThat(connection.handshake()).isSameAs(session.handshake)
  }

  @Test fun `isMultiplexed is always true`() {
    val connection = newConnection(FakeHttp3Session())
    assertThat(connection.isMultiplexed).isTrue()
  }

  @Test fun `isHealthy reflects the underlying session`() {
    val session = FakeHttp3Session()
    val connection = newConnection(session)

    assertThat(connection.isHealthy(doExtensiveChecks = false)).isTrue()

    session.isHealthy = false
    assertThat(connection.isHealthy(doExtensiveChecks = false)).isFalse()
  }

  @Test fun `isHealthy is false once noNewExchanges is set`() {
    val connection = newConnection(FakeHttp3Session())
    assertThat(connection.isHealthy(doExtensiveChecks = false)).isTrue()

    connection.noNewExchanges()
    assertThat(connection.isHealthy(doExtensiveChecks = false)).isFalse()
  }

  @Test fun `trackFailure sets noNewExchanges (any failure retires the connection)`() {
    val connection = newConnection(FakeHttp3Session())
    assertThat(connection.noNewExchanges).isFalse()

    connection.trackFailure(call = fakeCall(client), e = IOException("boom"))

    assertThat(connection.noNewExchanges).isTrue()
  }

  @Test fun `trackFailure is idempotent (second call is a no-op)`() {
    val recording = RecordingConnectionListener()
    val connection = newConnection(FakeHttp3Session(), listener = recording)

    connection.trackFailure(call = fakeCall(client), e = IOException("boom"))
    connection.trackFailure(call = fakeCall(client), e = IOException("boom again"))

    assertThat(recording.noNewExchangesFirings).isEqualTo(1)
  }

  @Test fun `cancel closes the session`() {
    val session = FakeHttp3Session()
    val connection = newConnection(session)

    connection.cancel()

    assertThat(session.closed).isTrue()
  }

  @Test fun `allocationLimit seeds from session maxConcurrentStreams`() {
    val session = FakeHttp3Session(maxConcurrentStreams = 42)
    val connection = newConnection(session)

    assertThat(connection.allocationLimit).isEqualTo(42)
  }

  @Test fun `isEligible refuses when allocationLimit is reached`() {
    val session = FakeHttp3Session(maxConcurrentStreams = 1)
    val connection = newConnection(session)
    // Simulate one in-flight call.
    connection.calls += java.lang.ref.WeakReference(fakeCall(client))

    assertThat(connection.isEligible(address = session.route.address, routes = null)).isFalse()
  }

  @Test fun `isEligible refuses after noCoalescedConnections on a different-host address`() {
    val session = FakeHttp3Session()
    val connection = newConnection(session)
    connection.noCoalescedConnections() // simulate 421 Misdirected Request

    // Same host is still allowed — noCoalescedConnections only disables coalescing
    // for other hostnames.
    assertThat(connection.isEligible(address = session.route.address, routes = null)).isTrue()

    // A different host would need coalescing; noCoalescedConnections blocks it.
    val otherHostAddress =
      Address(
        uriHost = "other.example.com",
        uriPort = 443,
        dns = Dns.SYSTEM,
        socketFactory = SocketFactory.getDefault(),
        sslSocketFactory = null,
        hostnameVerifier = null,
        certificatePinner = null,
        proxyAuthenticator = Authenticator.NONE,
        proxy = null,
        protocols = listOf(Protocol.HTTP_3, Protocol.HTTP_1_1),
        connectionSpecs = listOf(ConnectionSpec.MODERN_TLS),
        proxySelector = java.net.ProxySelector.getDefault(),
      )
    assertThat(connection.isEligible(address = otherHostAddress, routes = null)).isFalse()
  }

  private fun newConnection(
    session: Http3Session,
    listener: ConnectionListener = this.listener,
  ): Http3RealConnection =
    Http3RealConnection(
      taskRunner = TaskRunner.INSTANCE,
      connectionPool = ConnectionPool().delegate,
      session = session,
      connectionListener = listener,
    )

  private class FakeHttp3Session(
    override val maxConcurrentStreams: Int = 100,
  ) : Http3Session {
    override val route: Route =
      Route(
        address =
          Address(
            uriHost = "example.com",
            uriPort = 443,
            dns = Dns.SYSTEM,
            socketFactory = SocketFactory.getDefault(),
            sslSocketFactory = null,
            hostnameVerifier = null,
            certificatePinner = null,
            proxyAuthenticator = Authenticator.NONE,
            proxy = null,
            protocols = listOf(Protocol.HTTP_3, Protocol.HTTP_1_1),
            connectionSpecs = listOf(ConnectionSpec.MODERN_TLS),
            proxySelector = java.net.ProxySelector.getDefault(),
          ),
        proxy = Proxy.NO_PROXY,
        socketAddress = InetSocketAddress.createUnresolved("example.com", 443),
      )

    override val handshake: Handshake =
      Handshake.get(
        tlsVersion = TlsVersion.TLS_1_3,
        cipherSuite = okhttp3.CipherSuite.TLS_AES_128_GCM_SHA256,
        peerCertificates = emptyList(),
        localCertificates = emptyList(),
      )

    @Volatile override var isHealthy: Boolean = true

    @Volatile var closed: Boolean = false

    override fun newStream(
      headers: List<Http3Header>,
      hasRequestBody: Boolean,
    ): Http3Stream = throw UnsupportedOperationException("not needed in these tests")

    override fun flush() {}

    override fun close() {
      closed = true
    }
  }

  private class RecordingConnectionListener : ConnectionListener() {
    @Volatile var noNewExchangesFirings: Int = 0

    override fun noNewExchanges(connection: okhttp3.Connection) {
      noNewExchangesFirings++
    }
  }

  private fun fakeCall(client: OkHttpClient): RealCall =
    RealCall(
      client = client,
      originalRequest = okhttp3.Request.Builder().url("https://example.com/").build(),
      forWebSocket = false,
    )

  private fun fakeChain(client: OkHttpClient): okhttp3.internal.http.RealInterceptorChain =
    okhttp3.internal.http.RealInterceptorChain(
      call = fakeCall(client),
      interceptors = emptyList<okhttp3.Interceptor>(),
      index = 0,
      exchange = null,
      request = okhttp3.Request.Builder().url("https://example.com/").build(),
      client = client,
    )
}
