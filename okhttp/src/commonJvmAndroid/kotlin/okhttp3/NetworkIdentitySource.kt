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
package okhttp3

/**
 * Source of an opaque identifier for the current network. OkHttp scopes its failure
 * memory by the returned value so a route that failed on one network (say, a WiFi with
 * a blocked UDP port) is retried when the device moves to another network (cellular)
 * instead of being postponed indefinitely.
 *
 * Install via [OkHttpClient.Builder.networkIdentitySource]. The default [None] returns
 * null and preserves OkHttp's traditional network-blind failure memory.
 *
 * Typical Android implementation:
 *
 * ```kotlin
 * class ConnectivityManagerNetworkIdentitySource(
 *   private val connectivityManager: ConnectivityManager,
 * ) : NetworkIdentitySource {
 *   override fun currentNetworkId(): Any? =
 *     connectivityManager.activeNetwork?.networkHandle
 * }
 * ```
 *
 * Implementations must be thread-safe and should return fast (no I/O) — the source is
 * consulted on every route-database read/write.
 *
 * ## Opaque contract
 *
 * OkHttp treats the returned value as opaque and compares it only via `equals` /
 * `hashCode`. Two calls that return equal values are treated as the same network;
 * unequal values are treated as distinct. `null` means "no identity available" and
 * is treated uniformly: entries recorded under a null identity match only other null
 * identities, not any specific network.
 */
fun interface NetworkIdentitySource {
  /**
   * Returns an opaque handle for the current network, or null if unknown or not
   * applicable.
   */
  fun currentNetworkId(): Any?

  companion object {
    /**
     * A source that always returns null, preserving OkHttp's pre-2026
     * network-blind failure-memory behaviour. This is the default on
     * [OkHttpClient.Builder] when no other source is installed.
     */
    @JvmField
    val None: NetworkIdentitySource = NetworkIdentitySource { null }
  }
}
