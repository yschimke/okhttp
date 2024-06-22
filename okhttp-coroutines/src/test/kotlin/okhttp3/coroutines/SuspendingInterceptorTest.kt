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
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import kotlin.test.assertFailsWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClientTestRule
import okhttp3.Request
import okhttp3.SuspendingInterceptor
import okio.IOException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

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

      client = client.newBuilder().addInterceptor(SuspendingInterceptor {
        it.proceedAsync(it.request())
      }).build()

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

      client = client.newBuilder().addNetworkInterceptor(SuspendingInterceptor {
        it.proceedAsync(it.request())
      }).build()

      val call = client.newCall(request)

      call.executeAsync().use {
        withContext(Dispatchers.IO) {
          assertThat(it.body.string()).isEqualTo("abc")
        }
      }
    }
  }

  @Test
  fun withExecute() {
    server.enqueue(MockResponse(body = "abc"))

    client = client.newBuilder().addInterceptor(SuspendingInterceptor {
      it.proceedAsync(it.request())
    }).build()

    val call = client.newCall(request)

    call.execute().use {
      assertThat(it.body.string()).isEqualTo("abc")
    }
  }

  @Disabled("TODO work this out")
  @Test
  fun failsOnBlockingCall() {
    runTest {
      client = client.newBuilder().addInterceptor(SuspendingInterceptor {
        it.proceed(it.request())
      }).build()

      val call = client.newCall(request)

      val failure = assertFailsWith<IOException> {
        call.executeAsync()
      }
//      assertThat(failure.cause).isNotNull().isInstanceOf(IllegalStateException::class)
    }
  }
}
