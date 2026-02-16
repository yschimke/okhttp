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

import java.net.Socket as JavaNetSocket
import java.nio.channels.SocketChannel
import okio.IOException
import okio.buffer

internal class MockSocketAdapter(
  private val mockSocket: MockSocket,
  private val onConnect: () -> Unit = {}
) : JavaNetSocket() {
  override fun getInputStream() = mockSocket.source.buffer().inputStream()
  override fun getOutputStream() = mockSocket.sink.buffer().outputStream()
  override fun getInetAddress() = mockSocket.remoteAddress
  override fun getLocalAddress() = mockSocket.localAddress
  override fun getLocalPort() = mockSocket.localPort
  override fun getPort() = mockSocket.remotePort
  override fun close() = mockSocket.close()
  override fun shutdownInput() = mockSocket.shutdownInput()
  override fun shutdownOutput() = mockSocket.shutdownOutput()

  override fun isBound(): Boolean = true // Always bound in MockSocket pair

  override fun isConnected(): Boolean = mockSocket.isConnected

  override fun isClosed(): Boolean = mockSocket.closed

  override fun isInputShutdown(): Boolean = mockSocket.inputShutdown

  override fun isOutputShutdown(): Boolean = mockSocket.outputShutdown

  override fun connect(endpoint: java.net.SocketAddress) {
    if (isConnected) {
      throw IOException("Already Connected")
    }

    mockSocket.connect(endpoint)
    onConnect()
  }

  override fun connect(endpoint: java.net.SocketAddress, timeout: Int) {
    if (isConnected) {
      throw IOException("Already Connected")
    }

    mockSocket.connect(endpoint, timeout)
    onConnect()
  }

  override fun bind(bindpoint: java.net.SocketAddress) {
    if (isConnected) {
      throw IOException("Already Connected")
    }

    mockSocket.bind(bindpoint)
  }

  override fun getRemoteSocketAddress(): java.net.SocketAddress =
          java.net.InetSocketAddress(mockSocket.remoteAddress, mockSocket.remotePort)

  override fun getLocalSocketAddress(): java.net.SocketAddress =
          java.net.InetSocketAddress(mockSocket.localAddress, mockSocket.localPort)

  override fun getReuseAddress(): Boolean {
    return mockSocket.reuseAddress
  }

  override fun setReuseAddress(p0: Boolean) {
    mockSocket.reuseAddress = p0
  }

  override fun getTrafficClass(): Int {
    TODO()
  }

  override fun setTrafficClass(p0: Int) {
    TODO()
  }

  override fun getKeepAlive(): Boolean {
    return mockSocket.keepAlive
  }

  override fun setKeepAlive(p0: Boolean) {
    mockSocket.keepAlive = p0
  }

  override fun getReceiveBufferSize(): Int {
    return mockSocket.receiveBufferSize
  }

  override fun setReceiveBufferSize(p0: Int) {
    mockSocket.receiveBufferSize = p0
  }

  override fun getSendBufferSize(): Int {
    return mockSocket.sendBufferSize
  }

  override fun setSendBufferSize(p0: Int) {
    mockSocket.sendBufferSize = p0
  }

  override fun getSoTimeout(): Int {
    return mockSocket.soTimeout
  }

  override fun setSoTimeout(p0: Int) {
    mockSocket.soTimeout = p0
  }

  override fun getOOBInline(): Boolean {
    return mockSocket.oobInline
  }

  override fun setOOBInline(p0: Boolean) {
    mockSocket.oobInline = p0
  }

  override fun sendUrgentData(p0: Int) {
    super.sendUrgentData(p0)
  }

  override fun getSoLinger(): Int {
    return mockSocket.soLinger
  }

  override fun setSoLinger(p0: Boolean, p1: Int) {
    mockSocket.soLinger = p1
  }

  override fun getTcpNoDelay(): Boolean {
    return mockSocket.tcpNoDelay
  }

  override fun setTcpNoDelay(p0: Boolean) {
    mockSocket.tcpNoDelay = p0
  }

  override fun getChannel(): SocketChannel? {
    TODO()
  }
}
