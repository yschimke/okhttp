# Stage 2 — HTTP/3 inside OkHttp core

**Goal:** make `OkHttpClient.Builder().protocols(listOf(HTTP_3, HTTP_2, HTTP_1_1))` "just work" without a bolted-on interceptor. H/3 lives alongside H/1.1 and H/2 inside `:okhttp`; the stage-1 `:okhttp-quiche4j` bridge module goes away.

This is a working design doc, not a final spec. It maps what the core already does, where H/3 needs to plug in, and what the minimum public API additions are. Scope is **integration strategy**, not codec implementation details (the stage-1 module already proves the codec path; stage 2 is about fitting it into core cleanly).

**Principles**

1. **Selective new public API.** Everything a *user* needs to turn on H/3 must already work from `OkHttpClient.Builder`. New surface is added only where the existing API genuinely can't express H/3 (e.g. discovery signals, QUIC engine injection).
2. **Internals are free to refactor.** `RealConnection`, `RealRoutePlanner`, `ConnectPlan`, `ExchangeCodec.Carrier` are all `okhttp3.internal`. We can extract abstractions without breaking consumers.
3. **One transport-agnostic path through the core.** `Exchange` / `ExchangeFinder` / the interceptor chain should not branch on H/3. The branch lives below `newCodec()`.
4. **Discovery is a first-class concern, not an ad-hoc hook.** H/3 discovery (HTTPS records, Alt-Svc) has to happen during route planning — before the "TCP vs UDP" decision.
5. **No hard dependency from `:okhttp` on quiche4j.** The native Rust/JNI dep is opt-in via a `Http3Engine` SPI. Shipping it in every OkHttp jar is a non-goal.

---

## What the core does today (validated against the tree)

### Transport layering

```
RealCall
  └── ExchangeFinder (Sequential | FastFallback)
        └── RoutePlanner.plan() → RoutePlanner.Plan
              ├── ReusePlan          (pooled connection)
              └── ConnectPlan         (fresh TCP + TLS)
                    ↓
                    RealConnection (socket + protocol + {http2Connection?})
                      └── newCodec() → Http1ExchangeCodec | Http2ExchangeCodec
```

