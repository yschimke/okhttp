/*
 * Copyright (c) 2022 Square, Inc.
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
 *
 */

package okhttp3

import java.net.InetAddress
import okhttp3.internal.dns.BlockingAsyncDns
import okhttp3.internal.dns.CombinedAsyncDns
import okio.IOException

/**
 * An async domain name service that resolves IP addresses for host names.
 *
 * The main implementations will typically be implemented using specific DNS libraries such as
 *  * Android DnsResolver
 *  * OkHttp DnsOverHttps
 *  * dnsjava Resolver
 *
 * Implementations of this interface must be safe for concurrent use.
 */
@ExperimentalOkHttpApi
interface AsyncDns {
  /**
   * Query DNS records for `hostname`, in the order they are received.
   */
  fun query(
    hostname: String,
    originatingCall: Call?,
    callback: Callback,
  )

  /** Returns a [Dns] that blocks until all async results are available. */
  open fun asBlocking(): Dns {
    return BlockingAsyncDns(this)
  }

  /**
   * Callback to receive results from the DNS Queries.
   */
  @ExperimentalOkHttpApi
  interface Callback {
    /**
     * Invoked on a successful result from a single lookup step.
     *
     * @param addresses a non-empty list of addresses
     * @param hasMore true if another call to onAddresses or onFailure will be made
     */
    fun onAddresses(
      hasMore: Boolean,
      hostname: String,
      addresses: List<InetAddress>,
    )

    /**
     * Invoked on a failed result from a single lookup step.
     *
     * @param hasMore true if another call to onAddresses or onFailure will be made
     */
    fun onFailure(
      hasMore: Boolean,
      hostname: String,
      e: IOException,
    )
  }

  /**
   * Class of DNS addresses, such that clients that treat these differently, such
   * as attempting IPv6 first, can make such decisions.
   */
  @ExperimentalOkHttpApi
  enum class DnsClass(val type: Int) {
    IPV4(TYPE_A),
    IPV6(TYPE_AAAA),
  }

  @ExperimentalOkHttpApi
  companion object {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28

    /**
     * Returns an [AsyncDns] that queries all [sources] in parallel, and calls
     * the callback for each partial result.
     *
     * The callback will be passed `hasMore = false` only when all sources
     * have no more results.
     *
     * @param sources one or more AsyncDns sources to query.
     */
    fun union(vararg sources: AsyncDns): AsyncDns {
      return if (sources.size == 1) {
        sources.first()
      } else {
        CombinedAsyncDns(sources.toList())
      }
    }
  }
}
