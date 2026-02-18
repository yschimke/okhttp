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
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isInstanceOf
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Random
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(5)
@Burst
public class MockSocketTest(private val recordMode: RecordMode = RecordMode.DISABLED) {
    @Test
    public fun pairCommunication(): Unit = runBlocking {
        val (client, server) = MockSocket.pair().connectSuspending()
        client.recordMode = recordMode
        server.recordMode = recordMode

        client.sink.buffer().writeUtf8("hello").flush()
        assertThat(server.source.buffer().readUtf8(5)).isEqualTo("hello")

        server.sink.buffer().writeUtf8("world").flush()
        assertThat(client.source.buffer().readUtf8(5)).isEqualTo("world")
    }

    @Test
    public fun readTimeout(): Unit = runBlocking {
        val clock = AutoClock()
        val (client, server) = MockSocket.pair(clock).connect()
        client.recordMode = recordMode

        client.source.timeout().timeout(100, TimeUnit.MILLISECONDS)

        assertFailure { client.source.buffer().readUtf8() }
                .isInstanceOf(InterruptedIOException::class.java)
    }

    @Test
    public fun faultInjectionRead(): Unit = runBlocking {
        val (client, server) = MockSocket.pair().connectSuspending()
        client.recordMode = recordMode

        client.faults =
                object : MockSocket.Faults {
                    override fun maybeThrowRead(clock: Clock) {
                        throw IOException("boom")
                    }
                }

        assertFailure { client.source.buffer().readUtf8() }.isInstanceOf(IOException::class.java)
    }

    @Test
    public fun latencySimulation(): Unit = runBlocking {
        val clock = FakeClock()
        val (client, server) =
                MockSocket.pair(
                                clock = clock,
                                profile =
                                        NetworkProfile(
                                                latencyNanos = TimeUnit.MILLISECONDS.toNanos(100)
                                        )
                        )
                        .connect()
        client.recordMode = recordMode
        server.recordMode = recordMode

        val clientSink = client.sink.buffer()
        val serverSource = server.source.buffer()

        clientSink.writeUtf8("hello").flush()

        // At t=0, server should not see data yet
        assertThat(clock.now).isEqualTo(0L)

        // Advance clock BEFORE reading to simulate time passing.
        clock.advanceBy(100, TimeUnit.MILLISECONDS)

        assertThat(serverSource.readUtf8(5)).isEqualTo("hello")
        assertThat(clock.now).isEqualTo(TimeUnit.MILLISECONDS.toNanos(100))
    }

    @Test
    public fun throughputSimulation(): Unit = runBlocking {
        val clock = FakeClock()
        val (client, server) =
                MockSocket.pair(
                                clock = clock,
                                profile = NetworkProfile(bytesPerSecond = 1000) // 1 KB/s
                        )
                        .connect()
        client.recordMode = recordMode
        server.recordMode = recordMode

        val clientSink = client.sink.buffer()
        val serverSource = server.source.buffer()

        clientSink.write(ByteArray(1000)).flush()

        assertThat(clock.now).isEqualTo(TimeUnit.SECONDS.toNanos(1))
        assertThat(serverSource.readByteArray(1000).size).isEqualTo(1000)
    }

    @Test
    public fun slowMobileProfile(): Unit = runBlocking {
        val clock = FakeClock()
        val (client, server) = MockSocket.slowMobile(clock).connect()
        client.recordMode = recordMode
        server.recordMode = recordMode

        client.sink.buffer().writeUtf8("a").flush()

        // 200ms latency and 100KB/s throughput.
        // 1 byte takes 10,000ns.
        assertThat(clock.now).isEqualTo(10_000L)

        clock.advanceBy(200, TimeUnit.MILLISECONDS)

        assertThat(server.source.buffer().readUtf8(1)).isEqualTo("a")
        assertThat(clock.now).isEqualTo(TimeUnit.MILLISECONDS.toNanos(200) + 10_000L)
    }

