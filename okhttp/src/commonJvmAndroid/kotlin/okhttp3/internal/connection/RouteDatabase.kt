/*
 * Copyright (C) 2013 Square, Inc.
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

import okhttp3.NetworkIdentitySource
import okhttp3.Route

/**
 * A denylist of failed routes to avoid when creating a new connection to a target
 * address. OkHttp learns from its mistakes: if a connection attempt to a specific IP
 * address or proxy server fails, that failure is remembered and alternate routes are
 * preferred.
 *
 * Entries are scoped by the [NetworkIdentitySource]'s opaque network handle, so a
 * failure recorded on (say) a WiFi network doesn't suppress retries after the device
 * moves to cellular. With the default [NetworkIdentitySource.None] the source returns
 * null for every call and all entries share the same null key — identical to the
 * pre-2026 network-blind behaviour.
 */
class RouteDatabase
  @JvmOverloads
  constructor(
    private val networkIdentitySource: NetworkIdentitySource = NetworkIdentitySource.None,
  ) {
    private val _failedRoutes = mutableSetOf<Key>()

    /**
     * Read-only snapshot of failed routes, ignoring network scoping. Retained for tools
     * and tests that want to inspect the raw set.
     */
    val failedRoutes: Set<Route>
      @Synchronized get() = _failedRoutes.mapTo(mutableSetOf()) { it.route }

    /** Records a failure connecting to [failedRoute] on the current network. */
    @Synchronized fun failed(failedRoute: Route) {
      _failedRoutes.add(Key(failedRoute, networkIdentitySource.currentNetworkId()))
    }

    /** Records success connecting to [route] on the current network. */
    @Synchronized fun connected(route: Route) {
      _failedRoutes.remove(Key(route, networkIdentitySource.currentNetworkId()))
    }

    /**
     * Returns true if [route] has failed recently on the *current* network and should
     * be avoided. Returns false if the failure was recorded on a different network
     * (identified by a different [NetworkIdentitySource.currentNetworkId] value) —
     * that failure might not apply to the new network.
     */
    @Synchronized fun shouldPostpone(route: Route): Boolean =
      Key(route, networkIdentitySource.currentNetworkId()) in _failedRoutes

    private data class Key(
      val route: Route,
      val networkId: Any?,
    )
  }
