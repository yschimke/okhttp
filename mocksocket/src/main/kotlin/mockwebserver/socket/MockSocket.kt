package mockwebserver.socket

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

internal class InFlightChunk(val buffer: Buffer, val availableAtNanos: Long)

public class MockSocket
internal constructor(
        public val clock: Clock,
        private val mutex: Mutex,
        private val profile: NetworkProfile,
        public val eventListener: SocketEventListener = NoOpSocketEventListener()
) {
    private val stateChanged = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    
    private val inputBuffer = Buffer()
    private val inFlight = java.util.ArrayDeque<InFlightChunk>()

    private fun notifyStateChanged(): Unit {
        stateChanged.tryEmit(Unit)
    }

    public var name: String = "Socket"
    public var recordMode: RecordMode = RecordMode.DISABLED
    public var faults: Faults? = null

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
        eventListener.onEvent(event)
        notifyStateChanged()
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
                    clock.newTimeout(eventListener, name).apply {
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

                    var waitNanos = Long.MAX_VALUE
                    if (timeout.hasDeadline()) {
                        waitNanos = timeout.deadlineNanoTime() - clock.nanoTime()
                    }
                    if (timeout.timeoutNanos() > 0) {
                        waitNanos = minOf(waitNanos, timeout.timeoutNanos())
                    }
                    if (waitNanos <= 0) timeout.throwIfReached()

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
            
            val now = clock.nanoTime()
            while (inFlight.isNotEmpty() && now >= inFlight.first().availableAtNanos) {
                val chunk = inFlight.removeFirst()
                inputBuffer.writeAll(chunk.buffer)
                notifyStateChanged()
            }

            if (closed) throw IOException("closed")
            if (inputShutdown) {
                readResult = -1L
                return@withLock
            }
            if (inputBuffer.size == 0L && peer?.outputShutdown == true && inFlight.isEmpty()) {
                readResult = -1L
                return@withLock
            }
            
            faults?.maybeThrowRead(clock)

            if (inputBuffer.size > 0) {
                val readCount = minOf(byteCount, inputBuffer.size)
                
                // Read into intermediate buffer or track specifically so we can duplicate it
                val startSize = sink.size
                readResult = inputBuffer.read(sink, readCount)
                
                val payloadSize = sink.size - startSize
                val recordedPayload = if (recordMode == RecordMode.ENABLED && payloadSize > 0) {
                    val cloneBuffer = Buffer()
                    sink.copyTo(cloneBuffer, startSize, payloadSize)
                    cloneBuffer
                } else null

                recordEvent(
                        SocketEvent.ReadSuccess(
                                clock.nanoTime(),
                                Thread.currentThread().name,
                                name,
                                readResult!!,
                                recordedPayload
                        )
                )
                notifyStateChanged()
                peer?.notifyStateChanged()
            } else if (inFlight.isNotEmpty()) {
                waitNanos = maxOf(1L, inFlight.first().availableAtNanos - now)
            } else {
                shouldAwait = true
            }
        }

        if (readResult != null) return readResult!!
        if (waitNanos > 0) {
            clock.await(waitNanos)
            return null
        }
        if (shouldAwait) {
            return null
        }
        return null
    }

    private fun peerTotalBytesBuffered(): Long {
        val p = peer ?: return 0L
        var total = p.inputBuffer.size
        for (chunk in p.inFlight) {
            total += chunk.buffer.size
        }
        return total
    }

    public suspend fun writeSuspending(
            source: Buffer,
            byteCount: Long,
            timeout: okio.Timeout =
                    clock.newTimeout(eventListener, name).apply {
                        if (soTimeout > 0) timeout(soTimeout.toLong(), TimeUnit.MILLISECONDS)
                    }
    ): Unit {
        if (closed) throw IOException("closed")
        if (outputShutdown) throw IOException("output shutdown")

        var remaining = byteCount
        while (remaining > 0) {
            timeout.throwIfReached()

            var writeCompleted = false
            mutex.withLock {
                lastThread = Thread.currentThread()
                val peerBuffered = peerTotalBytesBuffered()
                
                if (peerBuffered < profile.maxWriteBufferSize ||
                                closed ||
                                peer?.inputShutdown == true
                ) {
                    if (closed) throw IOException("closed")
                    if (peer?.inputShutdown == true) throw IOException("broken pipe")

                    val maxAllowedToWrite = profile.maxWriteBufferSize - peerBuffered
                    var toWrite = minOf(remaining, sendBufferSize.toLong())
                    toWrite = minOf(toWrite, maxOf(1, maxAllowedToWrite))

                    val p = peer
                    val recordedPayload = if (recordMode == RecordMode.ENABLED && toWrite > 0) {
                        val cloneBuffer = Buffer()
                        source.copyTo(cloneBuffer, 0, toWrite)
                        cloneBuffer
                    } else null

                    if (p != null) {
                       if (profile.bytesPerSecond > 0) {
                           val delayNanos = (toWrite * 1_000_000_000L) / profile.bytesPerSecond
                           mutex.unlock()
                           try {
                               clock.await(delayNanos)
                           } finally {
                               mutex.lock()
                           }
                           
                           if (closed) throw IOException("closed")
                           if (p.inputShutdown) throw IOException("broken pipe")
                       }
                       
                       val arrivalNanos = clock.nanoTime() + profile.latencyNanos
                       val newBuffer = Buffer()
                       newBuffer.write(source, toWrite)
                       p.inFlight.addLast(InFlightChunk(newBuffer, arrivalNanos))
                       p.notifyStateChanged()
                    } else {
                        source.skip(toWrite)
                    }

                    recordEvent(
                            SocketEvent.WriteSuccess(
                                    clock.nanoTime(),
                                    Thread.currentThread().name,
                                    name,
                                    toWrite,
                                    clock.nanoTime(),
                                    recordedPayload
                            )
                    )
                    remaining -= toWrite
                    writeCompleted = true
                    notifyStateChanged()
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
                        var waitNanos = Long.MAX_VALUE
                        if (timeout.hasDeadline()) {
                            waitNanos = timeout.deadlineNanoTime() - clock.nanoTime()
                        }
                        if (timeout.timeoutNanos() > 0) {
                            waitNanos = minOf(waitNanos, timeout.timeoutNanos())
                        }
                        
                        if (waitNanos <= 0) timeout.throwIfReached()

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
            inFlight.clear()
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
                            endpoint.hostString,
                            endpoint.port
                    )
            )
            if (profile.latencyNanos > 0) {
                val timeout = clock.newTimeout(eventListener, name).apply {
                    if (soTimeout > 0) timeout(soTimeout.toLong(), TimeUnit.MILLISECONDS)
                }
                
                var waitNanos = profile.latencyNanos
                if (timeout.hasDeadline()) {
                    waitNanos = minOf(waitNanos, timeout.deadlineNanoTime() - clock.nanoTime())
                }
                if (timeout.timeoutNanos() > 0) {
                    waitNanos = minOf(waitNanos, timeout.timeoutNanos())
                }
                
                if (waitNanos < profile.latencyNanos || waitNanos <= 0) {
                    if (waitNanos > 0) {
                       clock.await(waitNanos)
                    }
                    throw java.net.SocketTimeoutException("connect timed out")
                }
            }
            clock.await(profile.latencyNanos)
        }
        isConnected = true
    }

    public fun close(): Unit = runBlocking { closeSuspending() }

    public fun connect(endpoint: SocketAddress): Unit = runBlocking { connectSuspending(endpoint) }

    public suspend fun attachClientSuspending(hostName: String, port: Int): MockSocket {
        peer = MockSocket(clock, mutex, profile, eventListener)
        peer!!.peer = this
        this.remoteHostNameInternal = hostName
        this.remotePortInternal = port
        this.remoteAddressInternal = InetAddress.getByName(hostName)
        peer!!.localAddress = this.remoteAddressInternal
        peer!!.localPort = this.remotePortInternal

        this.onConnect(hostName, port)
        peer!!.onConnect(null, this.localPort)
        return peer!!
    }

    public fun asSocket(): JavaNetSocket {
        return MockSocketAdapter(this)
    }

    public fun pair(peer: MockSocket): MockSocket {
        this.peer = peer
        peer.peer = this
        return this
    }

    public companion object {
        public fun pair(
                clock: Clock = AutoClock(),
                profile: NetworkProfile = NetworkProfile(),
                eventListener: SocketEventListener = MemorySocketEventListener()
        ): Pair<MockSocket, MockSocket> {
            val mutex = Mutex()
            return Pair(
                    MockSocket(clock, mutex, profile, eventListener).apply { name = "client" },
                    MockSocket(clock, mutex, profile, eventListener).apply { name = "server" }
            )
        }
    }

    public suspend fun waitForEvent(predicate: (SocketEvent) -> Boolean): SocketEvent {
        return kotlinx.coroutines.coroutineScope {
            val collection =
                    kotlinx.coroutines.flow.merge(stateChanged, clock.monitor()).produceIn(this)
            try {
                while (true) {
                    var snapshot: List<SocketEvent> = emptyList()
                    if (eventListener is MemorySocketEventListener) {
                         snapshot = eventListener.events
                    }

                    val event = snapshot.find(predicate)
                    if (event != null) {
                        return@coroutineScope event
                    }
                    collection.receive()
                }
            } finally {
                collection.cancel()
            }
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
    client.pair(server) // Re-pair outside lock just in case
    client.localAddress = InetAddress.getByName("127.0.0.1")
    client.localPort = 49152
    server.localAddress = InetAddress.getByName("127.0.0.1")
    server.localPort = 8080

    client.onConnect("localhost", 8080)
    server.onConnect(null, 49152)
    return this
}
