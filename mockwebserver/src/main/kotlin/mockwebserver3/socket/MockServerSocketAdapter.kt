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

import java.net.InetAddress
import java.net.ServerSocket as JavaNetServerSocket
import java.net.Socket as JavaNetSocket
import java.net.SocketAddress
import kotlinx.coroutines.runBlocking

internal class MockServerSocketAdapter(private val delegate: MockServerSocket) :
        JavaNetServerSocket() {
    override fun accept(): JavaNetSocket = runBlocking { delegate.acceptSuspending().asSocket() }

    override fun close(): Unit = runBlocking { delegate.closeSuspending() }

    override fun bind(endpoint: SocketAddress?): Unit {
        delegate.bind(endpoint)
    }

    override fun bind(endpoint: SocketAddress?, backlog: Int): Unit {
        delegate.bind(endpoint, backlog)
    }

    override fun getLocalPort(): Int = delegate.localPort
    override fun getInetAddress(): InetAddress = delegate.getInetAddress()
    override fun getLocalSocketAddress(): SocketAddress = delegate.getLocalSocketAddress()
    override fun isClosed(): Boolean = false // TODO track in delegate

    override fun toString(): String = delegate.toString()
}
