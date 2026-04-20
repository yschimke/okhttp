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
 * Implementations must be thread-safe. Default implementation is [InMemoryAltSvcCache]; for
 * persistence across process restarts, write a wrapper that delegates [get]/[put]/[remove] to
 * the in-memory cache and also mirrors the state to disk (see [snapshot] / [load]).
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

  /**
   * Snapshot of all currently stored non-expired entries, intended for serialization. The
   * companion [load] reverses this.
   */
  fun snapshot(): Map<AltSvcOrigin, List<AltSvcEntry>>

  /** Populate the cache from a previously taken [snapshot]. Entries with expired `ma` are dropped. */
  fun load(entries: Map<AltSvcOrigin, List<AltSvcEntry>>)
}

/**
 * Default in-memory [AltSvcCache]. Thread-safe; expired entries are pruned lazily on read.
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

  override fun snapshot(): Map<AltSvcOrigin, List<AltSvcEntry>> =
    map
      .mapValues { (_, v) -> v.filterNot { it.isExpired } }
      .filterValues { it.isNotEmpty() }

  override fun load(entries: Map<AltSvcOrigin, List<AltSvcEntry>>) {
    for ((origin, values) in entries) {
      val live = values.filterNot { it.isExpired }
      if (live.isNotEmpty()) map[origin] = live
    }
  }
}
