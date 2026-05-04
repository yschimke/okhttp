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
package okhttp3.internal.connection

import java.io.IOException
import okhttp3.AltSvcOrigin
import okhttp3.Http3Engine
import okhttp3.Http3Session
import okhttp3.Route
import okhttp3.internal.closeQuietly
import okhttp3.internal.concurrent.TaskRunner
import okhttp3.internal.concurrent.withLock
import okhttp3.internal.connection.RoutePlanner.ConnectResult
import okhttp3.internal.connection.RoutePlanner.Plan

/**
 * A [RoutePlanner.Plan] that opens an HTTP/3 session by handing the [route] to the
 * client's installed [Http3Engine]. Sibling to [ConnectPlan] — same `Plan` interface,
 * same place in the finder's life cycle, but the transport underneath is QUIC + UDP
 * rather than TCP + TLS.
 *
 * Failure handling preserves the design agreed in the stage-2 strawman: a handshake
 * failure is recorded in [RouteDatabase] (scoped by the client's
 * [okhttp3.NetworkIdentitySource], so a WiFi failure doesn't follow the device to
 * cellular) and the `h3` entry is evicted from [okhttp3.AltSvcCache] so subsequent
 * planners don't keep re-attempting it until a fresh response re-advertises it.
 *
 * Not a [ExchangeCodec.Carrier]: H/3 doesn't have a CONNECT-tunnel phase that would
 * need to carry a codec before the connection itself is built. The real carrier for
 * any H/3 exchange is [Http3RealConnection], returned by [handleSuccess].
 */
internal class Http3ConnectPlan(
  private val taskRunner: TaskRunner,
  private val connectionPool: RealConnectionPool,
  private val engine: Http3Engine,
  private val call: RealCall,
  internal val route: Route,
  private val connectionListener: ConnectionListener,
) : Plan {
  @Volatile private var session: Http3Session? = null

  @Volatile private var canceled: Boolean = false

  override val isReady: Boolean
    get() = session != null

  override fun connectTcp(): ConnectResult {
    check(session == null) { "QUIC handshake already done" }

    call.plansToCancel += this
    try {
      call.eventListener.connectStart(call, route.socketAddress, route.proxy)
      connectionPool.connectionListener.connectStart(route, call)

      val newSession = engine.connect(call.client, route)
      session = newSession

      call.eventListener.secureConnectEnd(call, newSession.handshake)
      call.eventListener.connectEnd(call, route.socketAddress, route.proxy, okhttp3.Protocol.HTTP_3)
      call.client.routeDatabase.connected(route)
      return ConnectResult(plan = this)
    } catch (e: IOException) {
      // Teach the client not to retry this route on this network, and strip any
      // "origin advertises h3" signal from the Alt-Svc cache — without eviction the
      // next planner run would just re-attempt H/3 and fail the same way.
      call.client.routeDatabase.failed(route)
      val origin =
        AltSvcOrigin(
          scheme = route.address.url.scheme,
          host = route.address.url.host,
          port = route.address.url.port,
        )
      call.client.altSvcCache.remove(origin)

      call.eventListener.connectFailed(call, route.socketAddress, route.proxy, okhttp3.Protocol.HTTP_3, e)
      connectionPool.connectionListener.connectFailed(route, call, e)
      return ConnectResult(plan = this, throwable = e)
    } finally {
      call.plansToCancel -= this
    }
  }

  /**
   * No-op for H/3: QUIC bundles TLS 1.3 into the same handshake, so there's no
   * separate "TLS phase" to run. Present only to satisfy [Plan].
   */
  override fun connectTlsEtc(): ConnectResult = ConnectResult(plan = this)

  override fun handleSuccess(): PooledConnection {
    val openedSession = session!!
    val connection =
      Http3RealConnection(
        taskRunner = taskRunner,
        connectionPool = connectionPool,
        session = openedSession,
        connectionListener = connectionListener,
      )
    connection.withLock {
      connection.idleAtNs = System.nanoTime()
      connectionPool.put(connection)
    }
    call.eventListener.connectionAcquired(call, connection)
    connectionListener.connectionAcquired(connection, call)
    return connection
  }

  override fun cancel() {
    canceled = true
    session?.closeQuietly()
  }

  /**
   * H/3 has no TLS-ConnectionSpec fallback, so there's no "retry this same attempt
   * with different parameters" option. A failed H/3 plan is just failed — the
   * [RoutePlanner] falls through to TCP.
   */
  override fun retry(): Plan? = null
}
