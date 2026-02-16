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

import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket as JavaNetSocket
import java.net.SocketAddress
import java.net.SocketOption
import java.net.StandardSocketOptions
import java.util.PriorityQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs
import okio.Buffer
import okio.Sink
import okio.Socket as OkioSocket
import okio.Source
import okio.Timeout
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

/**
 * A mock implementation of [okio.Socket].
 *
 * Use [pair] to create two connected instances for client/server testing.
 */
@Suppress("DEPRECATION")
public class MockSocket
internal constructor(
        public val clock: Clock,
        private val lock: ReentrantLock,
        private val inputBuffer: Buffer,
        private val outputBuffer: Buffer,
        private val inputCondition: Condition,
        private val outputCondition: Condition,
        private val profile: NetworkProfile,
        private val sharedEvents: MutableList<SocketEvent>,
        public var localAddress: InetAddress? = null,
        public var localPort: Int = 0,
) : OkioSocket, Closeable {
    public var name: String = "Socket"
    public var recordMode: RecordMode = RecordMode.ENABLED
    public var onConnect: (String?, Int) -> Unit = { _, _ -> }
    public var localHostName: String? = null
    public var remoteHostNameInternal: String? = null
    public var remoteAddressInternal: InetAddress? = null
    public var remotePortInternal: Int = 0
    @Volatile public var lastThread: Thread? = null

    @Suppress("NewApi")
    @IgnoreJRERequirement
    public val socketOpts: MutableMap<SocketOption<*>, Any> = mutableMapOf()

    /** Returns all events recorded by this socket and its peer. */
    public val events: List<SocketEvent>
        get() = lock.withLock { sharedEvents.toList() }

    public val remotePort: Int
        get() = peer?.localPort ?: remotePortInternal

    public val remoteAddress: InetAddress?
        get() = peer?.localAddress ?: remoteAddressInternal

    public val remoteHostName: String?
        get() = peer?.localHostName ?: remoteHostNameInternal

    @Suppress("NewApi")
    @get:IgnoreJRERequirement
    public var tcpNoDelay: Boolean
        get() = socketOpts[StandardSocketOptions.TCP_NODELAY] as? Boolean ?: false
        set(value) {
            socketOpts[StandardSocketOptions.TCP_NODELAY] = value
        }

    @Suppress("NewApi")
    @get:IgnoreJRERequirement
    public var soLinger: Int
        get() = socketOpts[StandardSocketOptions.SO_LINGER] as? Int ?: -1
        set(value) {
            socketOpts[StandardSocketOptions.SO_LINGER] = value
        }

    @Suppress("NewApi")
    @get:IgnoreJRERequirement
    public var sendBufferSize: Int
        get() = socketOpts[StandardSocketOptions.SO_SNDBUF] as? Int ?: 1024
        set(value) {
            socketOpts[StandardSocketOptions.SO_SNDBUF] = value
        }

    @Suppress("NewApi")
    @get:IgnoreJRERequirement
    public var receiveBufferSize: Int
        get() = socketOpts[StandardSocketOptions.SO_RCVBUF] as? Int ?: 1024
        set(value) {
            socketOpts[StandardSocketOptions.SO_RCVBUF] = value
        }

    @Suppress("NewApi")
    @get:IgnoreJRERequirement
    public var keepAlive: Boolean
        get() = socketOpts[StandardSocketOptions.SO_KEEPALIVE] as? Boolean ?: false
        set(value) {
            socketOpts[StandardSocketOptions.SO_KEEPALIVE] = value
        }

    @Suppress("NewApi")
    @get:IgnoreJRERequirement
    public var reuseAddress: Boolean
        get() = socketOpts[StandardSocketOptions.SO_REUSEADDR] as? Boolean ?: false
        set(value) {
            socketOpts[StandardSocketOptions.SO_REUSEADDR] = value
        }

    public var oobInline: Boolean = false
    public var soTimeout: Int = 0

    private fun recordEvent(event: SocketEvent) {
        if (recordMode == RecordMode.DISABLED) return
        println("EVENT: $event")
        lock.withLock { sharedEvents.add(event) }
    }

    private val scheduledFailures = PriorityQueue<ScheduledFailure>()

    private data class ScheduledFailure(val triggerTimeNanos: Long, val exception: IOException) :
            Comparable<ScheduledFailure> {
        override fun compareTo(other: ScheduledFailure): Int =
                triggerTimeNanos.compareTo(other.triggerTimeNanos)
    }

    /**
     * Schedules a failure to be thrown after the specified duration. The duration is relative to
     * the current time of the [clock].
     */
    public fun scheduleFailure(delay: Long, unit: TimeUnit, exception: IOException) {
        val triggerTimeNanos = clock.nanoTime() + unit.toNanos(delay)
        lock.withLock { scheduledFailures.add(ScheduledFailure(triggerTimeNanos, exception)) }
    }

    private fun checkScheduledFailures() {
        val now = clock.nanoTime()
        lock.withLock {
            val failure = scheduledFailures.peek()
            if (failure != null && failure.triggerTimeNanos <= now) {
                scheduledFailures.poll()
                throw failure.exception
            }
        }
    }

    public val isConnected: Boolean
        get() = peer != null && !closed

    public var closed: Boolean = false
        private set
    public var inputShutdown: Boolean = false
        private set
    public var outputShutdown: Boolean = false
        private set

    internal val outgoingChunks = mutableListOf<ArrivalTimeChunk>()
    private var lastWriteFinishTime = 0L

    public var faults: Faults = Faults.NONE

    override val source: Source =
            object : Source {
                private val timeout = clock.newTimeout()

                override fun read(sink: Buffer, byteCount: Long): Long {
                    require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
                    val callStart = clock.nanoTime()
                    lastThread = Thread.currentThread()

                    lock.withLock {
                        val effectiveDeadline =
                                if (timeout.hasDeadline()) {
                                    timeout.deadlineNanoTime()
                                } else if (timeout.timeoutNanos() > 0) {
                                    clock.nanoTime() + timeout.timeoutNanos()
                                } else if (soTimeout > 0) {
                                    clock.nanoTime() +
                                            TimeUnit.MILLISECONDS.toNanos(soTimeout.toLong())
                                } else {
                                    Long.MAX_VALUE
                                }

                        while (true) {
                            checkScheduledFailures()
                            while (inputBuffer.size == 0L) {
                                if (closed) {
                                    recordEvent(
                                            SocketEvent.ReadFailed(
                                                    clock.nanoTime(),
                                                    Thread.currentThread().name,
                                                    name,
                                                    "closed"
                                            )
                                    )
                                    throw IOException("closed")
                                }
                                if (inputShutdown) {
                                    recordEvent(
                                            SocketEvent.ReadFailed(
                                                    clock.nanoTime(),
                                                    Thread.currentThread().name,
                                                    name,
                                                    "input shutdown"
                                            )
                                    )
                                    throw IOException("input shutdown")
                                }
                                if (peer?.outputShutdown == true) {
                                    recordEvent(
                                            SocketEvent.ReadEof(
                                                    clock.nanoTime(),
                                                    Thread.currentThread().name,
                                                    name
                                            )
                                    )
                                    return -1L
                                }

                                faults.maybeThrowRead(clock)

                                val now = clock.nanoTime()
                                val waitNanos =
                                        if (effectiveDeadline == Long.MAX_VALUE) Long.MAX_VALUE
                                        else effectiveDeadline - now

                                if (waitNanos <= 0) {
                                    val message =
                                            when {
                                                timeout.hasDeadline() &&
                                                        now >= timeout.deadlineNanoTime() ->
                                                        "read deadline exceeded"
                                                soTimeout > 0 ->
                                                        "read soTimeout exceeded ($soTimeout)"
                                                timeout.timeoutNanos() > 0 ->
                                                        "read timeout exceeded"
                                                else -> null
                                            }
                                    if (message != null) {
                                        throw InterruptedIOException(timeoutMessage(message))
                                    }
                                }

                                lastThread = Thread.currentThread()

                                // Check for scheduled failures
                                checkScheduledFailures()

                                // If we are waiting, check if our peer has an earlier event.
                                val peerWaitNanos =
                                        peer?.let { p ->
                                            synchronized(p.outgoingChunks) {
                                                p.outgoingChunks.firstOrNull()?.let {
                                                    maxOf(0L, it.arrivalTimeNanos - now)
                                                }
                                            }
                                        }
                                                ?: Long.MAX_VALUE

                                val failureWaitNanos =
                                        lock.withLock {
                                            scheduledFailures.peek()?.let {
                                                maxOf(0L, it.triggerTimeNanos - now)
                                            }
                                                    ?: Long.MAX_VALUE
                                        }

                                val actualWaitNanos =
                                        minOf(waitNanos, peerWaitNanos, failureWaitNanos)

                                recordEvent(
                                        SocketEvent.ReadWait(
                                                clock.nanoTime(),
                                                Thread.currentThread().name,
                                                name,
                                                actualWaitNanos
                                        )
                                )
                                clock.await(inputCondition, actualWaitNanos)
                            }

                            val now = clock.nanoTime()
                            var availableCount = 0L
                            peer?.let { p ->
                                synchronized(p.outgoingChunks) {
                                    for (chunk in p.outgoingChunks) {
                                        if (chunk.arrivalTimeNanos <= now) {
                                            availableCount += chunk.byteCount
                                        } else {
                                            break
                                        }
                                    }
                                }
                            }

                            if (availableCount == 0L) {
                                val waitData =
                                        peer?.let { p ->
                                            synchronized(p.outgoingChunks) {
                                                p.outgoingChunks.firstOrNull()?.let {
                                                    it.arrivalTimeNanos - now
                                                }
                                            }
                                        }
                                                ?: Long.MAX_VALUE

                                if (waitData == Long.MAX_VALUE) {
                                    if (peer?.outputShutdown == true) {
                                        recordEvent(
                                                SocketEvent.ReadEof(
                                                        clock.nanoTime(),
                                                        Thread.currentThread().name,
                                                        name
                                                )
                                        )
                                        return -1L
                                    }
                                    clock.await(inputCondition, Long.MAX_VALUE)
                                    continue
                                }

                                val waitTimeout =
                                        if (effectiveDeadline == Long.MAX_VALUE) Long.MAX_VALUE
                                        else effectiveDeadline - now

                                if (effectiveDeadline != Long.MAX_VALUE && waitTimeout <= 0) {
                                    throw InterruptedIOException("timeout")
                                }

                                val waitWithTimeout = minOf(waitData, waitTimeout)

                                recordEvent(
                                        SocketEvent.ReadWait(
                                                clock.nanoTime(),
                                                Thread.currentThread().name,
                                                name,
                                                waitWithTimeout
                                        )
                                )
                                clock.await(inputCondition, waitWithTimeout)
                                continue
                            }

                            val toRead = minOf(byteCount, availableCount)
                            val readCount = inputBuffer.read(sink, toRead)
                            if (readCount != -1L) {
                                recordEvent(
                                        SocketEvent.ReadSuccess(
                                                clock.nanoTime(),
                                                Thread.currentThread().name,
                                                name,
                                                readCount
                                        )
                                )
                                var remainingToRemove = readCount
                                peer?.let { p ->
                                    synchronized(p.outgoingChunks) {
                                        while (remainingToRemove > 0) {
                                            val chunk = p.outgoingChunks.first()
                                            if (chunk.byteCount <= remainingToRemove) {
                                                remainingToRemove -= chunk.byteCount
                                                p.outgoingChunks.removeAt(0)
                                            } else {
                                                p.outgoingChunks[0] =
                                                        ArrivalTimeChunk(
                                                                chunk.byteCount - remainingToRemove,
                                                                chunk.arrivalTimeNanos
                                                        )
                                                remainingToRemove = 0
                                            }
                                        }
                                    }
                                }
                                faults.postRead(readCount)
                            }
                            return readCount
                        }
                    }
                }

                override fun timeout(): Timeout = timeout

                override fun close() {
                    // No-op
                }
            }

    override val sink: Sink =
            object : Sink {
                private val timeout = clock.newTimeout()

                override fun write(source: Buffer, byteCount: Long) {
                    require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
                    val callStart = clock.nanoTime()

                    lock.withLock {
                        val timeoutNanos = timeout.timeoutNanos()
                        val hasDeadline = timeout.hasDeadline()
                        val effectiveDeadline =
                                if (hasDeadline) {
                                    timeout.deadlineNanoTime()
                                } else if (timeoutNanos > 0) {
                                    clock.nanoTime() + timeoutNanos
                                } else {
                                    Long.MAX_VALUE
                                }

                        while (outputBuffer.size >= profile.maxWriteBufferSize) {
                            if (closed || outputShutdown) {
                                recordEvent(
                                        SocketEvent.WriteFailed(
                                                clock.nanoTime(),
                                                Thread.currentThread().name,
                                                name,
                                                "closed or output shutdown"
                                        )
                                )
                                throw IOException("closed")
                            }

                            recordEvent(
                                    SocketEvent.WriteWaitBufferFull(
                                            clock.nanoTime(),
                                            Thread.currentThread().name,
                                            name,
                                            outputBuffer.size
                                    )
                            )

                            val now = clock.nanoTime()
                            val waitNanos =
                                    if (effectiveDeadline == Long.MAX_VALUE) Long.MAX_VALUE
                                    else effectiveDeadline - now

                            if (waitNanos <= 0) {
                                val message =
                                        when {
                                            timeout.hasDeadline() &&
                                                    now >= timeout.deadlineNanoTime() ->
                                                    "write deadline"
                                            soTimeout > 0 -> "write soTimeout exceeded ($soTimeout)"
                                            timeout.timeoutNanos() > 0 -> "write timeout exceeded"
                                            else -> null
                                        }
                                if (message != null) {
                                    throw InterruptedIOException(timeoutMessage(message))
                                }
                            }

                            lastThread = Thread.currentThread()

                            // Check for scheduled failures
                            checkScheduledFailures()

                            // If we are waiting, check if our peer has an earlier event.
                            val peerWaitNanos =
                                    peer?.let { p ->
                                        synchronized(p.outgoingChunks) {
                                            p.outgoingChunks.firstOrNull()?.let {
                                                maxOf(0L, it.arrivalTimeNanos - now)
                                            }
                                        }
                                    }
                                            ?: Long.MAX_VALUE

                            val failureWaitNanos =
                                    lock.withLock {
                                        scheduledFailures.peek()?.let {
                                            maxOf(0L, it.triggerTimeNanos - now)
                                        }
                                                ?: Long.MAX_VALUE
                                    }

                            val actualWaitNanos = minOf(waitNanos, peerWaitNanos, failureWaitNanos)

                            clock.await(outputCondition, actualWaitNanos)
                        }

                        if (closed || outputShutdown) throw IOException("closed")

                        lastThread = Thread.currentThread()

                        faults.maybeThrowWrite(clock)

                        val now = clock.nanoTime()
                        val startTime = maxOf(now, lastWriteFinishTime)
                        val sendDuration =
                                if (profile.bytesPerSecond > 0) {
                                    byteCount * 1_000_000_000L / profile.bytesPerSecond
                                } else {
                                    0L
                                }

                        val arrivalTime = startTime + profile.latencyNanos + sendDuration
                        lastWriteFinishTime = startTime + sendDuration

                        if (clock is FakeClock && sendDuration > 0) {
                            clock.advanceBy(sendDuration)
                        }

                        recordEvent(
                                SocketEvent.WriteSuccess(
                                        clock.nanoTime(),
                                        Thread.currentThread().name,
                                        name,
                                        byteCount,
                                        arrivalTime
                                )
                        )
                        outputBuffer.write(source, byteCount)

                        peer?.let {
                            synchronized(this@MockSocket.outgoingChunks) {
                                this@MockSocket.outgoingChunks.add(
                                        ArrivalTimeChunk(byteCount, arrivalTime)
                                )
                            }
                            it.recordEvent(
                                    SocketEvent.DataArrival(
                                            clock.nanoTime(),
                                            Thread.currentThread().name,
                                            it.name,
                                            byteCount,
                                            arrivalTime
                                    )
                            )
                            it.inputCondition.signalAll()
                        }

                        faults.postWrite(byteCount)
                        outputCondition.signalAll()
                    }
                }

                override fun flush() {
                    lock.withLock { if (closed || outputShutdown) throw IOException("closed") }
                }

                override fun timeout(): Timeout = timeout

                override fun close() {
                    shutdownOutput()
                }
            }

    override fun close() {
        lock.withLock {
            if (closed) return
            recordEvent(SocketEvent.Close(clock.nanoTime(), Thread.currentThread().name, name))
            closed = true
            inputShutdown = true
            outputShutdown = true
            inputCondition.signalAll()
            outputCondition.signalAll()
            peer?.let { it.inputCondition.signalAll() }
        }
    }

    public fun shutdownInput() {
        lock.withLock {
            if (inputShutdown) return
            recordEvent(
                    SocketEvent.ShutdownInput(clock.nanoTime(), Thread.currentThread().name, name)
            )
            inputShutdown = true
            inputBuffer.clear()
            synchronized(this@MockSocket.outgoingChunks) { this@MockSocket.outgoingChunks.clear() }
            inputCondition.signalAll()
        }
    }

    public fun shutdownOutput() {
        lock.withLock {
            if (outputShutdown) return
            recordEvent(
                    SocketEvent.ShutdownOutput(clock.nanoTime(), Thread.currentThread().name, name)
            )
            outputShutdown = true
            outputCondition.signalAll()
            peer?.let { it.inputCondition.signalAll() }
        }
    }

    override fun cancel() {
        close()
    }

    public fun connect(endpoint: SocketAddress) {
        if (endpoint is InetSocketAddress) {
            recordEvent(
                    SocketEvent.Connect(
                            clock.nanoTime(),
                            Thread.currentThread().name,
                            name,
                            endpoint.hostName,
                            endpoint.port
                    )
            )
            peer?.let {
                it.localHostName = endpoint.hostName
                it.localAddress = endpoint.address
                it.localPort = endpoint.port
            }
                    ?: run {
                        this.remoteHostNameInternal = endpoint.hostName
                        this.remoteAddressInternal = endpoint.address
                        this.remotePortInternal = endpoint.port
                    }
            onConnect(endpoint.hostName, endpoint.port)
        }
    }

    public fun connect(endpoint: SocketAddress, timeout: Int) {
        connect(endpoint)
    }

    public fun bind(bindpoint: SocketAddress) {
        if (bindpoint is InetSocketAddress) {
            this.localAddress = bindpoint.address
            this.localPort = bindpoint.port
        }
    }

    public fun asSocket(): JavaNetSocket = MockSocketAdapter(this)

    public fun pair(server: MockSocket) {
        this.peer = server
        server.peer = this
    }

    private var peer: MockSocket? = null

    internal data class ArrivalTimeChunk(val byteCount: Long, val arrivalTimeNanos: Long)

    public interface Faults {
        public fun maybeThrowRead(clock: Clock) {}
        public fun postRead(byteCount: Long) {}
        public fun maybeThrowWrite(clock: Clock) {}
        public fun postWrite(byteCount: Long) {}

        public companion object {
            @JvmField public val NONE: Faults = object : Faults {}
        }
    }

    public enum class RecordMode {
        DISABLED,
        ENABLED
    }

    public companion object {
        public fun pair(
                clock: Clock = Clock.SYSTEM,
                profile: NetworkProfile = NetworkProfile(),
                sharedEvents: MutableList<SocketEvent> = mutableListOf()
        ): Pair<MockSocket, MockSocket> {
            val lock = ReentrantLock()
            val buffer1 = Buffer()
            val buffer2 = Buffer()
            val condition1 = lock.newCondition()
            val condition2 = lock.newCondition()

            val socketA =
                    MockSocket(
                            clock,
                            lock,
                            buffer2,
                            buffer1,
                            condition2,
                            condition1,
                            profile,
                            sharedEvents,
                    )
            val socketB =
                    MockSocket(
                            clock,
                            lock,
                            buffer1,
                            buffer2,
                            condition1,
                            condition2,
                            profile,
                            sharedEvents,
                    )

            return Pair(socketA, socketB)
        }

        public fun localhost(clock: Clock = Clock.SYSTEM): Pair<MockSocket, MockSocket> =
                pair(clock, NetworkProfile.LOCALHOST)

        public fun slowMobile(clock: Clock = Clock.SYSTEM): Pair<MockSocket, MockSocket> =
                pair(clock, NetworkProfile.SLOW_MOBILE)
    }

    private fun formatDuration(nanos: Long): String {
        val millis = (nanos + 999_999) / 1_000_000
        if (millis == 0L) return "0ms"

        val commonTimeouts =
                listOf(10L, 50L, 100L, 250L, 500L, 1000L, 2000L, 5000L, 10000L, 30000L, 60000L)
        for (common in commonTimeouts) {
            if (abs(millis - common) < 10) {
                return "${common}ms"
            }
        }
        return "${millis}ms"
    }

    private fun timeoutMessage(message: String): String = buildString {
        append(message)
        append(" (")
        append(name)
        append(")")
        lastThread?.let { t ->
            append("\n  Last thread: ")
            append(t.name)
            append("\n")
            t.stackTrace.forEach { element ->
                append("    at ")
                append(element)
                append("\n")
            }
        }
        peer?.let { p ->
            append("\n  Peer: ")
            append(p.name)
            p.lastThread?.let { pt ->
                append("\n  Peer last thread: ")
                append(pt.name)
                append("\n")
                pt.stackTrace.forEach { element ->
                    append("    at ")
                    append(element)
                    append("\n")
                }
            }
                    ?: append("\n  Peer has no last thread recorded")
        }
                ?: append("\n  No peer connected")
    }
}

/** Helper to connect a pair of sockets for testing. */
public fun Pair<MockSocket, MockSocket>.connect() {
    val client = first
    val server = second
    client.pair(server)
    client.localAddress = InetAddress.getByName("127.0.0.1")
    client.localPort = 49152
    server.localAddress = InetAddress.getByName("127.0.0.1")
    server.localPort = 8080

    // TODO these should be valid values including generating distinct ports above?
    client.onConnect("localhost", 8080)
    server.onConnect(null, 49152)
}
