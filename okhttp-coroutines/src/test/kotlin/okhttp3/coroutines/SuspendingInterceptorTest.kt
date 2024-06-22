/*
 * Copyright (c) 2022 Square, Inc.
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
 *
 */
@file:OptIn(ExperimentalCoroutinesApi::class)

package okhttp3.coroutines

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketPolicy.DisconnectAfterRequest
import okhttp3.Callback
import okhttp3.FailingCall
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClientTestRule
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.SuspendingInterceptor
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.fail

class SuspendingInterceptorTest {
  @RegisterExtension
  val clientTestRule = OkHttpClientTestRule()

  private var client = clientTestRule.newClientBuilder().build()

  private lateinit var server: MockWebServer

  val request by lazy { Request(server.url("/")) }

  @BeforeEach
  fun setup(server: MockWebServer) {
    this.server = server
  }

  @Test
  fun applicationInterceptor() {
    runTest {
      server.enqueue(MockResponse(body = "abc"))

      client = client.newBuilder().addInterceptor().build()

      val call = client.newCall(request)

      call.executeAsync().use {
        withContext(Dispatchers.IO) {
          assertThat(it.body.string()).isEqualTo("abc")
        }
      }
    }
  }

  @Test
  fun networkInterceptor() {
    runTest {
      server.enqueue(MockResponse(body = "abc"))

      client = client.newBuilder().addNetworkInterceptor().build()

      val call = client.newCall(request)

      call.executeAsync().use {
        withContext(Dispatchers.IO) {
          assertThat(it.body.string()).isEqualTo("abc")
        }
      }
    }
  }

  @Test
  fun failsOnBlockingCall() {
    runTest {
      server.enqueue(MockResponse(body = "abc"))

      client = client.newBuilder().addInterceptor().build()

      val call = client.newCall(request)

      call.executeAsync().use {
        withContext(Dispatchers.IO) {
          assertThat(it.body.string()).isEqualTo("abc")
        }
      }
    }
  }

  object NoopSuspendingInterceptor: SuspendingInterceptor() {
    override suspend fun interceptAsync(chain: Interceptor.Chain): Response {
      return chain.proceedAsync(chain.request())
    }
  }

  object BadSuspendingInterceptor: SuspendingInterceptor() {
    override suspend fun interceptAsync(chain: Interceptor.Chain): Response {
      return chain.proceed(chain.request())
    }
  }
}
