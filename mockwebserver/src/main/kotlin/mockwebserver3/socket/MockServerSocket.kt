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
import java.net.ServerSocket
import java.net.Socket as JavaNetSocket
import java.util.concurrent.locks.ReentrantLock
import javax.net.ServerSocketFactory
import javax.net.SocketFactory
import kotlin.concurrent.withLock

public class MockServerSocket(
  public val clock: Clock = Clock.SYSTEM,
  public val profile: NetworkProfile = NetworkProfile()
) : ServerSocket() {
  private val lock = ReentrantLock()
  private val condition = lock.newCondition()
  private val queue = mutableListOf<MockSocket>()
  private var closed = false

  // TODO set properly
  public var backlog: Int = 0

  override fun accept(): JavaNetSocket {
    println("accept")
    lock.withLock {
      while (queue.isEmpty()) {
        if (closed) throw IOException("closed")
        condition.await()
      }
      return queue.removeAt(0).asSocket()
    }
  }

  override fun close() {
    lock.withLock {
      if (closed) return
      closed = true
      condition.signalAll()
      for (socket in queue) {
        socket.close()
      }
      queue.clear()
    }
  }

  private var localPort = 0

  override fun bind(endpoint: java.net.SocketAddress?, backlog: Int) {
    if (endpoint is InetSocketAddress) {
      localPort = endpoint.port
    }
  }

  override fun bind(endpoint: java.net.SocketAddress?) {
    bind(endpoint, 50)
  }

  override fun getLocalPort(): Int = if (localPort == 0) 80 else localPort
  override fun getInetAddress(): InetAddress = InetAddress.getLoopbackAddress()
  override fun getLocalSocketAddress(): java.net.SocketAddress =
    InetSocketAddress(inetAddress, localPort)

  override fun toString(): String = "MockServerSocket[port=$localPort]"

  public fun enqueue(socket: MockSocket) {
    lock.withLock {
      if (closed) {
        socket.close()
        return
      }
      queue.add(socket)
      condition.signal()
    }
  }
}

public fun MockServerSocket.asServerSocketFactory(): ServerSocketFactory =
  object : ServerSocketFactory() {
    override fun createServerSocket() = this@asServerSocketFactory
    override fun createServerSocket(port: Int): ServerSocket = this@asServerSocketFactory.apply {
      bind(
        InetSocketAddress(port)
      )
    }

    override fun createServerSocket(port: Int, backlog: Int): ServerSocket =
      this@asServerSocketFactory.apply {
        this.backlog = backlog
        bind(InetSocketAddress(port))
      }

    override fun createServerSocket(
      port: Int,
      backlog: Int,
      ifAddress: InetAddress?
    ): ServerSocket = this@asServerSocketFactory.apply {
      this.backlog = backlog
      bind(if (ifAddress != null) InetSocketAddress(ifAddress, port) else InetSocketAddress(port))
    }
  }

public class MockSocketFactory(
  private val server: MockServerSocket,
  private val clock: Clock = server.clock,
  private val profile: NetworkProfile = server.profile
) : SocketFactory() {
  override fun createSocket(): JavaNetSocket {
    val (client, server) = MockSocket.pair(clock, profile)
    return MockSocketAdapter(client, onConnect = {
      client.pair(server)
      this.server.enqueue(server)
    })
  }

  override fun createSocket(host: String?, port: Int): JavaNetSocket {
    println("${Thread.currentThread()} createSocket($host,$port")
    val (client, server) = MockSocket.pair(clock, profile)
    this.server.enqueue(server)
    return MockSocketAdapter(client)
  }
  override fun createSocket(
    host: String?,
    port: Int,
    localHost: InetAddress?,
    localPort: Int
  ): JavaNetSocket {
    // TODO set local host and port
    println("${Thread.currentThread()} createSocket($host,$port")
    val (client, server) = MockSocket.pair(clock, profile)
    this.server.enqueue(server)
    return MockSocketAdapter(client)
  }

  override fun createSocket(address: InetAddress?, port: Int): JavaNetSocket {
    // TODO set local host and port
    println("${Thread.currentThread()} createSocket($address,$port")
    val (client, server) = MockSocket.pair(clock, profile)
    this.server.enqueue(server)
    return MockSocketAdapter(client)
  }
  override fun createSocket(
    address: InetAddress?,
    port: Int,
    localAddress: InetAddress?,
    localPort: Int
  ): JavaNetSocket {
    // TODO set local host and port
    println("${Thread.currentThread()} createSocket($address,$port")
    val (client, server) = MockSocket.pair(clock, profile)
    this.server.enqueue(server)
    return MockSocketAdapter(client)
  }
}
