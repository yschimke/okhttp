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
import java.net.InetAddress
import java.net.Socket as JavaNetSocket
import java.net.SocketAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.Sink
import okio.Socket as OkioSocket
import okio.Source
import okio.Timeout

internal class MockSocketAdapter(private val mockSocket: MockSocket) : JavaNetSocket(), OkioSocket {
  override fun getInputStream(): InputStream =
          object : InputStream() {
            override fun read(): Int {
              val buffer = Buffer()
              val read = runBlocking { mockSocket.readSuspending(buffer, 1) }
              return if (read == -1L) -1 else buffer.readByte().toInt() and 0xff
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
              val buffer = Buffer()
              val read = runBlocking { mockSocket.readSuspending(buffer, len.toLong()) }
              if (read == -1L) return -1
              buffer.read(b, off, read.toInt())
              return read.toInt()
            }

            override fun available(): Int =
                    0 // Not accurately supportable without blocking/mutex issues

            override fun close(): Unit = runBlocking { mockSocket.shutdownInputSuspending() }
          }

  override fun getOutputStream(): OutputStream =
          object : OutputStream() {
            override fun write(b: Int): Unit = runBlocking {
              val buffer = Buffer().writeByte(b)
              mockSocket.writeSuspending(buffer, 1)
            }

            override fun write(b: ByteArray, off: Int, len: Int): Unit = runBlocking {
              val buffer = Buffer().write(b, off, len)
              mockSocket.writeSuspending(buffer, len.toLong())
            }

            override fun close(): Unit = runBlocking { mockSocket.shutdownOutputSuspending() }
          }

  override fun getInetAddress(): InetAddress = mockSocket.remoteAddress
  override fun getLocalAddress(): InetAddress =
          mockSocket.localAddress ?: InetAddress.getLoopbackAddress()
  override fun getLocalPort(): Int = mockSocket.localPort
  override fun getPort(): Int = mockSocket.remotePort
  override fun close(): Unit = runBlocking { mockSocket.closeSuspending() }
  override fun shutdownInput(): Unit = runBlocking { mockSocket.shutdownInputSuspending() }
  override fun shutdownOutput(): Unit = runBlocking { mockSocket.shutdownOutputSuspending() }

  override fun isBound(): Boolean = true
  override fun isConnected(): Boolean = mockSocket.isConnected
  override fun isClosed(): Boolean = mockSocket.closed
  override fun isInputShutdown(): Boolean = mockSocket.inputShutdown
  override fun isOutputShutdown(): Boolean = mockSocket.outputShutdown

  override fun connect(endpoint: SocketAddress): Unit = runBlocking {
    mockSocket.connectSuspending(endpoint)
  }

  override fun connect(endpoint: SocketAddress, timeout: Int): Unit = runBlocking {
    mockSocket.connect(endpoint, TimeUnit.MILLISECONDS.toNanos(timeout.toLong()))
  }

  override fun bind(bindpoint: SocketAddress): Unit = runBlocking { mockSocket.bind(bindpoint) }

  override fun getRemoteSocketAddress(): SocketAddress =
          java.net.InetSocketAddress(mockSocket.remoteAddress, mockSocket.remotePort)

  override fun getLocalSocketAddress(): SocketAddress =
          java.net.InetSocketAddress(getLocalAddress(), mockSocket.localPort)

  override fun getReuseAddress(): Boolean = mockSocket.reuseAddress
  override fun setReuseAddress(reuse: Boolean): Unit {
    mockSocket.reuseAddress = reuse
  }

  override fun getKeepAlive(): Boolean = mockSocket.keepAlive
  override fun setKeepAlive(keepAlive: Boolean): Unit {
    mockSocket.keepAlive = keepAlive
  }

  override fun getReceiveBufferSize(): Int = mockSocket.receiveBufferSize
  override fun setReceiveBufferSize(size: Int): Unit {
    mockSocket.receiveBufferSize = size
  }

  override fun getSendBufferSize(): Int = mockSocket.sendBufferSize
  override fun setSendBufferSize(size: Int): Unit {
    mockSocket.sendBufferSize = size
  }

  override fun getSoTimeout(): Int = mockSocket.soTimeout
  override fun setSoTimeout(timeout: Int): Unit {
    mockSocket.soTimeout = timeout
  }

  override fun getOOBInline(): Boolean = mockSocket.oobInline
  override fun setOOBInline(oobInline: Boolean): Unit {
    mockSocket.oobInline = oobInline
  }

  override fun getSoLinger(): Int = mockSocket.soLinger
  override fun setSoLinger(on: Boolean, linger: Int): Unit {
    mockSocket.soLinger = if (on) linger else -1
  }

  override fun getTcpNoDelay(): Boolean = mockSocket.tcpNoDelay
  override fun setTcpNoDelay(tcpNoDelay: Boolean): Unit {
    mockSocket.tcpNoDelay = tcpNoDelay
  }

  // okio.Socket overrides
  override val source: Source =
          object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = runBlocking {
              mockSocket.readSuspending(sink, byteCount, timeout())
            }
            override fun timeout(): Timeout =
                    mockSocket.clock.newTimeout().apply {
                      if (mockSocket.soTimeout > 0)
                              timeout(mockSocket.soTimeout.toLong(), TimeUnit.MILLISECONDS)
                    }
            override fun close(): Unit = runBlocking { mockSocket.closeSuspending() }
          }

  override val sink: Sink =
          object : Sink {
            override fun write(source: Buffer, byteCount: Long): Unit = runBlocking {
              mockSocket.writeSuspending(source, byteCount, timeout())
            }
            override fun flush(): Unit {}
            override fun timeout(): Timeout =
                    mockSocket.clock.newTimeout().apply {
                      if (mockSocket.soTimeout > 0)
                              timeout(mockSocket.soTimeout.toLong(), TimeUnit.MILLISECONDS)
                    }
            override fun close(): Unit = runBlocking { mockSocket.closeSuspending() }
          }

  override fun cancel(): Unit {}

  override fun getChannel(): java.nio.channels.SocketChannel? = null
}
