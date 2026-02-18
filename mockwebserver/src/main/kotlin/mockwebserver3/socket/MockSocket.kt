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

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket as JavaNetSocket
import java.net.SocketAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import okio.Buffer

/**
 * A mock implementation of a socket using coroutines.
 *
 * This implementation supports high-fidelity simulation of network conditions like latency and
 * throughput. It integrates with a [Clock] to allow deterministic testing.
 *
 * When using a [FakeClock] (without [AutoClock]), time advances must be triggered externally by the
 * test. The socket will [Clock.await] for the required time to elapse before completing operations
 * that are subject to network simulation.
 */
public class MockSocket
internal constructor(
        public val clock: Clock,
        private val mutex: Mutex,
        private val inputBuffer: Buffer,
        private val outputBuffer: Buffer,
        private val profile: NetworkProfile,
        private val sharedEvents: MutableList<SocketEvent>
) {
    private val stateChanged = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    private var lastArrivalNanos = 0L
    private fun notifyStateChanged(): Unit {
        stateChanged.tryEmit(Unit)
    }

    public var name: String = "Socket"
    public var recordMode: RecordMode = RecordMode.DISABLED
    public var faults: Faults? = null
    public val events: List<SocketEvent>
        get() = sharedEvents

    public val source: okio.Source
        get() = (asSocket() as okio.Socket).source
    public val sink: okio.Sink
        get() = (asSocket() as okio.Socket).sink

    public var onConnect: (String?, Int) -> Unit = { _, _ -> }
    public var localAddress: InetAddress? = null
    public var localPort: Int = 0
    public var remoteHostNameInternal: String? = null
    public var remoteAddressInternal: InetAddress? = null
    public var remotePortInternal: Int = 0

    public var peer: MockSocket? = null
    @Volatile public var lastThread: Thread? = null

    public var isConnected: Boolean = false
    public var closed: Boolean = false
        private set
    public var inputShutdown: Boolean = false
        private set
    public var outputShutdown: Boolean = false
        private set

    public var soTimeout: Int = 0
    public var tcpNoDelay: Boolean = false
    public var reuseAddress: Boolean = false
    public var keepAlive: Boolean = false
    public var receiveBufferSize: Int = 4096
    public var sendBufferSize: Int = 4096
    public var oobInline: Boolean = false
    public var soLinger: Int = -1

    public val remoteAddress: InetAddress
        get() = remoteAddressInternal ?: InetAddress.getLoopbackAddress()
    public val remotePort: Int
        get() = remotePortInternal

    private suspend fun recordEvent(event: SocketEvent): Unit {
        if (recordMode == RecordMode.DISABLED) return
        mutex.withLock {
            sharedEvents.add(event)
            notifyStateChanged()
        }
    }

    public suspend fun awaitState(predicate: () -> Boolean) {
        kotlinx.coroutines.coroutineScope {
            val collection =
                    kotlinx.coroutines.flow.merge(stateChanged, clock.monitor()).produceIn(this)
            try {
                while (!predicate()) {
                    collection.receive()
                }
            } finally {
                collection.cancel()
            }
        }
    }

    public suspend fun readSuspending(
            sink: Buffer,
            byteCount: Long,
            timeout: okio.Timeout =
                    clock.newTimeout(sharedEvents, name).apply {
                        if (soTimeout > 0) timeout(soTimeout.toLong(), TimeUnit.MILLISECONDS)
                    }
    ): Long {
        if (byteCount < 0) throw IllegalArgumentException("byteCount < 0: $byteCount")
        if (closed) throw IOException("closed")
        if (inputShutdown) throw IOException("input shutdown")

        return kotlinx.coroutines.coroutineScope {
            val monitorFlow = clock.monitor()
            val stateFlow = stateChanged
            val collection = kotlinx.coroutines.flow.merge(stateFlow, monitorFlow).produceIn(this)

            try {
                while (true) {
                    timeout.throwIfReached()
                    val result = readInternal(sink, byteCount)
                    if (result != null) return@coroutineScope result

                    // Wait until the socket state changes OR the timeout expires.
                    val waitNanos =
                            if (timeout.hasDeadline()) {
                                val remaining = timeout.deadlineNanoTime() - clock.nanoTime()
                                if (remaining <= 0) timeout.throwIfReached()
                                remaining
                            } else {
                                Long.MAX_VALUE
                            }

                    if (waitNanos == Long.MAX_VALUE) {
                        collection.receive()
                    } else {
                        // Wait for either a state change event OR the timeout to expire.
                        val scope = this
                        val waitJob = scope.launch { clock.await(waitNanos) }
                        kotlinx.coroutines.selects.select<Unit> {
                            collection.onReceive { waitJob.cancel() }
                            waitJob.onJoin {}
                        }
                    }
                }
            } finally {
                collection.cancel()
            }
            throw IOException("unexpected end of readSuspending")
        }
    }

    private suspend fun readInternal(sink: Buffer, byteCount: Long): Long? {
        var waitNanos = 0L
        var readResult: Long? = null
        var shouldAwait = false

        mutex.withLock {
            lastThread = Thread.currentThread()
            if (inputBuffer.size > 0 || closed || inputShutdown || peer?.outputShutdown == true) {
                if (closed) throw IOException("closed")
                if (inputShutdown) {
                    readResult = -1L
                    return@withLock
                }
                if (inputBuffer.size == 0L && peer?.outputShutdown == true) {
                    readResult = -1L
                    return@withLock
                }

                val now = clock.nanoTime()
                val availableAt = lastArrivalNanos + profile.latencyNanos
                if (now < availableAt) {
                    waitNanos = availableAt - now
                    return@withLock
                }

                faults?.maybeThrowRead(clock)

                val readCount = minOf(byteCount, inputBuffer.size)
                readResult = inputBuffer.read(sink, readCount)
                recordEvent(
                        SocketEvent.ReadSuccess(
                                clock.nanoTime(),
                                Thread.currentThread().name,
                                name,
                                readResult!!
                        )
                )
                notifyStateChanged()
                peer?.notifyStateChanged()
            } else {
                shouldAwait = true
            }
        }

        if (readResult != null) return readResult!!
        if (waitNanos > 0) {
            clock.await(waitNanos)
            return null // Indicate that we waited, but no data yet.
        }
        if (shouldAwait) {
            return null // Indicate that we need to await state change.
        }
        return null // Should not be reached if logic is sound, but for completeness.
    }

    public suspend fun writeSuspending(
            source: Buffer,
            byteCount: Long,
            timeout: okio.Timeout =
                    clock.newTimeout(sharedEvents, name).apply {
                        if (soTimeout > 0) timeout(soTimeout.toLong(), TimeUnit.MILLISECONDS)
                    }
    ): Unit {
        if (closed) throw IOException("closed")
        if (outputShutdown) throw IOException("output shutdown")

        var remaining = byteCount
        while (remaining > 0) {
            timeout.throwIfReached()
            val toWrite = minOf(remaining, sendBufferSize.toLong())

            var writeCompleted = false
            mutex.withLock {
                lastThread = Thread.currentThread()
                if (outputBuffer.size < profile.maxWriteBufferSize ||
                                closed ||
                                peer?.inputShutdown == true
                ) {
                    if (closed) throw IOException("closed")
                    if (peer?.inputShutdown == true) throw IOException("broken pipe")

                    if (profile.bytesPerSecond > 0) {
                        val delayNanos = (toWrite * 1_000_000_000L) / profile.bytesPerSecond
                        // This await will block the current coroutine, allowing others to run.
                        // For AutoClock, it will advance the clock.
                        mutex.unlock()
                        try {
                            clock.await(delayNanos)
                        } finally {
                            mutex.lock()
                        }
                    }

                    outputBuffer.write(source, toWrite)
                    recordEvent(
                            SocketEvent.WriteSuccess(
                                    clock.nanoTime(),
                                    Thread.currentThread().name,
                                    name,
                                    toWrite,
                                    clock.nanoTime()
                            )
                    )
                    remaining -= toWrite
                    writeCompleted = true
                    notifyStateChanged()
                    peer?.onDataArrived(clock.nanoTime())
                }
            }

            if (!writeCompleted) {
                kotlinx.coroutines.coroutineScope {
                    val collection =
                            kotlinx.coroutines
                                    .flow
                                    .merge(stateChanged, clock.monitor())
                                    .produceIn(this)

                    try {
                        val waitNanos =
                                if (timeout.hasDeadline()) {
                                    val r = timeout.deadlineNanoTime() - clock.nanoTime()
                                    if (r <= 0) timeout.throwIfReached()
                                    r
                                } else {
                                    Long.MAX_VALUE
                                }

                        if (waitNanos == Long.MAX_VALUE) {
                            collection.receive()
                        } else {
                            val scope = this
                            val waitJob = scope.launch { clock.await(waitNanos) }
                            kotlinx.coroutines.selects.select<Unit> {
                                collection.onReceive { waitJob.cancel() }
                                waitJob.onJoin {}
                            }
                        }
                    } finally {
                        collection.cancel()
                    }
                }
            }
            yield()
        }
    }

    public suspend fun closeSuspending(): Unit {
        mutex.withLock {
            if (closed) return
            recordEvent(SocketEvent.Close(clock.nanoTime(), Thread.currentThread().name, name))
            closed = true
            inputShutdown = true
            outputShutdown = true
            notifyStateChanged()
            peer?.notifyStateChanged()
        }
    }

    public suspend fun shutdownInputSuspending(): Unit {
        mutex.withLock {
            if (inputShutdown) return
            recordEvent(
                    SocketEvent.ShutdownInput(clock.nanoTime(), Thread.currentThread().name, name)
            )
            inputShutdown = true
            inputBuffer.clear()
            notifyStateChanged()
        }
    }

    public suspend fun shutdownOutputSuspending(): Unit {
        mutex.withLock {
            if (outputShutdown) return
            recordEvent(
                    SocketEvent.ShutdownOutput(clock.nanoTime(), Thread.currentThread().name, name)
            )
            outputShutdown = true
            notifyStateChanged()
            peer?.notifyStateChanged()
        }
    }

    public fun shutdownInput(): Unit = runBlocking { shutdownInputSuspending() }

    public fun shutdownOutput(): Unit = runBlocking { shutdownOutputSuspending() }

    public suspend fun connectSuspending(endpoint: SocketAddress): Unit {
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

            // Simulate handshake latency (SYN / SYN-ACK / ACK)
            if (profile.latencyNanos > 0) {
                clock.await(profile.latencyNanos)
            }

            mutex.withLock {
                peer?.let {
                    it.remoteHostNameInternal = endpoint.hostName
                    it.remoteAddressInternal = endpoint.address
                    it.remotePortInternal = endpoint.port
                    it.isConnected = true
                    it.notifyStateChanged()
                }
                        ?: run {
                            this.remoteHostNameInternal = endpoint.hostName
                            this.remoteAddressInternal = endpoint.address
                            this.remotePortInternal = endpoint.port
                            this.isConnected = true
                        }
            }
            onConnect(endpoint.hostName, endpoint.port)
            notifyStateChanged()
        }
    }

    public suspend fun connect(endpoint: SocketAddress, timeoutNanos: Long): Unit {
        // TODO: Implement timeout usage in handshake simulation
        connectSuspending(endpoint)
    }

    public suspend fun bind(bindpoint: SocketAddress): Unit {
        mutex.withLock {
            if (bindpoint is InetSocketAddress) {
                this@MockSocket.localAddress = bindpoint.address
                this@MockSocket.localPort = bindpoint.port
            }
            notifyStateChanged()
        }
    }

    public fun asSocket(): JavaNetSocket = MockSocketAdapter(this)

    public fun pair(server: MockSocket): Unit {
        this.peer = server
        server.peer = this
    }

    internal fun onDataArrived(nanos: Long) {
        lastArrivalNanos = nanos
        notifyStateChanged()
    }

    public companion object {
        public fun pair(
                clock: Clock = Clock.SYSTEM,
                profile: NetworkProfile = NetworkProfile(),
                sharedEvents: MutableList<SocketEvent> = mutableListOf()
        ): Pair<MockSocket, MockSocket> {
            val mutex = Mutex()
            val buffer1 = Buffer()
            val buffer2 = Buffer()
            val socketA = MockSocket(clock, mutex, buffer2, buffer1, profile, sharedEvents)
            val socketB = MockSocket(clock, mutex, buffer1, buffer2, profile, sharedEvents)
            socketA.peer = socketB
            socketB.peer = socketA
            socketA.name = "SocketA"
            socketB.name = "SocketB"
            return Pair(socketA, socketB)
        }

        public fun localhost(clock: Clock = Clock.SYSTEM): Pair<MockSocket, MockSocket> =
                pair(clock, NetworkProfile.LOCALHOST)

        public fun slowMobile(clock: Clock = Clock.SYSTEM): Pair<MockSocket, MockSocket> =
                pair(clock, NetworkProfile.SLOW_MOBILE)
    }

    public interface Faults {
        public fun maybeThrowRead(clock: Clock) {}

        public fun maybeThrowWrite(clock: Clock) {}
    }

    public suspend fun waitForEvent(predicate: (SocketEvent) -> Boolean): Unit {
        while (true) {
            val found = mutex.withLock { sharedEvents.any(predicate) }
            if (found) return
            yield()
            delay(10)
        }
    }

    public suspend fun waitForReadWait(): Unit {
        waitForEvent { it is SocketEvent.ReadWait && it.socketName == name }
    }
}

public enum class RecordMode {
    ENABLED,
    DISABLED
}

public suspend fun Pair<MockSocket, MockSocket>.connectSuspending(): Pair<MockSocket, MockSocket> {
    first.connectSuspending(InetSocketAddress("localhost", 80))
    return this
}

public fun Pair<MockSocket, MockSocket>.connect(): Pair<MockSocket, MockSocket> {
    val client = first
    val server = second
    client.pair(server)
    client.localAddress = InetAddress.getByName("127.0.0.1")
    client.localPort = 49152
    server.localAddress = InetAddress.getByName("127.0.0.1")
    server.localPort = 8080

    client.onConnect("localhost", 8080)
    server.onConnect(null, 49152)
    return this
}
