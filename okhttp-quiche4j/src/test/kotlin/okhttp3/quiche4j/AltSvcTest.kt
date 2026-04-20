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

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class AltSvcTest {
  @Test fun `parses a single h3 entry`() {
    val now = 1_000_000L
    val entries = AltSvcEntry.parseHeader("""h3=":443"; ma=86400""", receivedAtMillis = now)
    assertThat(entries).hasSize(1)
    val e = entries.single()
    assertThat(e.protocolId).isEqualTo("h3")
    assertThat(e.host).isEqualTo("")
    assertThat(e.port).isEqualTo(443)
    assertThat(e.expiresAtMillis).isEqualTo(now + 86_400_000L)
    assertThat(e.persist).isFalse()
  }

  @Test fun `parses multiple comma-separated entries with different ports`() {
    val entries = AltSvcEntry.parseHeader("""h3=":443"; ma=3600, h2="alt.example.com:8443"; ma=60""")
    assertThat(entries).hasSize(2)
    val (h3, h2) = entries
    assertThat(h3.protocolId).isEqualTo("h3")
    assertThat(h3.host).isEqualTo("")
    assertThat(h3.port).isEqualTo(443)
    assertThat(h2.protocolId).isEqualTo("h2")
    assertThat(h2.host).isEqualTo("alt.example.com")
    assertThat(h2.port).isEqualTo(8443)
  }

  @Test fun `parses persist flag`() {
    val entries = AltSvcEntry.parseHeader("""h3=":443"; ma=86400; persist=1""")
    assertThat(entries.single().persist).isTrue()
  }

  @Test fun `clear returns empty`() {
    assertThat(AltSvcEntry.parseHeader("clear")).isEmpty()
    assertThat(AltSvcEntry.parseHeader("  CLEAR  ")).isEmpty()
  }

  @Test fun `InMemoryAltSvcCache roundtrip via snapshot`() {
    val cache = InMemoryAltSvcCache()
    val origin = AltSvcOrigin("https", "example.com", 443)
    val entries =
      listOf(
        AltSvcEntry("h3", "", 443, System.currentTimeMillis() + 60_000),
        AltSvcEntry("h2", "alt.example.com", 8443, System.currentTimeMillis() + 60_000),
      )
    cache.put(origin, entries)
    assertThat(cache.get(origin)).hasSize(2)

    val snap = cache.snapshot()
    assertThat(snap.keys).contains(origin)

    val loaded = InMemoryAltSvcCache().apply { load(snap) }
    assertThat(loaded.get(origin).map { it.protocolId }).containsExactly("h3", "h2")
  }

  @Test fun `expired entries are pruned on read`() {
    val cache = InMemoryAltSvcCache()
    val origin = AltSvcOrigin("https", "example.com", 443)
    cache.put(
      origin,
      listOf(
        AltSvcEntry("h3", "", 443, System.currentTimeMillis() - 1),
        AltSvcEntry("h2", "", 443, System.currentTimeMillis() + 60_000),
      ),
    )
    val live = cache.get(origin)
    assertThat(live).isNotEmpty()
    assertThat(live.map { it.protocolId }).containsExactly("h2")
  }

  @Test fun `remove clears origin`() {
    val cache = InMemoryAltSvcCache()
    val origin = AltSvcOrigin("https", "example.com", 443)
    cache.put(origin, listOf(AltSvcEntry("h3", "", 443, System.currentTimeMillis() + 60_000)))
    cache.remove(origin)
    assertThat(cache.get(origin)).isEmpty()
  }
}
