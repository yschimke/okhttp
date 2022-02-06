/*
 * Copyright (C) 2019 Square, Inc.
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

import java.security.Security
import javax.net.ssl.SSLSocket
import okhttp3.Call
import okhttp3.Connection
import okhttp3.ConnectionSpec
import okhttp3.DelegatingSSLSocketFactory
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.internal.ech.DnsJavaDns
import okhttp3.tls.HandshakeCertificates
import org.conscrypt.Conscrypt
import org.junit.jupiter.api.Test
import org.xbill.DNS.DohResolver
import org.xbill.DNS.HTTPSRecord
import org.xbill.DNS.Resolver
import org.xbill.DNS.SVCBBase
import org.xbill.DNS.lookup.LookupSession

/**
 * Run with "./gradlew :android-test:connectedCheck" and make sure ANDROID_SDK_ROOT is set.
 */
class EchTest() {
  @Test
  fun testDns() {
    Security.insertProviderAt(Conscrypt.newProviderBuilder().build(), 1)

    // val r: Resolver = SimpleResolver("1.1.1.1")
    val r: Resolver = DohResolver("https://cloudflare-dns.com/dns-query")
    val s = LookupSession.defaultBuilder().resolver(r).build()

    val dns = DnsJavaDns(s)

    val certs = HandshakeCertificates.Builder().addPlatformTrustedCertificates().build()

    val sslf = certs.sslSocketFactory()
    val tm = certs.trustManager

    val sslf2 = object : DelegatingSSLSocketFactory(sslf) {
      override fun configureSocket(sslSocket: SSLSocket): SSLSocket {
        val echConfig = dns.httpsRecords.values.firstOrNull()

        if (echConfig == null) {
          println("Unable to activate ECH for ${sslSocket.inetAddress}")
        } else {
          val echConfigEchList = echConfig.getSvcParamValue(HTTPSRecord.ECH) as SVCBBase.ParameterEch?

          val data = echConfigEchList?.data

          if (data == null) {
            println("Unable to extract echConfigList from $echConfig")
          } else {
            Conscrypt.setCheckDnsForEch(sslSocket, false)
            Conscrypt.setUseEchGrease(sslSocket, false)
            Conscrypt.setEchConfigList(sslSocket, data)
          }
        }

        return sslSocket
      }
    }

    val client = OkHttpClient().newBuilder()
      .dns(dns)
      .connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS))
      .eventListener(object : EventListener() {
        override fun connectionAcquired(call: Call, connection: Connection) {
          val sslSocket = connection.socket() as SSLSocket
          println("ECH was active " + Conscrypt.echAccepted(sslSocket))
        }
      })
      .sslSocketFactory(sslf2, tm)
      .build()

    val request = Request.Builder().url("https://crypto.cloudflare.com/cdn-cgi/trace").build()
    client.newCall(request).execute().use { response ->
      println(response.code)
      println(response.body!!.string())
    }
  }
}
