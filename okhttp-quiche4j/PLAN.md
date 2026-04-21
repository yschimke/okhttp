# HTTP/3 via quiche4j — POC Plan

An HTTP/3 transport for OkHttp built on [quiche4j](https://github.com/yschimke/quiche4j)
(Java bindings over Cloudflare's quiche QUIC library). This module is an
opt-in `okhttp3.Interceptor` modeled loosely on
[cronet-transport-for-okhttp](https://github.com/google/cronet-transport-for-okhttp):
it short-circuits the OkHttp call chain, performs the fetch over QUIC, and
returns a normal `Response` so callers see `Protocol.HTTP_3`.

Unlike the Cronet bridge, quiche4j is *transport only*. We own DNS, UDP
sockets, TLS configuration, pooling, and timers.

---

## Status at a glance

**Stage 1 is feature-complete as a POC.** Live H/3 fetches work on the JVM,
in a Dockerized Caddy 2 container, on the Android emulator, and on real
Pixel 10a hardware, against both public endpoints and a self-signed local
server. TLS is verified through the OkHttpClient's own `X509TrustManager`
+ `HostnameVerifier`. Cancellation is push-based (no polling). Caching
wires through the real `CacheInterceptor`, including 304 revalidation.

24 JVM unit/integration tests pass · 1 Caddy container test passes ·
2 live instrumentation tests pass on a real device.

Legend: ✅ done · 🚧 in progress · ⏳ pending / out of POC scope.

### Stage 1 — bridge interceptor (this module)

| Area | Status | Notes |
|---|---|---|
| `Quiche4jInterceptor` short-circuits OkHttp chain | ✅ | |
| Inner chain with `BridgeInterceptor` + `CacheInterceptor` | ✅ | caching works via the real `CacheInterceptor` |
| `QuicPooledConnection` + per-connection I/O thread + task queue | ✅ | all quiche calls serialised on one thread per origin |
| `Quiche4jEngine` pool keyed by `(host, port)` | ✅ | reuse verified by `two sequential fetches reuse the pooled connection` |
| Streaming `QuicBodySource` (okio `Source`) | ✅ | pumps the pool on demand; no in-memory body buffering |
| `Response.handshake.peerCertificates` populated | ✅ | JNI `quiche_conn_peer_cert_chain` + Java-side `CertificateFactory` |
| TLS trust via the `OkHttpClient`'s `X509TrustManager` | ✅ | Platform default fallback; Android system trust store works |
| Hostname verification via the `OkHttpClient`'s `HostnameVerifier` | ✅ | `OkHostnameVerifier` default; synthetic `SSLSession` for custom verifiers |
| `Http3Preference` tag (`Force` / `ForceOff` / `Current`) | ✅ | per-request override; `Force.fallback` catches H/3 failure |
| Timeouts sourced from OkHttpClient | ✅ | `connectTimeout → handshake`, `readTimeout → max_idle` |
| Alt-Svc cache (RFC 7838) | ✅ | pluggable `AltSvcCache` + default `InMemoryAltSvcCache`; seeded on every response |
| HTTPS DNS records (RFC 9460) discovery | ✅ | dnsjava + optional `HttpsAwareDns` Dns wrapper on JVM |
| Android ABI cross-compile (`cargo-ndk`) | ✅ | `./gradlew :quiche4j-jni:jar -PandroidAbis=arm64-v8a,x86_64` |
| Android instrumentation test (`Quiche4jAndroidTest`) | ✅ | `h3FetchAgainstCloudflareQuic`, `explicitForcePreferenceUsesQuiche4j` green on Pixel 10a |
| EventListener coverage (dns/connect/secureConnect/headers/body/connection) | ✅ | matches the table in the per-request flow below |
| `CacheInterceptor` direct tests (cache hit / conditional 304) | ✅ | `Quiche4jCacheTest` — primes cache via MockWebServer, reads via Quiche4jInterceptor with a stub terminal that asserts it isn't reached on hit |
| Caddy container test (local H/3 server, full round trip) | ✅ | `CaddyQuiche4jTest` in `:container-tests`, runs with `-PcontainerTests=true` |
| Android HTTPS-record resolver (`android.net.DnsResolver`, API 29+) | ✅ | `AndroidHttpsServiceRecordResolver` in `:android-test` — raw DNS query + dnsjava parser |
| Push-based cancellation (`Call.cancel()` tears down QUIC stream) | ✅ | `CancellationHook` via `Call.addEventListener`; no polling |
| Duplex request body (`RequestBody.isDuplex() == true`) | ⏳ | design in [Duplex requests](#duplex-requests); pool already serialises quiche correctly so it's additive |
| HTTP CONNECT proxy | ⏳ | UDP doesn't tunnel; MASQUE (RFC 9298) is Stage-3 |
| WebSocket | ⏳ | out of H/3 scope |
| Trailers | ⏳ | ignored |

### Stage 2 — peer transport inside OkHttp core (internal refactor)

All pending — see the [Stage 2 section](#stage-2--peer-transport-inside-okhttp-core).

### Stage 3 — public API (breaking, 6.0)

All pending — see the [Stage 3 section](#stage-3--full-surface-support-major-version).

---

## Current public API

```kotlin
val interceptor = Quiche4jInterceptor.Builder()
  .httpsServiceRecordResolver(HttpsServiceRecordResolver.DEFAULT)  // optional
  .altSvcCache(InMemoryAltSvcCache())                              // optional; default in-memory
  .build()

val client = OkHttpClient.Builder()
  // Optional: discover H/3 eagerly; Dns.lookup() also starts an HTTPS-type query.
  .dns(HttpsAwareDns())
  // Optional per-request override — Force / ForceOff / Current
  .addInterceptor(interceptor)   // must be the LAST application interceptor
  .build()

val request = Request.Builder()
  .url("https://cloudflare-quic.com/")
  .tag<Http3Preference>(Http3Preference.Current)                    // optional
  .build()
```

Trust / hostname / timeouts / user-agent come from the `OkHttpClient`
itself — the interceptor doesn't have its own knobs for these because
`RealInterceptorChain` already exposes them (`connectTimeoutMillis`,
`readTimeoutMillis`, `x509TrustManagerOrNull`, `hostnameVerifier`) and
`BridgeInterceptor` in the inner chain handles `User-Agent`.

---

## Three-stage roadmap

The end state is HTTP/3 as a peer of H1/H2 inside OkHttp's transport core. We
get there in three stages, each deliverable on its own.

### Stage 1 — bridge interceptor (this module)

**Goal:** working HTTP/3 with zero change to OkHttp core. **✅ feature-complete as a POC** — see the [Status at a glance](#status-at-a-glance) above.

- Added as the **last application interceptor**. Cronet pattern.
- Short-circuits the call chain: the interceptor builds a `Response` directly
  from a QUIC fetch and never calls `chain.proceed()` unless a signal
  (`Http3Preference.ForceOff`, no-h3 HTTPS record, non-https URL) says fall through.
- Caching: because we short-circuit, `CacheInterceptor` never runs in the
  outer chain. To preserve cache semantics, we run a **nested chain** inside
  our interceptor containing `[BridgeInterceptor, CacheInterceptor,
  Quiche4jCallServer]`. The innermost interceptor (`Quiche4jCallServer`) is
  the one that actually talks QUIC; everything above it is stock OkHttp.
  Net effect: cache reads/writes work exactly as they do today.
- EventListener emitted from the bridge position:
  `dnsStart/End`, `connectStart/End`, `secureConnectStart/End`,
  `connectFailed`, `connectionAcquired/Released` (synthetic `Connection`),
  `requestHeadersStart/End`, `requestBodyStart/End`,
  `responseHeadersStart/End`, `responseBodyStart/End`,
  `responseFailed`. `callStart/End/Failed` fire from OkHttp core as usual.
- TLS: quiche's own `verify_peer` is disabled; we extract the peer cert
  chain via the JNI binding and run the OkHttpClient's own
  `X509TrustManager.checkServerTrusted` + hostname verification after the
  handshake. Failure → `SSLPeerUnverifiedException` and the connection is
  discarded before publishing to the pool.

### Stage 2 — peer transport inside OkHttp core

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
4. **HTTP/3 discovery.** See [HTTPS DNS records](#https-dns-records-rfc-9460)
   — the resolver moves into `RealRoutePlanner` so discovery governs all
   protocol selection.
5. **Connection pool.** Reuse `RealConnectionPool` keyed additionally by
   transport.

Public API additions (Stage 2, all additive):

- `Protocol.HTTP_3` / `Protocol.QUIC` — already defined.
- `OkHttpClient.Builder.quicEngineFactory(...)` — optional injection slot
  so callers can plug in alternative QUIC implementations
  (netty-incubator-codec-http3, msquic) alongside or instead of quiche4j.
- Optional `EventListener.altSvcDiscovered(...)` with a default no-op.

### Stage 3 — full surface support (major-version)

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
- **ECH (Encrypted Client Hello).** Pairs with the HTTPS DNS record's
  `echConfigList`. Blocked on quiche native-side ECH support (0.26.1
  exposes `Config.with_boring_ssl_ctx_builder` as an escape hatch; a
  native ECH API in quiche or a BoringSSL-ctx callback in quiche4j would
  unblock this).

---

## Architecture (Stage 1 — current)

```
┌──────────────────────────────────────────────────────────────┐
│ App interceptors (user)                                      │
│   …                                                          │
│   Quiche4jInterceptor   ◄── added last (app interceptor)    │
└──────────────────────────────────────────────────────────────┘
   │
   │ intercept(outerChain):
   │   · discovery: Http3Preference tag > HTTPS record > Alt-Svc
   │   · if no "h3" signal → chain.proceed() (still updates Alt-Svc)
   │   · otherwise run an INNER chain to preserve cache semantics
   ▼
   ┌────────────────────────────────────────────────────┐
   │ RealInterceptorChain (inner)                       │
   │   BridgeInterceptor        ← host, UA, cookies     │
   │   CacheInterceptor         ← cache reads/writes    │
   │   Quiche4jCallServer       ← the QUIC fetch        │
   └────────────────────────────────────────────────────┘
                                │
                                ▼
   ┌────────────────────────────────────────────────────┐
   │ Quiche4jEngine                                     │
   │   pool: ConcurrentHashMap<(host,port),             │
   │                           QuicPooledConnection>    │
   └────────────────────────────────────────────────────┘
                                │
                                ▼
   ┌────────────────────────────────────────────────────┐
   │ QuicPooledConnection  (per origin)                 │
   │   · DatagramSocket                                 │
   │   · quiche::Connection + h3::Connection            │
   │   · dedicated daemon I/O thread                    │
   │   · task queue (all quiche calls serialise here)   │
   │   · streams: ConcurrentHashMap<sid, QuicStream>    │
   └────────────────────────────────────────────────────┘
                                │
                                ▼
   QuicStream (per request)                   QuicBodySource (okio Source)
     · headersFuture                            ← consumer drains body queue
     · bodyQueue (Bytes | End | Error)
```

## Per-request flow (Stage 1)

| Step | OkHttp side | quiche4j side |
|---|---|---|
| 1 | Request enters `Quiche4jInterceptor.intercept` | — |
| 2 | Discovery: `Http3Preference` tag / HTTPS record / Alt-Svc | — |
| 3 | Inner chain: `BridgeInterceptor` adds Host/UA/cookies | — |
| 4 | `CacheInterceptor` checks cache; may short-circuit | — |
| 5 | Cache miss → `Quiche4jCallServer.intercept` called | — |
| 6 | DNS resolve via `chain.dns`, fires `dnsStart/End` | — |
| 7 | `connectStart/End`, `secureConnectStart/End` | pool `acquire`: `Quiche.connect`, pump packets until `isEstablished` |
| 8 | (between 7 and 9) | Java-side `TrustManager` + hostname verify |
| 9 | `connectionAcquired` with synthetic `QuicConnectionHandle` | — |
| 10 | `requestHeadersStart/End` | `h3.sendRequest(headers, fin=body==null)` — serialised via pool's task queue |
| 11 | `requestBodyStart/End` (if body) | one-shot `sendBody(..., fin=true)` |
| 12 | `responseHeadersStart/End` | wait on `headersFuture` fed by I/O thread's `h3.poll` |
| 13 | Build `Response` with `Protocol.HTTP_3` and populated `Handshake` | — |
| 14 | Caller drains `responseBody` via `QuicBodySource` | I/O thread queues body bytes; `onFinished` → EOF |
| 15 | `responseBodyEnd` + `connectionReleased` on body close | stream removed from pool's map; connection stays warm |

---

## HTTPS DNS records (RFC 9460) — ✅ wired

HTTPS/SVCB service records are the standard way to learn that an origin speaks
HTTP/3 *without* a prior HTTP/1.1 or HTTP/2 exchange. A single DNS query
returns, for `https://example.com/`:

- `priority` + `targetName` (alias or direct targets),
- `alpnIds` — includes `"h3"` if the server supports HTTP/3,
- `port`,
- `ipAddressHints` — pre-resolved A/AAAA hints, no second DNS round trip,
- `echConfigList` — the ECHConfig blob for Encrypted Client Hello.

### Two-source strategy

| Source | When it applies | Status |
|---|---|---|
| `android.net.dns.HttpsRecord` via `DnsResolver` | Android 36+ | ⏳ Tracked for integration with [square/okhttp#9383](https://github.com/square/okhttp/pull/9383)'s `AndroidDnsResolverDns`. |
| `android.net.DnsResolver.query(type = HTTPS)` | Android 29–35 | ⏳ Would fix the Android instrumentation gap below. |
| [dnsjava](https://github.com/dnsjava/dnsjava) `HTTPSRecord` / `SVCBRecord` | JVM | ✅ `DnsJavaHttpsServiceRecordResolver`. |

### Current API

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
  fun lookup(hostname: String): List<HttpsServiceRecord>
  companion object { val DEFAULT: HttpsServiceRecordResolver = DnsJavaHttpsServiceRecordResolver() }
}

/** Dns + HttpsAware: issues A/AAAA + HTTPS queries in parallel, caches the latter. */
class HttpsAwareDns(
  private val delegate: Dns = Dns.SYSTEM,
  private val resolver: HttpsServiceRecordResolver = HttpsServiceRecordResolver.DEFAULT,
  private val httpsLookupTimeoutMillis: Long = 500,
) : Dns, HttpsAware
```

### Android resolver

dnsjava's raw UDP/53 queries are blocked inside the Android app sandbox.
The fix lives in `:android-test` as
[`AndroidHttpsServiceRecordResolver`](../../android-test/src/androidTest/java/okhttp/android/test/quiche4j/AndroidHttpsServiceRecordResolver.kt):
it uses `android.net.DnsResolver.rawQuery(...)` (API 29+) to fetch the
type-65 DNS answer bytes and dnsjava's `Message` + `HTTPSRecord` parsers
to decode them. Works inside the sandbox; no extra permissions.

To ship this as part of the main module we'd need to split `okhttp-quiche4j`
into JVM + Android variants (or introduce a sibling `okhttp-quiche4j-android`
AAR) — the current single JVM module can't `import android.net.DnsResolver`
at compile time. Tracked; keeps Stage 1 simple.

### Decision precedence (implemented)

1. An explicit [`Http3Preference`](src/main/kotlin/okhttp3/quiche4j/Http3Preference.kt)
   tag on the request (`Force` / `ForceOff` / `Current`). Highest priority —
   bypasses all discovery.
2. HTTPS record advertises `"h3"`.
3. Alt-Svc cache has an unexpired `h3` entry for this origin.
4. No discovery configured at all → use H/3 (caller opted in by adding the interceptor).
5. Otherwise fall through to OkHttp's standard stack.

`ipAddressHints`, `port`, and `targetName` from the HTTPS record are **not
yet** consumed — tracked for Stage 2 route planning.

## Alt-Svc caching (RFC 7838) — ✅ wired

[`AltSvcCache`](src/main/kotlin/okhttp3/quiche4j/AltSvcCache.kt) is an
interface with a default in-memory implementation and `snapshot` / `load`
hooks for serializable implementations (persist to disk, share across
processes). The interceptor reads the cache on every call and writes to
it after every response (H/1.1, H/2, *or* H/3) by parsing the `Alt-Svc`
header — so an origin that "upgrades" mid-session is preferred via H/3
on the next connect without the caller doing anything.

## Duplex requests — ⏳ pending

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
the I/O thread, so duplex is a natural extension — not a rewrite.

---

## Testing

### Landed

| Test | Layer | Notes |
|---|---|---|
| `AltSvcTest` (7 tests) | unit | parser + `InMemoryAltSvcCache` roundtrip, expiry, `clear`, persist |
| `builder produces interceptor` | unit | builder API shape |
| `client accepts the interceptor` | unit | `OkHttpClient.addInterceptor` works |
| `live fetch against public h3 server with real TLS` | JVM integration | `Protocol.HTTP_3`, leaf CN contains `cloudflare`, 3-cert chain via platform trust |
| `two sequential fetches reuse the pooled connection` | JVM integration | `pooledConnectionCount == 1` after two calls |
| `HttpsAwareDns advertises h3 for cloudflare` | JVM integration | real dnsjava HTTPS record → `alpnIds = [h3, h2]` |
| `fall-through to outer chain when HTTPS record lacks h3` | unit | fake `HttpsAware` → sentinel interceptor confirms fall-through |
| `Alt-Svc cache seeded after a successful H3 fetch` | JVM integration | cloudflare's `Alt-Svc: h3=":443"; ma=…` populates cache |
| `seeded Alt-Svc drives h3 decision without HTTPS-record resolver` | JVM integration | cache overrides an HttpsAware that says "h2 only" |
| `Http3Preference ForceOff/Force/Current` (3 tests) | JVM integration | per-request override, including `Force.fallback` recover + propagate |
| `Http3Preference Force with fallback recovers when H3 fails` | JVM integration | 1s `connectTimeout` + unreachable UDP port → falls through, gets H/2 |
| `h3FetchAgainstCloudflareQuic` | Android (Pixel 10a + Pixel_4 emulator) | real H/3 fetch from Android; cert chain verified via platform TM |
| `explicitForcePreferenceUsesQuiche4j` | Android | `Force()` bypasses discovery |

### Required before M1 ships

- **`CacheInterceptor` direct coverage.** Today the inner chain runs but the
  tests don't assert `response.cacheResponse != null` on a conditional hit.
  - `cached response is served from CacheInterceptor on second fetch`
  - `cacheMiss then conditional hit on 304`
- **Container test with an H/3 MockServer-equivalent.** MockServer itself
  doesn't speak QUIC; run **Caddy 2.x** in a testcontainer with a trivial
  `Caddyfile` that serves static responses over HTTPS + HTTP/3. The
  `:container-tests` module already has the `testcontainers` plumbing.
  Pins the self-signed cert via a per-test `X509TrustManager` on the
  `OkHttpClient.Builder`. Benefits: no external network dependency,
  covers the request-body path we can't easily hit against Cloudflare's
  edge.
- **Android HTTPS-record resolver** — swap dnsjava for
  `android.net.DnsResolver.query(type = HTTPS)` so the Android test's
  HttpsAware-discovery path can be covered.

### Nice-to-have

- **Cancellation test.** Start a slow response, call `Call.cancel()`, assert
  the I/O thread tears down the QUIC connection and the body `read()` throws.
  Requires the cancellation-polling work that's still pending in the pool.
- **Concurrent-calls-to-different-hosts test.** Two simultaneous calls to
  `cloudflare-quic.com` and `www.google.com` must end up as two distinct
  pooled connections (`pooledConnectionCount == 2`) and both must succeed.
- **Large response streaming.** Body > 10 MiB; monitor heap so we're
  actually streaming (not silently buffering).

---

## Upstream quiche4j changes

### Already landed in the [fork](https://github.com/yschimke/quiche4j/tree/gradle-build)

- Gradle build with configuration caching (replaces the Maven build), plus
  `buildSrc/CargoBuild` and `CargoNdkBuild` `@CacheableTask`s for the Rust
  side.
- Android ABI cross-compile via cargo-ndk
  (`-PandroidAbis=arm64-v8a,x86_64,armeabi-v7a,x86`); .so files land at
  `lib/<abi>/libquiche_jni.so` in the JAR so AGP extracts them into the APK
  directly.
- JNI fix: `quiche_h3_send_request` was declared `long` on the Java side but
  returned nothing on the Rust side; stream IDs were uninitialized-stack
  garbage. Now returns the proper id (or an h3 error code).
- JNI fix: `Quiche.connect`'s Java-side error check
  (`ptr <= ErrorCode.SUCCESS`) flagged any `jlong` with bit 63 set as an
  error — legitimate arm64 heap addresses under ASLR. Narrowed to the
  small-negative error-code range; `ConnectionFailureException` now
  includes the value in its message.
- New JNI: `quiche_config_load_verify_locations_from_file` and
  `..._from_directory`, plus Java `ConfigBuilder` wrappers (retained for
  callers that want quiche's own verification path, though the okhttp
  transport disables it and verifies in Java).
- New JNI: `quiche_conn_peer_cert_chain` (returns `byte[][]` of DER-encoded
  certs) and `quiche_conn_application_proto` (negotiated ALPN).
- `NativeUtils`: on Android, fail fast with a descriptive error instead of
  retrying `System.loadLibrary` and silently swallowing `copyFileFromJAR`'s
  `IOException`.
- New JNI: `quiche_conn_stream_shutdown(streamId, direction, err)` returns
  an `int` status (was previously a void stub that `panic!`-ed on error).
  Powers the push-based cancellation path in the transport.

### Tracked as follow-ups

Most of these are "the upstream quiche Rust API has it, we just haven't
added the JNI binding + Java wrapper yet" — i.e. the same pattern as the
already-landed `peer_cert_chain` and `application_proto` bindings.

- **`conn.timeoutNanos()`.** Available as `Connection::timeout()`; add a
  JNI binding so we can move off `soTimeout`-based polling to a proper
  monotonic timer wheel.
- **Cipher suite + TLS version enums.** Punted — needs an upstream
  quiche change. `handshake.cipher()` is on a private field and quiche's
  public `Connection` has no accessor. Full proposal in
  [`upstream-issues/quiche-expose-negotiated-tls-params.md`](upstream-issues/quiche-expose-negotiated-tls-params.md),
  ready to raise on cloudflare/quiche. Today the `Handshake` we build is
  pinned to `TLS_1_3 / TLS_AES_128_GCM_SHA256`.
- **ECH support.** Punted — needs an upstream change to quiche itself,
  not just to quiche4j. Full investigation + proposed API options
  (`Config::set_ech_config_list` or a per-`Ssl` init callback) captured
  in
  [`upstream-issues/quiche-client-ech-hook.md`](upstream-issues/quiche-client-ech-hook.md),
  ready to paste into a cloudflare/quiche issue when someone's got the
  appetite. Our side parses `echConfigList` out of the HTTPS DNS record
  already — as soon as quiche supports feeding it in, the wiring is
  trivial.

---

## Milestones

| M | Scope | Status | Disruption |
|---|---|---|---|
| **M1** | This module: `Quiche4jInterceptor` + inner-chain caching + pooled engine + live fetch against a public H3 endpoint | ✅ feature-complete as POC | None in core |
| **M1-T1** | Direct `CacheInterceptor` coverage (hit + 304) + Caddy container test | ✅ | None in core |
| **M1-T2** | Android-ABI cross-compile in quiche4j-jni and an `:android-test` live H/3 fetch | ✅ | None in core |
| **M1.5** | HTTPS service record discovery (dnsjava on JVM + `DnsResolver`-backed resolver for Android 29+); Alt-Svc cache; per-request `Http3Preference` tag; push-based cancellation via `Call.addEventListener` | ✅ (Android resolver lives in `:android-test` until the module gains a JVM/Android variant split) | None in core |
| **M2** | Upstream quiche4j: `timeoutNanos`, cipher/TLS-version enums (needs quiche PR first), ECH config plumbing | ⏳ | None in core |
| **M3** | Stage 2 core refactor: extract `Carrier`, add `Http3ExchangeCodec`, route planning wired through the HTTPS-record resolver | ⏳ | Internal-only OkHttp changes |
| **M4** | Stage 2 API polish: `quicEngineFactory` slot, optional `altSvcDiscovered` event | ⏳ | Additive |
| **M5 (5.x → 6.0)** | Stage 3: `Handshake` transport field, ECH via HTTPS-record `echConfigList`, AsyncInterceptor SPI, WebTransport, migration, 0-RTT | ⏳ | Breaking |
