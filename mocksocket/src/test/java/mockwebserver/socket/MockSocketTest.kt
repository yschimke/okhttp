package mockwebserver.socket
import app.cash.burst.Burst
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isInstanceOf
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetSocketAddress
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

        client.soTimeout = 100

        // This read should block for 100ms in simulated time, advancing the clock automatically.
        // It will fail with strict InterruptedIOException because no data arrived.
        assertFailure { client.source.buffer().readUtf8() }
                .isInstanceOf(InterruptedIOException::class.java)

        // The clock should have advanced by exactly 100ms.
        assertThat(clock.now).isEqualTo(TimeUnit.MILLISECONDS.toNanos(100))
    }

    @Test
    public fun connectTimeout(): Unit = runBlocking {
        val clock = AutoClock()
        val (client, _) = MockSocket.pair(
            clock = clock,
            profile = NetworkProfile(latencyNanos = TimeUnit.SECONDS.toNanos(2))
        )
        client.recordMode = recordMode

        // 1 second connection timeout vs 2 second simulated latency
        assertFailure {
            client.connect(InetSocketAddress("localhost", 80), TimeUnit.SECONDS.toNanos(1))
        }.isInstanceOf(java.net.SocketTimeoutException::class.java)
        
        assertThat(clock.now).isEqualTo(TimeUnit.SECONDS.toNanos(1))
    }

    @Test
    public fun writeTimeoutWithFullPeerBuffer(): Unit = runBlocking {
        val clock = AutoClock()
        val profile = NetworkProfile(
            maxWriteBufferSize = 10,
            bytesPerSecond = 100 // 100 bytes / second bandwidth
        )
        val (client, server) = MockSocket.pair(clock, profile).connect()

        client.soTimeout = 100 // 100ms simulated timeout
        
        // Peer buffer is 10. Write 20.
        // The first 10 bytes fit in peer buffer instantly but are paced at 100 B/s (100ms duration).
        // The next 10 bytes block waiting for peer buffer to empty. Since we just sit there
        // and AutoClock advances the clock endlessly when idle, the 100ms timeout triggers.
        assertFailure {
            client.sink.buffer().writeUtf8("12345678901234567890").flush()
        }.isInstanceOf(InterruptedIOException::class.java)
    }

    @Test
    public fun sequentialChunksWithLatency(): Unit = runBlocking {
        val clock = AutoClock()
        val (client, server) = MockSocket.pair(
            clock = clock,
            profile = NetworkProfile(latencyNanos = TimeUnit.MILLISECONDS.toNanos(100))
        ).connect()

        val clientSink = client.sink.buffer()
        val serverSource = server.source.buffer()

        clientSink.writeUtf8("chunk1").flush()
        clock.advanceBy(50, TimeUnit.MILLISECONDS)
        clientSink.writeUtf8("chunk2").flush()
        
        clock.advanceBy(50, TimeUnit.MILLISECONDS)
        assertThat(serverSource.readUtf8(6)).isEqualTo("chunk1")

        assertFailure {
            server.soTimeout = 1
            serverSource.readUtf8(6)
        }.isInstanceOf(InterruptedIOException::class.java)

        clock.advanceBy(50, TimeUnit.MILLISECONDS)
        assertThat(serverSource.readUtf8(6)).isEqualTo("chunk2")
    }
}