- **`ExchangeCodec.Carrier`** ([`ExchangeCodec.kt:87-98`](../okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/http/ExchangeCodec.kt#L87-L98)) is the seam the codec talks through: `route`, `trackFailure`, `noNewExchanges`, `cancel`. `RealConnection` and `ConnectPlan` both implement it — the latter for CONNECT tunnels.

- **`ExchangeCodec.socket: okio.Socket`** ([`ExchangeCodec.kt:37`](../okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/http/ExchangeCodec.kt#L37)) is already abstract. H/2 doesn't use it for wire I/O (it goes through `Http2Stream`), so H/3 can provide a stub/adapter too.

- **`RealConnection.newCodec()`** ([`RealConnection.kt:268-283`](../okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/connection/RealConnection.kt#L268-L283)) branches `http2Connection != null ? Http2 : Http1`. This is the single H/2-vs-H/1.1 branch — the Exchange machinery above it is transport-agnostic.

- **ALPN is read post-handshake** ([`ConnectPlan.kt:~400`](../okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/connection/ConnectPlan.kt)) via `Platform.get().getSelectedProtocol(sslSocket)`. Protocol goes into `RealConnection.protocol`, `start()` then spins up `Http2Connection` if applicable.

- **`FastFallbackExchangeFinder`** ([`FastFallbackExchangeFinder.kt`](../okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/connection/FastFallbackExchangeFinder.kt)) already races multiple `Plan`s staggered 250 ms apart. It doesn't know or care whether the plans are TCP or UDP — it just calls `plan.connectTcp()` (misnamed) and `plan.connectTlsEtc()`. **This is the key seam for L4 happy-eyeballs between H/3 and H/2.**

### Protocol / Address plumbing

- `Protocol.HTTP_3("h3")` is **already defined** ([`Protocol.kt:95`](../okhttp/src/commonJvmAndroid/kotlin/okhttp3/Protocol.kt#L95)) and `Protocol.get(alpn)` already folds draft IDs (`h3-29`) back to `HTTP_3`.
- `Address.protocols` is passed end-to-end and is part of the pool identity ([`Address.kt:~187`](../okhttp/src/commonJvmAndroid/kotlin/okhttp3/Address.kt)). Pool entries negotiated for a different protocol list won't be reused — correct for H/3, but see *over-segmentation* below.
- `Route` is `Address × Proxy × InetSocketAddress` — transport-agnostic in shape. The `InetSocketAddress` is fine for UDP too.

### Concrete gaps we'll have to fix

1. **`Platform.alpnProtocolNames()`** ([`Platform.kt:222`](../okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/platform/Platform.kt#L222)) filters `HTTP_1_0` but **not** `HTTP_3`. Offering `h3` over TCP ALPN is wrong (`h3` is QUIC-only). Must add H/3 to the filter.
2. **`ConnectPlan`** is TCP-specific. Calls `route.address.socketFactory.createSocket()` (TCP), wraps `SSLSocket` over raw socket, runs ALPN over TCP-TLS. There's no seam for UDP. We need a sibling `Http3ConnectPlan` that shares `RoutePlanner.Plan` but not the TCP assumptions.
3. **`RealConnection`** deeply assumes TCP: `rawSocket: JavaNetSocket`, `javaNetSocket: JavaNetSocket`, `BufferedSocket`. It's either (a) refactored to hold a `Transport` abstraction (TCP vs QUIC), or (b) we introduce a sibling `Http3RealConnection` that implements the same `Connection` + `ExchangeCodec.Carrier` interfaces. **(b) is smaller and safer for an initial PR; (a) can follow.**
4. **No discovery surface.** There's nowhere in core to plug HTTPS records, Alt-Svc, or any "does this origin speak h3?" signal. Stage-1 has all this in `:okhttp-quiche4j`; it needs to migrate with minimal new public API.
5. **CONNECT proxies.** `Route.requiresTunnel()` assumes HTTPS-through-HTTP-proxy. QUIC over a CONNECT proxy needs MASQUE (RFC 9298), which is Stage-3. For Stage 2, a configured HTTP proxy disqualifies H/3 for that request — fall back to TCP transparently.

---

## Design

### 1. `Http3Engine` SPI in `:okhttp` core

A small interface in `okhttp3.internal.http3` (or, more likely, just `okhttp3`; TBD) that the core consults to open a QUIC connection. This is where the actual QUIC library lives — and it is the *one* place where H/3 support is opt-in.

```kotlin
// New public API — minimal.
interface Http3Engine {
  /**
   * Open (or reuse) a QUIC + H/3 transport to [peer], honouring [address]'s TLS config.
   * Returns a [Http3Session] the core can create codecs against. Blocks until the
   * handshake succeeds or throws [IOException] on any failure.
   */
  fun connect(
    address: Address,
    route: Route,
    timeouts: Timeouts,
    cancellation: CancellationSignal,
  ): Http3Session
}

// Core-visible handle to a connected QUIC session. Opaque to users.
interface Http3Session {
  val handshake: Handshake         // TLS 1.3 params, peer cert chain, ALPN
  val allocationLimit: Int         // from QUIC transport params
  val isHealthy: Boolean
  fun newStream(): Http3StreamHandle
  fun close(reason: String?)
}
```

The interface is deliberately tiny. `Http3StreamHandle` is a streams-oriented abstraction the core's `Http3ExchangeCodec` writes frames over; everything QUIC-specific (congestion control, packet pacing, pool per origin) is *inside* the engine.

**Where the implementation lives:** open question. Three options, in rough preference order:

- **(a) `:okhttp-quiche4j-engine` sibling module** — a thin wrapper over quiche4j providing `Http3Engine`. Shipped in the same repo, same release train, but a separate artifact so non-H/3 users don't pay the native cost. Renamed from stage-1's `:okhttp-quiche4j` to make clear the split: the *bridge* is gone; the *engine* is what remains.
- **(b) First-party in `:okhttp` with `quiche4j` as a compile dep** — simplest for users (turn on H/3 in protocols, it works), but every OkHttp consumer pays the native dep weight. Probably a non-starter for Android slim builds.
- **(c) Service-loader discovery** — `:okhttp` ships an interface, any `Http3Engine` on the classpath is auto-registered. No builder step. Smaller surface but more magic; hard to debug when it fails.

**Default recommendation: (a).** Matches how `okhttp-brotli` and `okhttp-coroutines` already coexist. The sibling module is thin because the non-trivial H/3 mechanics (codec, discovery, pooling policy) live in core; it only provides the QUIC transport.

### 2. `RoutePlanner` + new `Http3ConnectPlan`

`RealRoutePlanner.plan()` is already the policy point that decides "what to attempt next." Extend it:

1. Inspect `Address.protocols` — is `HTTP_3` requested at all?
2. Consult discovery signals: HTTPS record (ALPN list includes `h3`?), Alt-Svc cache (unexpired `h3` entry?). If neither and no per-request force tag, don't plan H/3.
3. Return either `Http3ConnectPlan` (UDP+QUIC handshake) or the existing `ConnectPlan` (TCP), or — when `fastFallback` is on and both are viable — queue both and let `FastFallbackExchangeFinder` race them.

The crucial observation: `FastFallbackExchangeFinder.launchTcpConnect()` only cares about `Plan.connectTcp()`. If we rename that to `Plan.openTransport()` (or keep the name; it's internal) and let `Http3ConnectPlan.openTransport()` do the QUIC handshake instead of a TCP connect, cross-transport racing is free. Whichever plan finishes its handshake first wins; the loser is cancelled just like a losing IP address today.

```kotlin
// Existing
interface RoutePlanner.Plan {
  val isReady: Boolean
  fun connectTcp(): ConnectResult       // rename? or keep for TCP path only
  fun connectTlsEtc(): ConnectResult
  fun handleSuccess(): RealConnection
  fun cancel()
  fun retry(): Plan?
}

// Addition: a plan whose connectTcp() is a UDP+QUIC handshake (no TLS phase; QUIC bakes
// it in). connectTlsEtc() is a no-op that returns success immediately.
internal class Http3ConnectPlan(
  private val engine: Http3Engine,
  route: Route,
  ...
) : RoutePlanner.Plan, ExchangeCodec.Carrier {
  override fun connectTcp(): ConnectResult { /* engine.connect(...) */ }
  override fun connectTlsEtc(): ConnectResult = ConnectResult(this)
  override fun handleSuccess(): RealConnection = Http3RealConnection(session, ...)
  ...
}
```

This keeps `Plan` as the unit of racing and keeps the finder transport-blind. The `connectTcp` name is a cleanup we can do in a follow-up PR; its semantics ("open the L4 transport") is already what's needed.

### 3. `RealConnection` — add a sibling, don't refactor the class (yet)

`RealConnection` is 400+ lines with TCP assumptions baked in (`rawSocket`, `javaNetSocket`, `BufferedSocket`, `SSLSocket` handshake extraction). Trying to make it polymorphic in one PR is a big diff with no net win for non-H/3 users.

Instead: introduce a `Http3RealConnection` that implements the public `Connection` interface and the internal `ExchangeCodec.Carrier`, and holds an `Http3Session` the way `RealConnection` holds an `Http2Connection`. The pool stores both in the same queue — they're polymorphic via `Connection`/`Carrier`.

Touch points:

| Site | Change |
|---|---|
| `RealConnectionPool.connections` | Type becomes `ConcurrentLinkedQueue<PooledConnection>` where `PooledConnection` is a shared supertype (existing `RealConnection` + new `Http3RealConnection`). May be able to reuse `Connection` directly — it exposes enough. |
| `RealConnectionPool.isMultiplexed` check | Move out of `RealConnection` onto the supertype. `Http3RealConnection.isMultiplexed = true`. |
| `RealConnection.isEligible()` coalescing check | Same logic moves to the supertype. QUIC supports coalescing exactly like H/2. |
| `RealConnection.newCodec()` | Only called on `RealConnection`; `Http3RealConnection.newCodec()` returns `Http3ExchangeCodec`. |
| `RealConnection.handshake` | Already nullable `Handshake`. QUIC provides it from the QUIC TLS context. |

Follow-up PR: once `Http3RealConnection` proves the abstraction, refactor `RealConnection` → `TcpRealConnection` and hoist the common parts to a base class. No user-visible change.

### 4. Discovery — new, minimal public API

Three pieces, all on `OkHttpClient.Builder`:

```kotlin
// 1. Alt-Svc cache (RFC 7838). Already designed in stage 1; just move to public API.
interface AltSvcCache {
  fun get(host: String, port: Int): List<AltSvcEntry>
  fun put(host: String, port: Int, entries: List<AltSvcEntry>)
  companion object { val InMemory: AltSvcCache = ... }  // default factory
}
fun Builder.altSvcCache(cache: AltSvcCache): Builder

// 2. HTTPS / SVCB record resolver (RFC 9460). Optional.
interface HttpsServiceRecordResolver {
  fun lookup(hostname: String): List<HttpsServiceRecord>
}
fun Builder.httpsServiceRecordResolver(resolver: HttpsServiceRecordResolver?): Builder

// 3. QUIC engine injection. If null (the default), H/3 is quietly disabled even when
// listed in protocols() — the same behaviour a user gets today.
fun Builder.http3Engine(engine: Http3Engine?): Builder
```

Three fields on `OkHttpClient`, three methods on `Builder`. No other new user-visible surface. Everything else reuses what's already there:

- The per-request force/fallback control is `Http3Preference` — but do we need to expose it publicly? Stage 1 has it as a request tag. For stage 2, keep it as a tag (already a generic mechanism; `Request.tag<Http3Preference>(...)`), import the tag class into the core package. Not a new concept.
- `EventListener.connectStart/End` already fires with a `Proxy` and `InetSocketAddress` — the consumer can't tell TCP from UDP today. We may want a new optional callback (`transportSelected`?) but that's not load-bearing; defer to Stage 3.

**Location of `HttpsAwareDns`:** stage 1 has a combined `Dns + HttpsAware` — it overloads `Dns.lookup()` to also trigger an HTTPS-record query in the same hop. This remains a user-composed class (it implements `Dns`, which is already public). No new API needed.

### 5. `Protocol` enum & ALPN filtering

Tiny but necessary:

- [`Platform.alpnProtocolNames`](../okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/platform/Platform.kt#L222) must filter `HTTP_3` out. TCP ALPN never advertises `h3`; the QUIC engine does its own ALPN inside the QUIC handshake.
- `Route.requiresTunnel()` unchanged. QUIC over HTTP proxy is out of scope.

---

## Phased plan

Each phase is a reviewable PR on top of the previous one. None of them break Stage 1 — `:okhttp-quiche4j` can keep working as a bridge while this lands piecemeal.

### Phase 2.0 — plumbing (no H/3 yet)
- Filter `HTTP_3` out of TCP ALPN (`Platform.alpnProtocolNames`).
- Introduce `Http3Engine` / `Http3Session` / `Http3StreamHandle` SPI (no wiring; just the interfaces).
- Introduce `AltSvcCache` + `HttpsServiceRecordResolver` in the public API (backed by `InMemoryAltSvcCache` default).
- Import `Http3Preference` (the tag class) from stage 1 into core.
- **Ship:** no functional change for users who don't opt in; H/3 still not actually negotiated anywhere.

### Phase 2.1 — `Http3RealConnection` + `Http3ExchangeCodec`
- Add the sibling connection class + its codec. Unit-testable against a stub `Http3Session`.
- Hoist `isMultiplexed`, coalescing-eligibility, and pool-storage to a shared supertype (likely via `Connection` + a small internal `PooledConnection` interface).
- Pool stores both TCP and QUIC connections in the same queue.
- Engine is still not invoked from the planner — no H/3 traffic yet.
- **Ship:** still no functional change; just the codec/connection skeleton with tests.

### Phase 2.2 — `Http3ConnectPlan` + planner integration
- Implement `Http3ConnectPlan : RoutePlanner.Plan, ExchangeCodec.Carrier`.
- `RealRoutePlanner.plan()` consults `Address.protocols` + `AltSvcCache` + (optional) `HttpsServiceRecordResolver` + per-request `Http3Preference` tag. Returns `Http3ConnectPlan` when h3 is viable.
- `FastFallbackExchangeFinder` races `Http3ConnectPlan` against `ConnectPlan` when both are viable (L4 happy-eyeballs for free).
- Alt-Svc parsing on every response (H/1.1, H/2, H/3) writes to the cache.
- **Ship:** end-to-end H/3 works for users who wire a `Http3Engine` via `Builder.http3Engine(...)`.

### Phase 2.3 — `:okhttp-quiche4j-engine` sibling module
- Replace the stage-1 interceptor module with a new `:okhttp-quiche4j-engine` that implements only `Http3Engine` + internal pool/I/O plumbing.
- All stage-1-specific types (`Quiche4jInterceptor`, `Quiche4jCallServer`, `QuicPooledConnection`, `QuicStream`) move behind the SPI or get deleted.
- `Quiche4jWebSocketFactory` — separate followup: the RFC 9220 WebSocket support needs a core-level hook into OkHttp's WebSocket path; stage-1's approach is a standalone `WebSocket.Factory` which still works. Likely belongs in the engine module until Stage 3 introduces a transport-aware `OkHttpClient.newWebSocket` path.

### Phase 2.4 — polish
- Refactor `RealConnection` → `TcpRealConnection` + common base. Nothing user-visible.
- Public API doc pass.
- Consume `HttpsServiceRecord.ipAddressHints` / `.port` / `.targetName` in route planning (stage-1 left this on the table).

---

## Over-segmentation of the pool (open question)

`Address.equalsNonHost()` includes `protocols` — so two clients with different protocol lists never share pool entries, even when the negotiated protocol is identical. Example:

- Client A: `protocols = [HTTP_3, HTTP_2, HTTP_1_1]`, negotiates H/2 against origin.
- Client B: `protocols = [HTTP_2, HTTP_1_1]`, same origin.

Today they'd share a pooled H/2 connection. With H/3 added to A's list, they won't. That's technically correct (pool identity changed) but wasteful.

One option: key the pool on `(negotiated protocol, non-protocol-fields of Address)` rather than on `Address.protocols` directly. Requires care — the old behaviour exists so that a user *can* segment pools by protocol intentionally. Park for Phase 2.4 or Stage 3.

---

## Where the WebSocket factory goes

Stage 1's `Quiche4jWebSocketFactory` is an `okhttp3.WebSocket.Factory` implementation that uses the same `Quiche4jEngine` to open an H/3 extended-CONNECT stream (RFC 9220). Stage 2 changes the right answer.

- Short term (Phase 2.3): the WebSocket factory moves to the engine module alongside `Http3Engine`. It's still a separate `WebSocket.Factory` the user composes, same shape as stage 1.
- Long term (Stage 3): `OkHttpClient.newWebSocket()` itself becomes transport-aware — the client picks H/3 extended CONNECT if H/3 is the selected transport, else H/2 RFC 8441 if available, else H/1 upgrade. That's the right place for it and requires public-API changes to `OkHttpClient` (Stage 3 territory).

---

## Summary of new public API (Phase 2.0 + 2.2 combined)

```kotlin
// One SPI and one helper interface.
interface Http3Engine { fun connect(...): Http3Session }
interface Http3Session { ... }

// Discovery.
interface AltSvcCache { ... ; companion object { val InMemory: AltSvcCache } }
interface HttpsServiceRecordResolver { fun lookup(...): List<HttpsServiceRecord> }
data class HttpsServiceRecord(...)
data class AltSvcEntry(...)

// OkHttpClient.Builder additions.
fun Builder.http3Engine(engine: Http3Engine?): Builder
fun Builder.altSvcCache(cache: AltSvcCache): Builder
fun Builder.httpsServiceRecordResolver(resolver: HttpsServiceRecordResolver?): Builder

// Request tag (move from okhttp-quiche4j to okhttp).
sealed class Http3Preference { ... }  // ForceOff / Force / Current
```

That's the entire user-visible surface Stage 2 adds. Every other change is internal.
