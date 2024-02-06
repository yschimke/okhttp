package okhttp3.containers

import assertk.assertThat
import assertk.assertions.contains
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import okhttp3.Authenticator
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.containers.BasicMockServerTest.Companion.MOCKSERVER_IMAGE
import okio.buffer
import okio.source
import org.junit.jupiter.api.Test
import org.mockserver.client.MockServerClient
import org.mockserver.configuration.Configuration
import org.mockserver.model.HttpRequest.request
import org.mockserver.model.HttpResponse.response
import org.mockserver.proxyconfiguration.ProxyConfiguration
import org.testcontainers.containers.MockServerContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.MountableFile

@Testcontainers
class AuthenticatedProxyTest {
  @Container
  val mockServer: MockServerContainer =
    MockServerContainer(MOCKSERVER_IMAGE)
      .withNetworkAliases("mockserver")
      .withCopyToContainer(
        MountableFile.forClasspathResource("/proxy-config.properties"),
        "/config/mockserver.properties"
      )
      .withLogConsumer {
        println(it.utf8StringWithoutLineEnding)
      }

  @Test
  fun testOkHttpProxied() {
    testRequest {
      val client =
        OkHttpClient.Builder()
          .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(it.remoteAddress().hostString, mockServer.getMappedPort(1090))))
          .proxyAuthenticator(Authenticator.JAVA_NET_AUTHENTICATOR)
          .build()

      val response =
        client.newCall(
          Request((mockServer.endpoint + "/person?name=peter").toHttpUrl()),
        ).execute()

      assertThat(response.body.string()).contains("Peter the person")
    }
  }

  @Test
  fun testUrlConnectionPlaintextProxied() {
    System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "")
    System.setProperty("http.proxyUser", "admin")
    System.setProperty("http.proxyPassword", "password1")

    testRequest {
      val proxy =
        Proxy(
          Proxy.Type.HTTP,
          it.remoteAddress(),
        )

      val url = URI(mockServer.endpoint + "/person?name=peter").toURL()

      val connection = url.openConnection(proxy) as HttpURLConnection

      assertThat(connection.inputStream.source().buffer().readUtf8()).contains("Peter the person")
    }
  }

  private fun testRequest(function: (MockServerClient) -> Unit) {
    MockServerClient(mockServer.host, mockServer.serverPort).use { mockServerClient ->
      mockServerClient.openUI()

      val request =
        request().withPath("/person")
          .withQueryStringParameter("name", "peter")

      mockServerClient
        .`when`(
          request,
        )
        .respond(response().withBody("Peter the person!"))

      function(mockServerClient)
      Thread.sleep(3000)
      function(mockServerClient)
    }
  }
}
