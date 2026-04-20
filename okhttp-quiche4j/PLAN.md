# HTTP/3 via quiche4j — POC Plan

An HTTP/3 transport for OkHttp built on [quiche4j](https://github.com/yschimke/quiche4j)
(Java bindings over Cloudflare's quiche QUIC library). This module is an
opt-in `okhttp3.Interceptor` modeled loosely on
[cronet-transport-for-okhttp](https://github.com/google/cronet-transport-for-okhttp):
it short-circuits the OkHttp call chain, performs the fetch over QUIC, and
returns a normal `Response` so callers see `Protocol.HTTP_3`.

Unlike the Cronet bridge, quiche4j is *transport only*. We own DNS, UDP
sockets, TLS configuration, pooling, and timers.

## Status

Stage 1 scaffolding (POC). Not yet wired for general traffic. See the Stage 1 section below for the exact surface.

## Three-stage roadmap

The end state is HTTP/3 as a peer of H1/H2 inside OkHttp's transport core. We
get there in three stages, each deliverable on its own.

### Stage 1 — Bridge interceptor (this module)

**Goal:** working HTTP/3 with zero change to OkHttp core.

- New opt-in module `okhttp-quiche4j`, added as the **last application
  interceptor**. Cronet pattern.
- Short-circuits the call chain: the interceptor builds a `Response` directly
  from a QUIC fetch and never calls `chain.proceed()`.
- Caching: because we short-circuit, `CacheInterceptor` never runs in the
  outer chain. To preserve cache semantics, we run a **nested chain** inside
  our interceptor containing `[BridgeInterceptor, CacheInterceptor,
  Quiche4jCallServer]`. The innermost interceptor (`Quiche4jCallServer`) is
  the one that actually talks QUIC; everything above it is stock OkHttp.
  Net effect: cache reads/writes work exactly as they do today.
- EventListener: we emit what we reasonably can from the bridge position —
  `dnsStart/End`, `connectStart/End`, `secureConnectStart/End`,
  `connectFailed`, `connectionAcquired/Released` (synthetic `Connection`),
  `requestHeadersStart/End`, `requestBodyStart/End`,
  `responseHeadersStart/End`, `responseBodyStart/End`, `requestFailed`,
  `responseFailed`, `canceled`. `callStart/End/Failed` are emitted by OkHttp
  as usual.

**Public API (Stage 1):**

```kotlin
val interceptor = Quiche4jInterceptor.Builder()
  .userAgent("okhttp/5.x quiche4j")
  .maxIdleTimeoutMillis(30_000)
  .build()

val client = OkHttpClient.Builder()
  .addInterceptor(interceptor)   // must be the LAST application interceptor
  .build()
```

**Known gaps in Stage 1 (documented, not fixed):**

- `Handshake.peerCertificates()` is empty — quiche4j doesn't surface the peer
  cert chain yet. See [upstream changes](#upstream-quiche4j-changes) below.
- `networkResponse`, `cacheResponse`, `priorResponse` on the returned
  `Response` are set by the nested chain only when applicable
  (cache hits/conditional hits produce `cacheResponse`).
- `RequestBody.isDuplex() == true` is unsupported — see [duplex requests][]
  below for the plan.
- Response body is drained to memory (no streaming `okio.Source` yet).
- No connection pooling — each call gets its own `DatagramSocket` and QUIC
  connection.
- No Alt-Svc discovery or HTTPS-record resolution yet (see next section).

[duplex requests]: #duplex-requests
- No HTTP CONNECT proxy (UDP doesn't tunnel through HTTP CONNECT). Direct
  connections only.
- No WebSocket.
- Trailers are ignored.

### Stage 2 — Peer transport inside OkHttp core

**Goal:** `OkHttpClient.Builder().protocols(listOf(HTTP_3, HTTP_2, HTTP_1_1))`
"just works." No explicit interceptor needed; caller sees `Protocol.HTTP_3`
on responses.

Internal refactors (no interceptor-level bridge any more):

1. **Extract `Carrier` role from `RealConnection`.** `ExchangeCodec.Carrier`
   is currently implemented only by TCP-backed types. Introduce a
   `QuicCarrier` wrapping a shared `DatagramChannel` + `Http3Connection`.
2. **Add `Http3ExchangeCodec`.** Mirrors `Http2ExchangeCodec`: header and
   body translation between `Exchange` and quiche4j. Slots into the existing
   `Exchange` state machine so EventListener events fire unchanged from the
   core.
3. **Route planning.** `RealRoutePlanner` learns UDP/QUIC routes. Plans TCP
   and QUIC in parallel; whichever finishes the handshake first wins
   (happy-eyeballs over L4).
4. **HTTP/3 discovery.** See the dedicated section below — this should be wired
   early (Stage 1→2 boundary), not late. In order of precedence:
   explicit `Request.tag(Protocol.HTTP_3)`; HTTPS DNS records (RFC 9460,
   SVCB/HTTPS); Alt-Svc cache (RFC 7838).
5. **Connection pool.** Reuse `RealConnectionPool` keyed additionally by
   transport.

Public API (Stage 2, all additive):

- `Protocol.HTTP_3` / `Protocol.QUIC` — already defined.
- `OkHttpClient.Builder.quicEngineFactory(...)` — optional injection slot
  so callers can plug in alternative QUIC implementations
  (netty-incubator-codec-http3, msquic) alongside or instead of quiche4j.
- Optional `EventListener.altSvcDiscovered(...)` with a default no-op.

### Stage 3 — Full surface support (major-version)

Areas where we'd want OkHttp's **public** API to evolve for HTTP/3 to be
first-class. These are the candidates, not commitments.

- **`Handshake` rework.** Add `transport: TransportProtocol`, `alpn: String?`,
  `isZeroRtt: Boolean`, optionally `quicTransportParameters`. Requires new
  factory for QUIC-derived handshakes.
- **Async / non-blocking interceptor SPI.** `suspend fun intercept(chain)`
  variant registered via `addAsyncInterceptor(...)`. Existing blocking
  interceptors keep working (bridged). Benefits QUIC particularly: the
  quiche4j event loop is naturally async; we stop burning a thread per
  in-flight request.
- **WebTransport.** quiche4j already ships `WtNative`/`WtEventListener`.
  Expose a peer of `WebSocket`:
  `OkHttpClient.newWebTransport(request, listener)`.
- **HTTP/3 datagrams** (RFC 9297).
- **Connection migration.** `Connection.route()` → `Connection.routes()`; new
  `EventListener.connectionMigrated(...)`.
- **First-class `QuicConfig`** (sibling of `ConnectionSpec`): max idle
  timeout, initial flow-control windows, datagram support, 0-RTT opt-in,
  qlog.

## Architecture (Stage 1)

```
┌──────────────────────────────────────────────────────────────┐
│ App interceptors (user)                                      │
│   …                                                          │
│   Quiche4jInterceptor   ◄── added last (app interceptor)    │
└──────────────────────────────────────────────────────────────┘
   │
   │ intercept(outerChain):
   │   runs an INNER chain to preserve cache semantics
   ▼
   ┌────────────────────────────────────────────────────┐
   │ RealInterceptorChain(inner)                        │
   │   BridgeInterceptor(cookieJar)                     │
   │   CacheInterceptor(client.cache)                   │
   │   Quiche4jCallServer                               │
   └────────────────────────────────────────────────────┘
                                │ proceed()
                                ▼
   ┌────────────────────────────────────────────────────┐
   │ Quiche4jEngine (shared across interceptor calls)   │
   │   DatagramChannel + selector thread                │
   │   QuicConnectionPool keyed on (host, port)         │
   │   Scheduled timer wheel → conn.onTimeout()         │
   │   Executor for h3.poll() dispatches                │
   └────────────────────────────────────────────────────┘
```

## Per-request flow (Stage 1)

| Step | OkHttp side | quiche4j side |
|---|---|---|
| 1 | Request enters `Quiche4jInterceptor.intercept` | — |
| 2 | Inner chain: `BridgeInterceptor` adds host/UA/cookies | — |
| 3 | `CacheInterceptor` checks cache; may short-circuit | — |
| 4 | Cache miss → `Quiche4jCallServer.intercept` called | — |
| 5 | DNS resolve via `client.dns`, fire `dnsStart/End` | — |
| 6 | `connectStart/End`, `secureConnectStart/End` | `Quiche.connect`, pump packets until `isEstablished` |
| 7 | `requestHeadersStart/End` | `h3.sendRequest(headers, fin=body==null)` |
| 8 | `requestBodyStart/End` | loop `h3.sendBody` |
| 9 | `responseHeadersStart/End` | await `onHeaders` via blocking queue |
| 10 | Build `Response` with `Protocol.HTTP_3` | — |
| 11 | `responseBodyStart/End`, via `QuicBodySource` | drain `onData` → `h3.recvBody` into okio |
| 12 | `CacheInterceptor` stores response if cacheable | — |
| 13 | `connectionReleased` | return connection to pool |

## HTTPS DNS records (RFC 9460) — wire this early

HTTPS/SVCB service records are the standard way to learn that an origin speaks
HTTP/3 *without* a prior HTTP/1.1 or HTTP/2 exchange. A single DNS query
returns, for `https://example.com/`:

- `priority` + `targetName` (alias or direct targets),
- `alpnIds` — includes `"h3"` if the server supports HTTP/3,
- `port`,
- `ipAddressHints` — pre-resolved A/AAAA hints, no second DNS round trip,
- `echConfigList` — the ECHConfig blob for Encrypted Client Hello.

Because this single record tells us *both* "does this origin speak H3?" and
"which ECH config should we use?", it should be part of the early plan rather
than retrofitted later.

### Two-source strategy

| Source | When it applies | Notes |
|---|---|---|
| `android.net.dns.HttpsRecord` via `DnsResolver` | Android 36+ | Already being wired up in [square/okhttp#9383](https://github.com/square/okhttp/pull/9383) as `AndroidDnsResolverDns`. Exposes everything native including ECH. |
| [dnsjava](https://github.com/dnsjava/dnsjava) `HTTPSRecord` / `SVCBRecord` | Older Android, JVM, anywhere without platform DNS-over-TLS | Pure-Java parser; we issue the HTTPS-type DNS query (via DoH using `okhttp-dnsoverhttps`, or plain UDP via dnsjava) and deserialize the record. |

### Minimal internal API

```kotlin
data class HttpsServiceRecord(
  val priority: Int,
  val targetName: String,
  val port: Int?,
  val alpnIds: List<String>,
  val ipAddressHints: List<InetAddress>,
  val echConfigList: ByteString?,
)

interface HttpsServiceRecordResolver {
  suspend fun lookup(hostname: String): List<HttpsServiceRecord>
}
```

Implementations:
- `AndroidDnsResolverHttpsServiceRecordResolver` — Android 36+.
- `DnsJavaHttpsServiceRecordResolver` — everywhere else; uses dnsjava's
  `HTTPSRecord` parser over either a UDP resolver or DoH via
  `okhttp-dnsoverhttps`.

### Stage plan with HTTPS records in the loop

- **Stage 1** (this module): no discovery. Caller adds `Quiche4jInterceptor`
  because they know the origin speaks H3.
- **Stage 1.5** (done in this module): two opt-in paths:
  1. `Quiche4jInterceptor.Builder.httpsServiceRecordResolver(...)` — explicit
     resolver; the interceptor calls it inline.
  2. `.dns(HttpsAwareDns(...))` on the `OkHttpClient` — a `Dns` wrapper that
     fires the HTTPS record lookup in parallel with the A/AAAA lookup and
     implements `HttpsAware` so the interceptor can read the cached result.
     Preferred: one DNS query serves both code paths. Mirrors PR 9383's
     `AndroidDnsResolverDns` on Android 36.

  In either case: if no record advertises `"h3"`, the interceptor calls
  `chain.proceed(request)` and OkHttp's H/1.1 or H/2 stack handles the
  request. `ipAddressHints`, `port`, and `targetName` from the record are
  still unused (tracked for Stage 2 route planning).
- **Stage 2**: the resolver moves into `RealRoutePlanner` so it governs all
  protocol selection — H3 vs H2-over-TCP races only fire when the HTTPS
  record advertises both.
- **Stage 3**: `echConfigList` pairs with ECH support (PR 9383's
  `EchAware` / `EchConfig` surface) and is passed through to the quiche TLS
  config — requires an upstream quiche4j JNI addition to set
  `quiche_config_set_ech_config_list`.

### Behavior when the record is missing

Absence of an HTTPS record doesn't mean "no H3" — many origins speak H3 but
haven't published HTTPS records. Fallback order:
1. An explicit [`Http3Preference`](src/main/kotlin/okhttp3/quiche4j/Http3Preference.kt)
   tag on the request (`Force` / `ForceOff` / `Current`). Highest priority —
   bypasses all discovery.
2. Use HTTPS record if present and `alpnIds` contains `"h3"`.
3. Use an Alt-Svc cache populated from prior H1/H2 responses on the same
   origin.
4. Skip H3 for this request.

Alt-Svc caching is wired in the current module via
[`AltSvcCache`](src/main/kotlin/okhttp3/quiche4j/AltSvcCache.kt) — an
interface with a default [`InMemoryAltSvcCache`][mem] and
[`snapshot`][snap] / [`load`][load] hooks for serializable implementations
(persist to disk, share across processes). The interceptor reads the cache
on every call and writes to it after every response (H/1.1, H/2, or H/3)
by parsing the `Alt-Svc` header, so an origin that "upgrades" mid-session
is preferred via H/3 on the next connect without the caller doing
anything.

[mem]: src/main/kotlin/okhttp3/quiche4j/AltSvcCache.kt
[snap]: src/main/kotlin/okhttp3/quiche4j/AltSvcCache.kt
[load]: src/main/kotlin/okhttp3/quiche4j/AltSvcCache.kt

## Duplex requests

OkHttp's duplex mode (`RequestBody.isDuplex() == true`, used by gRPC and
other streaming APIs) hands the request body writer back to the caller and
lets it interleave with response reads. A duplex exchange can read a byte
of response before finishing the request body — the two halves are
genuinely concurrent.

Today's `Quiche4jCallServer` cannot do this: `intercept()` writes the
request body in one shot (`body.writeTo(buffer); sendBody(…, fin=true)`)
and only then waits for response headers. The caller has no writer handle,
and even if they did, the call thread is the only thread driving the
pipeline for that exchange.

What has to change for duplex:

1. **Writes happen on the I/O thread, not the caller's.** `Quiche4jCallServer`
   submits `sendBody` tasks to `QuicPooledConnection`'s task queue (same
   serialization guarantee as everything else that touches quiche), driven
   by writes the caller makes to a `QuicRequestBodySink` (okio `Sink`).
   This is the symmetric counterpart of the existing `QuicBodySource` on
   the response side.
2. **Return headers before the request body completes.** `intercept()`
   returns as soon as `headersFuture` resolves. The caller then writes
   their duplex body into the sink while also reading the response body
   from the source — two independent streams over the same `QuicStream`.
3. **Flow control.** `sendBody` can return short writes; the sink must
   block / park until `STREAM_DATA_BLOCKED` clears. Simplest: the I/O thread
   re-drives pending sink writes each loop iteration after receiving
   packets.
4. **FIN on close.** `QuicRequestBodySink.close()` schedules a final
   `sendBody(..., fin=true)` onto the I/O thread.
5. **`chain.request().body!!.writeTo(sink)` runs on a worker thread** —
   caller code expects a blocking sink, so OkHttp dispatches duplex writes
   on a Dispatcher thread. We don't need our own thread pool; we just
   accept writes from whatever thread the caller provides.

The existing pool design already assumes quiche calls are serialized onto
the I/O thread, so duplex is a natural extension — not a rewrite. Main
open question is back-pressure signalling: do we expose it via a latch or
just spin on `sendBody` returning DONE?

This is blocked on the streaming `QuicBodySource` already being in place
(done in M1) plus the pool refactor (also done), so it can land in M1.5
alongside the HTTPS-record resolver if gRPC use cases come up, or be
deferred to M3 if not.

## Testing

The interceptor has subtle moving parts (inner chain, per-connection I/O
thread, pooled reuse, EventListener timing). The plan is explicit about
what each layer should cover.

### Unit + live tests (already in `src/test`)

- `builder produces interceptor` — the `Builder` is well-formed.
- `client accepts the interceptor` — `OkHttpClient.Builder.addInterceptor` works.
- `live fetch against public h3 server with real TLS` — end-to-end request
  against cloudflare-quic.com using the system CA bundle. Asserts
  `Protocol.HTTP_3`, non-empty peer certificate chain, leaf CN contains
  `cloudflare`.
- `two sequential fetches reuse the pooled connection` — verifies
  `Quiche4jEngine.pooledConnectionCount()` stays at 1 across two calls.

### Required before M1 ships

- **`CacheInterceptor` directly covered.** Today our test passes through the
  inner chain but doesn't assert caching actually happened. Add:
  - `cached response is served from CacheInterceptor on second fetch` — first
    call hits the network (with appropriate `cache-control` header), second
    returns `response.cacheResponse != null`, `response.networkResponse == null`.
    Asserts that `CacheInterceptor` in the **nested** chain is the one
    serving the hit, not OkHttp's outer chain.
  - `cacheMiss then conditional hit on 304` — exercises `If-None-Match` /
    304 path through the nested `CacheInterceptor`.
- **Container test with an H/3 MockServer-equivalent.** MockServer itself
  doesn't speak QUIC, so we run **Caddy 2.x** in a testcontainer with a
  trivial `Caddyfile` that serves static responses over HTTPS + HTTP/3.
  The container test module (`:container-tests`) already has the
  `testcontainers` plumbing we can lean on. Sketch:
  ```kotlin
  @Testcontainers
  class Quiche4jCaddyTest {
    @Container
    val caddy = GenericContainer(DockerImageName.parse("caddy:2"))
      .withExposedPorts(443, 443/udp)
      .withClasspathResourceMapping("Caddyfile", "/etc/caddy/Caddyfile", READ_ONLY)
      .withClasspathResourceMapping("cert.pem", "/etc/caddy/cert.pem", READ_ONLY)
      .withClasspathResourceMapping("key.pem", "/etc/caddy/key.pem", READ_ONLY)
  }
  ```
  The test pins the self-signed cert via `Quiche4jInterceptor.Builder.trustedCaPemFile(...)`.
  Benefits: no external network dependency, covers the request-body path we
  can't easily hit against Cloudflare's edge.
- **Android instrumentation test** (`:android-test`). The module already
  runs ALPN / TLS / cert-pinning tests on real Android devices; adding
  H/3 is a natural fit. Blockers to solve first:
  1. **Cross-compile quiche4j-jni for Android ABIs.** Currently only
     `linux-x86_64` is built. Need `aarch64-linux-android`,
     `x86_64-linux-android`, `armv7-linux-androideabi`. Simplest path:
     `cargo-ndk` driven from the same Gradle `CargoBuild` task.
  2. **AAR packaging.** `NativeUtils` looks for
     `/native-libs/{platform}/libquiche_jni.so` on the JVM classpath.
     Android expects `jniLibs/<abi>/libquiche_jni.so` inside the AAR.
     A small Gradle Android-library wrapper re-homes the `.so`s.
  3. **Test itself**: one request to cloudflare-quic.com (emulator.wtf has
     network access), assert `Protocol.HTTP_3` and a non-empty cert chain,
     same shape as the JVM live test.

### Nice-to-have

- **Cancellation test.** Start a slow response, call `Call.cancel()`, assert
  the I/O thread tears down the QUIC connection and the body `read()` throws.
  Requires the cancellation-polling work that's still pending in the pool.
- **Concurrent-calls-to-different-hosts test.** Two simultaneous calls to
  `cloudflare-quic.com` and `www.google.com` must end up as two distinct
  pooled connections (`pooledConnectionCount == 2`) and both must succeed.
- **Large response streaming.** Body > 10 MiB; monitor heap so we're
  actually streaming (not silently buffering).

## Upstream quiche4j changes

Already landed in the [fork](https://github.com/yschimke/quiche4j) (branch
`gradle-build`):

- Fixed `quiche_h3_send_request` JNI binding — was declared `long` on the
  Java side but had no return type in Rust, so stream IDs were
  uninitialized-stack garbage.
- Added `Native.quiche_config_load_verify_locations_from_file` and
  `..._from_directory`, plus `ConfigBuilder.loadVerifyLocationsFromFile` /
  `loadVerifyLocationsFromDirectory`, so the Java side can point quiche at
  a system CA bundle. `Quiche4jEngine` auto-probes common Linux/macOS paths
  (`/etc/ssl/certs/ca-certificates.crt`, `/etc/pki/tls/certs/ca-bundle.crt`,
  `/etc/ssl/cert.pem`, `/etc/ssl/certs`) so real TLS verification works with
  no caller configuration.

Tracked as follow-ups:

- Expose the peer certificate chain so we can fully populate `Handshake`.
- Expose cipher suite and TLS version as enums compatible with OkHttp.
- Expose `streamShutdown(streamId, ...)`.
- Expose `conn.timeoutNanos()` for a proper monotonic timer wheel.
- Expose ECH config loading (`quiche_config_set_ech_config_list`) so the
  HTTPS-record `echConfigList` from Stage 3 can be fed in.

## Milestones

| M | Scope | Disruption |
|---|---|---|
| M1 | This module: `Quiche4jInterceptor` + inner-chain caching + engine + live fetch against a public H3 endpoint | None in core |
| M1.5 | HTTPS service record discovery via dnsjava (and Android 36's native `HttpsRecord` on new platforms); opt-in fall-through to H1/H2 when `alpnIds` doesn't contain `"h3"` | None in core |
| M2 | Upstream quiche4j work; fully populate `Handshake`; Alt-Svc cache | None in core |
| M3 | Stage 2 core refactor: extract `Carrier`, add `Http3ExchangeCodec`, route planning wired through the HTTPS-record resolver | Internal-only OkHttp changes |
| M4 | Stage 2 API polish: `quicEngineFactory` slot, optional `altSvcDiscovered` event | Additive |
| M5 (5.x → 6.0) | Stage 3: Handshake transport field, ECH via HTTPS-record `echConfigList`, AsyncInterceptor, WebTransport, migration, 0-RTT | Breaking |

Testing milestones woven into the above:
- **M1-T1** (blocks M1): direct `CacheInterceptor` coverage + Caddy container
  test covering request body + large body.
- **M1-T2** (blocks M1): Android-ABI cross-compile in quiche4j-jni and an
  `:android-test` live H/3 fetch.
