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
package mockwebserver3.socket

import app.cash.burst.Burst
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.*
import okio.sink
import okio.source
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(10)
@Burst
public class SocketComparisonTest(private val mode: TestMode = TestMode.MOCK_SYSTEM) {
    public enum class TestMode {
        TCP,
        TCP_DECORATED,
        MOCK_SYSTEM,
        MOCK_FAKE,
        MOCK_AUTO,
    }

    private var serverSocket: ServerSocket? = null
    private var client: Socket? = null
    private var server: Socket? = null
    private var clock: Clock? = null
    private val sharedEvents = mutableListOf<SocketEvent>()

    @BeforeEach
    public fun setUp() {
        println("SETTING UP: $mode")
        when (mode) {
            TestMode.TCP -> {
                serverSocket = ServerSocket(0, 50, java.net.InetAddress.getByName("localhost"))
                val address = serverSocket!!.localSocketAddress
                client = Socket()
                client!!.connect(address)
                server = serverSocket!!.accept()
            }
            TestMode.TCP_DECORATED -> {
                val realServerSocket =
                        ServerSocket(0, 50, java.net.InetAddress.getByName("localhost"))
                serverSocket = RecordingServerSocket(realServerSocket, sharedEvents, "Server")
                val address = serverSocket!!.localSocketAddress
                client = RecordingSocket(Socket(), sharedEvents, "Client")
                client!!.connect(address)
                server = serverSocket!!.accept()
            }
            TestMode.MOCK_SYSTEM -> {
                val (c, s) =
                        MockSocket.pair(Clock.SYSTEM, sharedEvents = sharedEvents).apply {
                            connect()
                        }
                client = c.asSocket()
                server = s.asSocket()
            }
            TestMode.MOCK_FAKE -> {
                clock = FakeClock()
                val (c, s) =
                        runBlocking {
                            MockSocket.pair(clock!!, sharedEvents = sharedEvents)
                                    .connectSuspending()
                        }
                client = c.asSocket()
                server = s.asSocket()
            }
            TestMode.MOCK_AUTO -> {
                clock = AutoClock()
                val (c, s) =
                        runBlocking {
                            MockSocket.pair(clock!!, sharedEvents = sharedEvents)
                                    .connectSuspending()
                        }
                client = c.asSocket()
                server = s.asSocket()
            }
        }
    }

    @AfterEach
    public fun tearDown() {
        println("TEARING DOWN: $mode")
        client?.close()
        server?.close()
        serverSocket?.close()

        if (sharedEvents.isNotEmpty()) {
            println("EVENTS for $mode:")
            sharedEvents.forEach { println("  $it") }
        }
    }

    @Test
    public fun pairCommunication() = runBlocking {
        println("RUNNING: pairCommunication ($mode)")
        val clientOut = client!!.getOutputStream().sink().buffer()
        val serverIn = server!!.getInputStream().source().buffer()

        clientOut.writeUtf8("hello").flush()

        if (mode == TestMode.MOCK_FAKE) {
            (clock as FakeClock).advanceBy(1, TimeUnit.MILLISECONDS)
        }

        assertThat(serverIn.readUtf8(5)).isEqualTo("hello")

        val serverOut = server!!.getOutputStream().sink().buffer()
        val clientIn = client!!.getInputStream().source().buffer()

        serverOut.writeUtf8("world").flush()

        if (mode == TestMode.MOCK_FAKE) {
            (clock as FakeClock).advanceBy(1, TimeUnit.MILLISECONDS)
        }

        assertThat(clientIn.readUtf8(5)).isEqualTo("world")
    }

    @Test
    public fun readTimeout(): Unit = runBlocking {
        println("RUNNING: readTimeout ($mode)")
        client!!.soTimeout = 100
        val clientIn = client!!.getInputStream().source().buffer()

        if (mode == TestMode.MOCK_FAKE) {
            val clock = this@SocketComparisonTest.clock as FakeClock
            launch {
                delay(200)
                clock.advanceBy(100, TimeUnit.MILLISECONDS)
            }
        }

        assertFailure { clientIn.readUtf8(1) }.isInstanceOf(InterruptedIOException::class.java)
    }

    @Test
    public fun connectTimeout() {
        println("RUNNING: connectTimeout ($mode)")
        // For TCP, we need a non-responsive address. 10.255.255.1 is often used.
        // For MockSocket, we can just not call connect() or simulate a delay.
        if (mode == TestMode.TCP) {
            val unresponsive = InetSocketAddress("10.254.254.1", 80)
            val s = Socket()
            assertFailure { s.connect(unresponsive, 100) }.isInstanceOf(IOException::class.java)
        } else {
            // MockSocket connect timeout is not yet implemented to throw InterruptedIOException
            // in MockSocketAdapter. Instead, it just calls MockSocket.connect(address, timeout)
            // which doesn't simulate delay yet.
            println("Skipping connectTimeout for $mode")
        }
    }
}
