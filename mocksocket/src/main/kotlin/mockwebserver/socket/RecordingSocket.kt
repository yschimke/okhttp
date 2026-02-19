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
package mockwebserver.socket

import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.locks.ReentrantLock
import javax.net.ServerSocketFactory
import javax.net.SocketFactory
import kotlin.concurrent.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

/** A [Socket] implementation that delegates to another [Socket] and records events. */
public open class RecordingSocket(
        private val delegate: Socket,
        private val sharedEvents: MutableList<SocketEvent>,
        public val name: String = "Socket"
) : Socket() {
        private val clock = Clock.SYSTEM
        private val lock = ReentrantLock()

        override fun connect(endpoint: SocketAddress?) {
                delegate.connect(endpoint)
                recordConnect(endpoint)
        }

        override fun connect(endpoint: SocketAddress?, timeout: Int) {
                delegate.connect(endpoint, timeout)
                recordConnect(endpoint)
        }

        private fun recordConnect(endpoint: SocketAddress?) {
                val address = endpoint as? java.net.InetSocketAddress
                sharedEvents.add(
                        SocketEvent.Connect(
                                clock.nanoTime(),
                                Thread.currentThread().name,
                                name,
                                address?.hostName,
                                address?.port ?: 0
                        )
                )
        }

        override fun getInputStream(): InputStream {
                return object : InputStream() {
                        private val delegateStream = delegate.getInputStream()
                        override fun read(): Int {
                                val b = delegateStream.read()
                                if (b != -1) {
                                        sharedEvents.add(
                                                SocketEvent.ReadSuccess(
                                                        clock.nanoTime(),
                                                        Thread.currentThread().name,
                                                        name,
                                                        1L
                                                )
                                        )
                                } else {
                                        sharedEvents.add(
                                                SocketEvent.ReadEof(
                                                        clock.nanoTime(),
                                                        Thread.currentThread().name,
                                                        name
                                                )
                                        )
                                }
                                return b
                        }

                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                                val count = delegateStream.read(b, off, len)
                                if (count != -1) {
                                        sharedEvents.add(
                                                SocketEvent.ReadSuccess(
                                                        clock.nanoTime(),
                                                        Thread.currentThread().name,
                                                        name,
                                                        count.toLong()
                                                )
                                        )
                                } else {
                                        sharedEvents.add(
                                                SocketEvent.ReadEof(
                                                        clock.nanoTime(),
                                                        Thread.currentThread().name,
                                                        name
                                                )
                                        )
                                }
                                return count
                        }

                        override fun close() {
                                delegateStream.close()
                        }
                }
        }

        override fun getOutputStream(): OutputStream {
                return object : OutputStream() {
                        private val delegateStream = delegate.getOutputStream()
                        override fun write(b: Int) {
                                delegateStream.write(b)
                                sharedEvents.add(
                                        SocketEvent.WriteSuccess(
                                                clock.nanoTime(),
                                                Thread.currentThread().name,
                                                name,
                                                1L,
                                                clock.nanoTime()
                                        )
                                )
                        }

                        override fun write(b: ByteArray, off: Int, len: Int) {
                                delegateStream.write(b, off, len)
                                sharedEvents.add(
                                        SocketEvent.WriteSuccess(
                                                clock.nanoTime(),
                                                Thread.currentThread().name,
                                                name,
                                                len.toLong(),
                                                clock.nanoTime()
                                        )
                                )
                        }

                        override fun flush() {
                                delegateStream.flush()
                        }

                        override fun close() {
                                delegateStream.close()
                        }
                }
        }

        override fun close() {
                delegate.close()
                sharedEvents.add(
                        SocketEvent.Close(clock.nanoTime(), Thread.currentThread().name, name)
                )
        }

        override fun shutdownInput() {
                delegate.shutdownInput()
                sharedEvents.add(
                        SocketEvent.ShutdownInput(
                                clock.nanoTime(),
                                Thread.currentThread().name,
                                name
                        )
                )
        }

        override fun shutdownOutput() {
                delegate.shutdownOutput()
                lock.withLock {
                        sharedEvents.add(
                                SocketEvent.ShutdownOutput(
                                        clock.nanoTime(),
                                        Thread.currentThread().name,
                                        name
                                )
                        )
                }
        }

        public suspend fun waitForEvent(predicate: (SocketEvent) -> Boolean) {
                while (true) {
                        val found = lock.withLock { sharedEvents.any(predicate) }
                        if (found) return
                        yield()
                        delay(10)
                }
        }

        public suspend fun waitForRead() {
                waitForEvent { it is SocketEvent.ReadSuccess && it.socketName == name }
        }
}

