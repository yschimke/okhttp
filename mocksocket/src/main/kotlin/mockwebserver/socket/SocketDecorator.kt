package mockwebserver.socket

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import okio.Buffer
import okio.Sink
import okio.Source
import okio.Timeout
import okio.buffer
import okio.sink
import okio.source

/**
 * Wraps a standard java.net.Socket with Okio sources and sinks that
 * emit SocketEvents to a provided listener. This Allows intercepting OkHttp's actual calls.
 */
public class SocketDecorator(
        private val delegate: Socket,
        private val listener: SocketEventListener,
        private val clock: Clock = AutoClock(),
        private val socketName: String = "Socket"
) : Socket() {

    private val mySource: Source = object : Source {
        private val delegateSource by lazy { delegate.source() }

        override fun read(sink: Buffer, byteCount: Long): Long {
            val startSize = sink.size
            val readCount = delegateSource.read(sink, byteCount)
            
            val payloadSize = sink.size - startSize
            val payload = if (payloadSize > 0) {
                val clone = Buffer()
                sink.copyTo(clone, startSize, payloadSize)
                clone
            } else null

            val event = if (readCount == -1L) {
                 SocketEvent.ReadEof(clock.nanoTime(), Thread.currentThread().name, socketName)
            } else {
                 SocketEvent.ReadSuccess(
                        clock.nanoTime(),
                        Thread.currentThread().name,
                        socketName,
                        readCount,
                        payload
                 )
            }
            listener.onEvent(event)
            return readCount
        }

        override fun timeout(): Timeout = delegateSource.timeout()

        override fun close() {
            delegateSource.close()
            listener.onEvent(SocketEvent.ShutdownInput(clock.nanoTime(), Thread.currentThread().name, socketName))
        }
    }

    private val mySink: Sink = object : Sink {
        private val delegateSink by lazy { delegate.sink() }

        override fun write(source: Buffer, byteCount: Long) {
            val payload = if (byteCount > 0) {
                val clone = Buffer()
                source.copyTo(clone, 0, byteCount)
                clone
            } else null

            delegateSink.write(source, byteCount)

            listener.onEvent(
                    SocketEvent.WriteSuccess(
                            clock.nanoTime(),
                            Thread.currentThread().name,
                            socketName,
                            byteCount,
                            clock.nanoTime(),
                            payload
                    )
            )
        }

        override fun flush() {
            delegateSink.flush()
        }

        override fun timeout(): Timeout = delegateSink.timeout()

        override fun close() {
            delegateSink.close()
            listener.onEvent(SocketEvent.ShutdownOutput(clock.nanoTime(), Thread.currentThread().name, socketName))
        }
    }

    private val myInputStream by lazy { mySource.buffer().inputStream() }
    private val myOutputStream by lazy { mySink.buffer().outputStream() }

    init {
        listener.onEvent(
                SocketEvent.Connect(
                        clock.nanoTime(),
                        Thread.currentThread().name,
                        socketName,
                        delegate.inetAddress?.hostName ?: "localhost",
                        delegate.port
                )
        )
    }

    override fun connect(endpoint: SocketAddress?) { delegate.connect(endpoint) }
    override fun connect(endpoint: SocketAddress?, timeout: Int) { delegate.connect(endpoint, timeout) }
    override fun bind(bindpoint: SocketAddress?) { delegate.bind(bindpoint) }
    override fun getInetAddress(): InetAddress? = delegate.inetAddress
    override fun getLocalAddress(): InetAddress? = delegate.localAddress
    override fun getPort(): Int = delegate.port
    override fun getLocalPort(): Int = delegate.localPort
    override fun getRemoteSocketAddress(): SocketAddress? = delegate.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress
    override fun getChannel(): java.nio.channels.SocketChannel? = delegate.channel
    override fun getInputStream(): InputStream = myInputStream
    override fun getOutputStream(): OutputStream = myOutputStream
    
    override fun close() {
        delegate.close()
        listener.onEvent(SocketEvent.Close(clock.nanoTime(), Thread.currentThread().name, socketName))
    }
    
    override fun setTcpNoDelay(on: Boolean) { delegate.tcpNoDelay = on }
    override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay
    override fun setSoLinger(on: Boolean, linger: Int) { delegate.setSoLinger(on, linger) }
    override fun getSoLinger(): Int = delegate.soLinger
    override fun sendUrgentData(data: Int) { delegate.sendUrgentData(data) }
    override fun setOOBInline(on: Boolean) { delegate.oobInline = on }
    override fun getOOBInline(): Boolean = delegate.oobInline
    override fun setSoTimeout(timeout: Int) { delegate.soTimeout = timeout }
    override fun getSoTimeout(): Int = delegate.soTimeout
    override fun setSendBufferSize(size: Int) { delegate.sendBufferSize = size }
    override fun getSendBufferSize(): Int = delegate.sendBufferSize
    override fun setReceiveBufferSize(size: Int) { delegate.receiveBufferSize = size }
    override fun getReceiveBufferSize(): Int = delegate.receiveBufferSize
    override fun setKeepAlive(on: Boolean) { delegate.keepAlive = on }
    override fun getKeepAlive(): Boolean = delegate.keepAlive
    override fun setTrafficClass(tc: Int) { delegate.trafficClass = tc }
    override fun getTrafficClass(): Int = delegate.trafficClass
    override fun setReuseAddress(on: Boolean) { delegate.reuseAddress = on }
    override fun getReuseAddress(): Boolean = delegate.reuseAddress
    override fun shutdownInput() { delegate.shutdownInput() }
    override fun shutdownOutput() { delegate.shutdownOutput() }
    override fun toString(): String = delegate.toString()
    override fun isConnected(): Boolean = delegate.isConnected
    override fun isBound(): Boolean = delegate.isBound
    override fun isClosed(): Boolean = delegate.isClosed
    override fun isInputShutdown(): Boolean = delegate.isInputShutdown
    override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown
}
