/*
 * Copyright (C) 2026 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package okhttp.android.test.quiche4j

import android.util.Log
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import java.security.cert.X509Certificate
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.quiche4j.AndroidHttpsServiceRecordResolver
import okhttp3.quiche4j.Http3Preference
import okhttp3.quiche4j.HttpsAware
import okhttp3.quiche4j.HttpsAwareDns
import okhttp3.quiche4j.Quiche4jInterceptor
import org.junit.jupiter.api.Test

/**
 * Instrumentation tests for the quiche4j HTTP/3 interceptor on Android. Requires:
 *   * a device or emulator with network access to cloudflare-quic.com,
 *   * a native lib for the device's ABI packaged into the quiche4j-jni JAR via cargo-ndk
 *     (configured by `-PandroidAbis=arm64-v8a,x86_64` when building the fork).
 */
class Quiche4jAndroidTest {
  private val tag = "Quiche4jAndroidTest"

  @Test
  fun h3FetchAgainstCloudflareQuic() {
    val interceptor = Quiche4jInterceptor.Builder().build()
    val client =
      OkHttpClient
        .Builder()
        .addInterceptor(interceptor)
        .build()
    val request = Request.Builder().url("https://cloudflare-quic.com/").build()
    client.newCall(request).execute().use { response ->
      Log.i(tag, "protocol=${response.protocol} code=${response.code}")
      assertThat(response.protocol).isEqualTo(Protocol.HTTP_3)
      assertThat(response.code).isEqualTo(200)
      val handshake = response.handshake!!
      val leaf = handshake.peerCertificates.firstOrNull() as X509Certificate?
      val subject = leaf?.subjectX500Principal?.name.orEmpty()
      Log.i(tag, "peer leaf subject=$subject chain.size=${handshake.peerCertificates.size}")
      assertThat(handshake.peerCertificates).isNotEmpty()
      assertThat(subject.lowercase()).contains("cloudflare")
    }
  }

  @Test
  fun httpsRecordDiscoveryRoutesOverQuiche4j() {
    // Requires API 29+ — skip silently on older devices so the test module still runs there.
    if (android.os.Build.VERSION.SDK_INT < 29) return
    val dns =
      HttpsAwareDns(
        resolver = AndroidHttpsServiceRecordResolver(),
        httpsLookupTimeoutMillis = 5_000,
      )
    val interceptor = Quiche4jInterceptor.Builder().build()
    val client =
      OkHttpClient
        .Builder()
        .dns(dns)
        .addInterceptor(interceptor)
        .build()
    val request = Request.Builder().url("https://cloudflare-quic.com/").build()
    client.newCall(request).execute().use { response ->
      val record = (dns as HttpsAware).getHttpsServiceRecord("cloudflare-quic.com")
      Log.i(tag, "discovery record alpn=${record?.alpnIds} protocol=${response.protocol}")
      assertThat(record).isNotNull()
      assertThat(record!!.alpnIds).contains("h3")
      assertThat(response.protocol).isEqualTo(Protocol.HTTP_3)
    }
  }

  @Test
  fun explicitForcePreferenceUsesQuiche4j() {
    val interceptor = Quiche4jInterceptor.Builder().build()
    val client =
      OkHttpClient
        .Builder()
        .addInterceptor(interceptor)
        .build()
    val request =
      Request
        .Builder()
        .url("https://cloudflare-quic.com/")
        .tag<Http3Preference>(Http3Preference.Force())
        .build()
    client.newCall(request).execute().use { response ->
      Log.i(tag, "forced protocol=${response.protocol} code=${response.code}")
      assertThat(response.protocol).isEqualTo(Protocol.HTTP_3)
      assertThat(response.code).isEqualTo(200)
    }
  }
}
