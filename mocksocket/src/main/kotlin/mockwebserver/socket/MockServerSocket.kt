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

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket as JavaNetSocket
import javax.net.ServerSocketFactory
import javax.net.SocketFactory
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

public class MockServerSocket(
        public val clock: Clock = Clock.SYSTEM,
        public val profile: NetworkProfile = NetworkProfile(),
        public val sharedEvents: MutableList<SocketEvent> = mutableListOf()
) {
  private val mutex = Mutex()
  private val stateChanged = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
  private fun notifyStateChanged(): Unit {
    stateChanged.tryEmit(Unit)
  }
  private val queue = mutableListOf<MockSocket>()
  public var backlog: Int = 50
  private var closed = false

  public suspend fun awaitState(predicate: () -> Boolean) {
    while (!predicate()) {
      kotlinx.coroutines.flow.merge(stateChanged, clock.monitor()).first()
    }
  }

  public suspend fun acceptSuspending(): MockSocket {
    recordEventSuspending(
            SocketEvent.AcceptStarting(clock.nanoTime(), Thread.currentThread().name, toString())
    )
    awaitState { queue.isNotEmpty() || closed }
    mutex.withLock {
      if (closed) throw IOException("closed")
      val socket = queue.removeAt(0)
      recordEventSuspending(
              SocketEvent.AcceptReturning(
                      clock.nanoTime(),
                      Thread.currentThread().name,
                      toString(),
                      socket.name
              )
      )
      return socket
    }
  }

  private suspend fun recordEventSuspending(event: SocketEvent): Unit {
    mutex.withLock {
      sharedEvents.add(event)
      notifyStateChanged()
    }
  }

  public suspend fun closeSuspending(): Unit {
    mutex.withLock {
      if (closed) return
      closed = true
      notifyStateChanged()
      for (socket in queue) {
        socket.closeSuspending()
      }
      queue.clear()
    }
  }

  public var localAddress: java.net.InetAddress? = null
  private var _localPort: Int = 0
  public var localPort: Int
    get() = if (_localPort == 0) 80 else _localPort
    set(value) {
      _localPort = value
    }

  public fun bind(endpoint: java.net.SocketAddress?, backlog: Int): Unit {
    if (endpoint is InetSocketAddress) {
      localPort = endpoint.port
      localAddress = endpoint.address
    }
    this.backlog = backlog
  }

  public fun bind(endpoint: java.net.SocketAddress?): Unit {
    bind(endpoint, 50)
  }

  public fun getInetAddress(): InetAddress = localAddress ?: InetAddress.getLoopbackAddress()
  public fun getLocalSocketAddress(): java.net.SocketAddress =
          InetSocketAddress(getInetAddress(), localPort)

  override fun toString(): String = "MockServerSocket[port=$localPort]"

  public fun enqueue(socket: MockSocket): Unit = runBlocking {
    mutex.withLock {
      if (closed || (backlog > 0 && queue.size >= backlog)) {
        socket.closeSuspending()
        return@withLock
      }
      queue.add(socket)
      notifyStateChanged()
    }
  }

  public fun asServerSocket(): java.net.ServerSocket = MockServerSocketAdapter(this)
}

public fun MockServerSocket.asServerSocketFactory(): ServerSocketFactory =
        object : ServerSocketFactory() {
          override fun createServerSocket() = this@asServerSocketFactory.asServerSocket()
          override fun createServerSocket(port: Int): ServerSocket =
                  this@asServerSocketFactory.asServerSocket().apply {
                    bind(InetSocketAddress(port))
                  }

          override fun createServerSocket(port: Int, backlog: Int): ServerSocket =
                  this@asServerSocketFactory.asServerSocket().apply {
                    bind(InetSocketAddress(port), backlog)
                  }

          override fun createServerSocket(
                  port: Int,
                  backlog: Int,
                  ifAddress: InetAddress?
          ): ServerSocket =
                  this@asServerSocketFactory.asServerSocket().apply {
                    bind(
                            if (ifAddress != null) InetSocketAddress(ifAddress, port)
                            else InetSocketAddress(port),
                            backlog
                    )
                  }
        }

public class MockSocketFactory(
        private val server: MockServerSocket,
        private val clock: Clock = server.clock,
        private val profile: NetworkProfile = server.profile
) : SocketFactory() {
  override fun createSocket(): JavaNetSocket {
    val (client, server) = MockSocket.pair(clock, profile, MemorySocketEventListener(server.sharedEvents))
    client.onConnect = { _, _ ->
      client.pair(server)
      this.server.enqueue(server)
    }
    return MockSocketAdapter(client)
  }

  override fun createSocket(host: String?, port: Int): JavaNetSocket {
    val (client, server) = MockSocket.pair(clock, profile, MemorySocketEventListener(server.sharedEvents))
    client.onConnect = { _, _ ->
      client.pair(server)
      this.server.enqueue(server)
    }
    val adapter = MockSocketAdapter(client)
    if (host != null) {
      adapter.connect(InetSocketAddress(host, port))
    }
    return adapter
  }

  override fun createSocket(
          host: String?,
          port: Int,
          localHost: InetAddress?,
          localPort: Int
  ): JavaNetSocket {
    val (client, server) = MockSocket.pair(clock, profile, MemorySocketEventListener(server.sharedEvents))
    client.onConnect = { _, _ ->
      client.pair(server)
      this.server.enqueue(server)
    }
    val adapter = MockSocketAdapter(client)
    if (localHost != null || localPort != 0) {
      adapter.bind(InetSocketAddress(localHost, localPort))
    }
    if (host != null) {
      adapter.connect(InetSocketAddress(host, port))
    }
    return adapter
  }

  override fun createSocket(address: InetAddress?, port: Int): JavaNetSocket {
    val (client, server) = MockSocket.pair(clock, profile, MemorySocketEventListener(server.sharedEvents))
    client.onConnect = { _, _ ->
      client.pair(server)
      this.server.enqueue(server)
    }
    val adapter = MockSocketAdapter(client)
    if (address != null) {
      adapter.connect(InetSocketAddress(address, port))
    }
    return adapter
  }

  override fun createSocket(
          address: InetAddress?,
          port: Int,
          localAddress: InetAddress?,
          localPort: Int
  ): JavaNetSocket {
    val (client, server) = MockSocket.pair(clock, profile, MemorySocketEventListener(server.sharedEvents))
    client.onConnect = { _, _ ->
      client.pair(server)
      this.server.enqueue(server)
    }
    val adapter = MockSocketAdapter(client)
    if (localAddress != null || localPort != 0) {
      adapter.bind(InetSocketAddress(localAddress, localPort))
    }
    if (address != null) {
      adapter.connect(InetSocketAddress(address, port))
    }
    return adapter
  }
}
