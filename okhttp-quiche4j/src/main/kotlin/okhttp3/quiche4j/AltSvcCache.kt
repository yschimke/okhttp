/*
 * Copyright (C) 2026 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package okhttp3.quiche4j

import java.util.concurrent.ConcurrentHashMap

/**
 * Stores `Alt-Svc` (RFC 7838) records keyed by origin, so a prior response's "you can also reach
 * me via h3 on :443" hint survives into later calls and lets [Quiche4jInterceptor] prefer
 * HTTP/3 on the next connect.
 *
 * Implementations must be thread-safe. Default implementation is [InMemoryAltSvcCache].
 *
 * For a persistent cache, implement these three methods on top of the target store (SQLite,
 * Room, a file, etc.) — nothing else is required from the interceptor's perspective. Bulk
 * snapshot/load support is a property of the in-memory impl only (see
 * [InMemoryAltSvcCache.snapshot] / [InMemoryAltSvcCache.load]); a real database doesn't need
 * "load everything at startup" because it can answer [get] directly from disk.
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
 * [snapshot] and [load] are provided here (rather than on the [AltSvcCache] interface) as a
 * convenience for callers who want to persist the cache across process restarts by writing
 * the snapshot to their own store. Implementations backed by an external database don't need
 * these methods — they can satisfy [get] from disk directly.
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
   * Snapshot of all currently stored non-expired entries, intended for serialization. The
   * companion [load] reverses this.
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
