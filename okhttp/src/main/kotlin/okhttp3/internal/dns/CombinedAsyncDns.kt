/*
 * Copyright (C) 2024 Block, Inc.
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
package okhttp3.internal.dns

import java.net.InetAddress
import okhttp3.AsyncDns
import okhttp3.Call
import okio.IOException

internal class CombinedAsyncDns(val dnsList: List<AsyncDns>) : AsyncDns {
  override fun query(
    hostname: String,
    originatingCall: Call?,
    callback: AsyncDns.Callback,
  ) {
    var remainingQueries = dnsList.size

    dnsList.forEach {
      it.query(
        hostname = hostname,
        originatingCall = originatingCall,
        callback =
          object : AsyncDns.Callback {
            override fun onAddresses(
              hasMore: Boolean,
              hostname: String,
              addresses: List<InetAddress>,
            ) {
              synchronized(this) {
                if (!hasMore) {
                  remainingQueries -= 1
                }

                callback.onAddresses(remainingQueries == 0, hostname, addresses)
              }
            }

            override fun onFailure(
              hasMore: Boolean,
              hostname: String,
              e: IOException,
            ) {
              synchronized(this) {
                if (!hasMore) {
                  remainingQueries -= 1
                }

                callback.onFailure(remainingQueries == 0, hostname, e)
              }
            }
          },
      )
    }
  }
}
