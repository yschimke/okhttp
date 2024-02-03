package okhttp3.containers

import assertk.assertThat
import assertk.assertions.contains
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Proxy.Type.HTTP
import java.net.Proxy.Type.SOCKS
import java.net.Socket
import java.net.SocketAddress
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLSocket
import okhttp3.Authenticator
import okhttp3.DelegatingSSLSocket
import okhttp3.DelegatingSocketFactory
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.containers.BasicMockServerTest.Companion.MOCKSERVER_IMAGE
import okhttp3.containers.BasicMockServerTest.Companion.trustMockServer
import okhttp3.internal.platform.Platform
import okhttp3.tls.HandshakeCertificates
import org.junit.jupiter.api.Test
import org.mockserver.client.MockServerClient
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.MockServerContainer
import org.testcontainers.containers.Network
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

@Testcontainers
class SecureWebProxyTest {
  private lateinit var token: String
  val network: Network = Network.newNetwork()

  val tokenMatcher = ".*token=(\\S+).*".toRegex(RegexOption.MULTILINE)

  @Container
  val secureWebProxy =
    GenericContainer(SOCRATEX)
      .withNetwork(network)
      .withExposedPorts(443)
      .withCommand("--domain=localhost --token=1234 --user=admin --password=password")
      .withLogConsumer {
        println(it.utf8StringWithoutLineEnding)

        if (it.utf8StringWithoutLineEnding.contains("token=")) {
          token = it.utf8StringWithoutLineEnding.replace(tokenMatcher, "$1").trim()
          println("token '$token'")
        }
      }

  @Test
  fun testLocal() {
      val clientCertificates = HandshakeCertificates.Builder()
        .addPlatformTrustedCertificates()
        .addInsecureHost("localhost")
        .addInsecureHost("httpbin.org")
        .addInsecureHost("kubernetes.docker.internal")
        .build()

    val sslSocketFactory = clientCertificates.sslSocketFactory()
    val protocols = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
    val client =
        OkHttpClient.Builder()
          .protocols(protocols)
        .sslSocketFactory(sslSocketFactory, clientCertificates.trustManager)
          .proxy(Proxy(HTTP, InetSocketAddress(secureWebProxy.host, secureWebProxy.firstMappedPort)))
          .socketFactory(object : DelegatingSocketFactory(sslSocketFactory) {
            override fun configureSocket(socket: Socket): Socket {
              val sslSocket = socket as SSLSocket

              val parameters = sslSocket.sslParameters
              val sni = parameters.serverNames
              parameters.serverNames = mutableListOf<SNIServerName>(SNIHostName("localhost"))
              parameters.applicationProtocols = Platform.alpnProtocolNames(protocols).toTypedArray()
              sslSocket.sslParameters = parameters

              return object : DelegatingSSLSocket(socket) {
                override fun connect(remoteAddr: SocketAddress, timeout: Int) {
                  super.connect(remoteAddr, timeout).also {
                    println("connect " + this.remoteSocketAddress + " " + delegate?.handshakeApplicationProtocol)
                    println("getSupportedProtocols " + supportedProtocols.toList())
                  }
                }
              }
            }
          })
          .proxyAuthenticator { _, response ->
            response.request.newBuilder()
              .header("Proxy-Authorization", token)
              .build()
          }
          .build()

      val response =
        client.newCall(
          Request("https://httpbin.org/get".toHttpUrl()),
        ).execute()

      assertThat(response.body.string()).contains("Peter the person")
  }

  companion object {
    val SOCRATEX: DockerImageName =
      DockerImageName
        .parse("leask/socratex")
        .withTag("latest")
  }
}
