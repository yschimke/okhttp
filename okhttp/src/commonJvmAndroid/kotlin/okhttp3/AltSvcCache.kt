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

import java.util.concurrent.ConcurrentHashMap

/**
 * Stores `Alt-Svc` (RFC 7838) records keyed by origin, so a prior response's
 * "you can also reach me via h3 on :443" hint survives into later calls. When HTTP/3 is
 * enabled on an [OkHttpClient], the route planner consults this cache to prefer HTTP/3
 * on the next connection to an origin that advertised it.
 *
 * Implementations must be thread-safe. The default is [InMemoryAltSvcCache]; plug in a
 * different implementation via [OkHttpClient.Builder.altSvcCache] to persist entries
 * across process restarts (SQLite, Room, flat file, etc.).
 */
interface AltSvcCache {
  /** All non-expired entries for [origin], or an empty list. */
  fun get(origin: AltSvcOrigin): List<AltSvcEntry>

  /**
   * Replace all entries for [origin]. Passing an empty list (e.g. from `Alt-Svc: clear`)
   * clears the cache for that origin; it is equivalent to calling [remove].
   */
  fun put(
    origin: AltSvcOrigin,
    entries: List<AltSvcEntry>,
  )

  fun remove(origin: AltSvcOrigin)
}

/**
 * Default in-memory [AltSvcCache]. Thread-safe; expired entries are pruned lazily on read.
 *
 * [snapshot] and [load] are provided on this implementation (not on the [AltSvcCache]
 * interface) as a convenience for callers who want to persist the cache across process
 * restarts by writing the snapshot to their own store. A persistent cache backed by a
 * database doesn't need these — it can satisfy [get] from disk directly.
 */
class InMemoryAltSvcCache : AltSvcCache {
  private val map = ConcurrentHashMap<AltSvcOrigin, List<AltSvcEntry>>()

  override fun get(origin: AltSvcOrigin): List<AltSvcEntry> {
    val current = map[origin] ?: return emptyList()
    val live = current.filterNot { it.isExpired }
    if (live.size != current.size) {
      if (live.isEmpty()) map.remove(origin, current) else map[origin] = live
    }
    return live
  }

  override fun put(
    origin: AltSvcOrigin,
    entries: List<AltSvcEntry>,
  ) {
    if (entries.isEmpty()) {
      map.remove(origin)
    } else {
      map[origin] = entries.toList()
    }
  }

  override fun remove(origin: AltSvcOrigin) {
    map.remove(origin)
  }

  /**
   * Snapshot of all currently stored non-expired entries, intended for serialization.
   * The companion [load] reverses this.
   */
  fun snapshot(): Map<AltSvcOrigin, List<AltSvcEntry>> =
    map
      .mapValues { (_, v) -> v.filterNot { it.isExpired } }
      .filterValues { it.isNotEmpty() }

  /** Populate the cache from a previously taken [snapshot]. Entries with expired `ma` are dropped. */
  fun load(entries: Map<AltSvcOrigin, List<AltSvcEntry>>) {
    for ((origin, values) in entries) {
      val live = values.filterNot { it.isExpired }
      if (live.isNotEmpty()) map[origin] = live
    }
  }
}