    /**
     * Verifies deterministic timing under real world jitter. We introduce random Thread.sleep
     * delays in the client and server threads, but we confirm that those delays have NO impact on
     * the FakeClock and the sequence/timestamps of recorded events.
     */
    @Test
    public fun deterministicPingPongWithJitter(): Unit = runBlocking {
        // Use AutoClock to automatically advance time when blocked waiting for I/O.
        val clock = AutoClock()
        // Use a profile with latency to force the clock to advance.
        val latencyNanos = TimeUnit.MILLISECONDS.toNanos(10)
        val profile = NetworkProfile(latencyNanos = latencyNanos)
        val (client, server) = MockSocket.pair(clock, profile).connect()
        client.name = "Client"
        server.name = "Server"
        client.recordMode = recordMode
        server.recordMode = recordMode

        val random = Random(42)

        val clientJob =
                launch(Dispatchers.IO) {
                    val sink = client.sink.buffer()
                    val source = client.source.buffer()
                    for (i in 1..5) {
                        // This sleep represents real-world jitter (e.g. GC, context switching).
                        // It should NOT advance the FakeClock.
                        Thread.sleep(random.nextInt(10).toLong())
                        sink.writeUtf8("Ping$i").flush()
                        assertThat(source.readUtf8(5)).isEqualTo("Pong$i")
                    }
                }

        val serverJob =
                launch(Dispatchers.IO) {
                    val sink = server.sink.buffer()
                    val source = server.source.buffer()
                    for (i in 1..5) {
                        assertThat(source.readUtf8(5)).isEqualTo("Ping$i")
                        Thread.sleep(random.nextInt(10).toLong())
                        sink.writeUtf8("Pong$i").flush()
                    }
                }

        clientJob.join()
        serverJob.join()

        // Confirm the Clock advanced.
        // 5 round trips. Each trip has 2 latency delays (Ping -> Server, Pong -> Client).
        // Total minimal latency = 5 * 2 * 10ms = 100ms.
        // Plus any potential transfer time (negligible for small payloads).
        assertThat(clock.now).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(100))

        if (recordMode == RecordMode.ENABLED) {
            val events = client.events
            // Events should show increasing timestamps.
            var lastTimestamp = 0L
            for (event in events) {
                assertThat(event.timestampNanos).isGreaterThanOrEqualTo(lastTimestamp)
                lastTimestamp = event.timestampNanos
            }
            // And last timestamp should match clock
            assertThat(lastTimestamp).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(100))
        }
    }

    @Test
    public fun shutdownOutput(): Unit = runBlocking {
        val (client, server) = MockSocket.pair().connectSuspending()
        client.recordMode = recordMode
        server.recordMode = recordMode

        client.sink.buffer().writeUtf8("hello").close()

        assertThat(server.source.buffer().readUtf8()).isEqualTo("hello")
        assertThat(server.source.buffer().exhausted()).isEqualTo(true)
    }

    @Test
    public fun shutdownInput(): Unit = runBlocking {
        val (client, server) = MockSocket.pair().connectSuspending()
        client.recordMode = recordMode

        client.shutdownInput()

        assertFailure { client.source.buffer().readUtf8() }.isInstanceOf(IOException::class.java)
    }

    @Test
    public fun autoClock(): Unit = runBlocking {
        val clock = AutoClock()
        val (client, server) = MockSocket.pair(clock).connect()
        client.recordMode = recordMode
        server.recordMode = recordMode

        client.source.timeout().timeout(100, TimeUnit.MILLISECONDS)

        // This read should block for 100ms in simulated time, advancing the clock automatically.
        // It will fail with strict InterruptedIOException because no data arrived.
        assertFailure { client.source.buffer().readUtf8() }
                .isInstanceOf(InterruptedIOException::class.java)

        // The clock should have advanced by exactly 100ms.
        assertThat(clock.now).isEqualTo(TimeUnit.MILLISECONDS.toNanos(100))
    }
}