/** A [ServerSocket] implementation that delegates to another [ServerSocket] and records events. */
public open class RecordingServerSocket(
        private val delegate: ServerSocket,
        private val sharedEvents: MutableList<SocketEvent>,
        public val name: String = "ServerSocket"
) : ServerSocket() {
        private val clock = Clock.SYSTEM

        override fun accept(): Socket {
                sharedEvents.add(
                        SocketEvent.AcceptStarting(
                                clock.nanoTime(),
                                Thread.currentThread().name,
                                name
                        )
                )
                val socket = delegate.accept()
                val recordingSocket = RecordingSocket(socket, sharedEvents, "AcceptedSocket")
                sharedEvents.add(
                        SocketEvent.AcceptReturning(
                                clock.nanoTime(),
                                Thread.currentThread().name,
                                name,
                                recordingSocket.name
                        )
                )
                return recordingSocket
        }

        override fun close() {
                delegate.close()
                sharedEvents.add(
                        SocketEvent.Close(clock.nanoTime(), Thread.currentThread().name, name)
                )
        }

        override fun bind(endpoint: SocketAddress?) {
                delegate.bind(endpoint)
        }

        override fun bind(endpoint: SocketAddress?, backlog: Int) {
                delegate.bind(endpoint, backlog)
        }

        override fun getLocalPort(): Int = delegate.localPort
        override fun getInetAddress(): java.net.InetAddress = delegate.inetAddress
        override fun getLocalSocketAddress(): SocketAddress = delegate.localSocketAddress
}

public class RecordingSocketFactory(
        private val delegate: SocketFactory,
        private val sharedEvents: MutableList<SocketEvent>
) : SocketFactory() {
        override fun createSocket(): Socket = RecordingSocket(delegate.createSocket(), sharedEvents)
        override fun createSocket(host: String?, port: Int): Socket =
                RecordingSocket(delegate.createSocket(host, port), sharedEvents)
        override fun createSocket(
                host: String?,
                port: Int,
                localHost: java.net.InetAddress?,
                localPort: Int
        ): Socket =
                RecordingSocket(
                        delegate.createSocket(host, port, localHost, localPort),
                        sharedEvents
                )
        override fun createSocket(host: java.net.InetAddress?, port: Int): Socket =
                RecordingSocket(delegate.createSocket(host, port), sharedEvents)
        override fun createSocket(
                address: java.net.InetAddress?,
                port: Int,
                localAddress: java.net.InetAddress?,
                localPort: Int
        ): Socket =
                RecordingSocket(
                        delegate.createSocket(address, port, localAddress, localPort),
                        sharedEvents
                )
}

public class RecordingServerSocketFactory(
        private val delegate: ServerSocketFactory,
        private val sharedEvents: MutableList<SocketEvent>
) : ServerSocketFactory() {
        override fun createServerSocket(): ServerSocket =
                RecordingServerSocket(delegate.createServerSocket(), sharedEvents)
        override fun createServerSocket(port: Int): ServerSocket =
                RecordingServerSocket(delegate.createServerSocket(port), sharedEvents)
        override fun createServerSocket(port: Int, backlog: Int): ServerSocket =
                RecordingServerSocket(delegate.createServerSocket(port, backlog), sharedEvents)
        override fun createServerSocket(
                port: Int,
                backlog: Int,
                ifAddress: java.net.InetAddress?
        ): ServerSocket =
                RecordingServerSocket(
                        delegate.createServerSocket(port, backlog, ifAddress),
                        sharedEvents
                )
}
