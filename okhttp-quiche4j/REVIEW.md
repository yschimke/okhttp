# okhttp-quiche4j code review

A snapshot of known rough edges — output of a structured code review over the
module source at the time of the breather (commit `9e1b6dac5`).

Each item is keyed by `file:line`, one paragraph of rationale. Items marked
**FIXED** have had follow-up commits; those still in the list are parked
bugs, tests we lack, or kdoc improvements.

## Must-fix before shipping

1. **FIXED — `QuicPooledConnection.kt` — `sendBuf` was aliased as both send
   and receive buffer.** The I/O loop received packets into `sendBuf`, then
   `drainSend()` called `conn.send(sendBuf)` on the same array. If quiche
   queued an outbound packet between `recv` and the next `receive`, the
   outbound packet's bytes would get overwritten by the next inbound read.
   Now split into dedicated `sendBuf` and `recvPacketBuf`.
2. **FIXED — `Quiche4jCallServer.kt` — `CancellationHook.attach` ran after
   `openStream` returned.** A `Call.cancel()` racing with `openStream`
   completion would flip the RealCall to canceled without anyone tearing
   down the stream. Fixed by attaching before `openStream` with a
   `volatile var stream` the hook checks for null.
3. **FIXED — `Quiche4jCallServer.kt` — on failure, `releaseStream` didn't
   send STOP_SENDING / RESET_STREAM.** The remote kept sending data for a
   stream we'd abandoned, consuming flow-control credit. Error path now
   calls `pooled.cancelStream(stream)` before `releaseStream`.
4. **FIXED — `QuicConnectionHandle.kt` — every call allocated a real
   `Socket()`** that never got closed, leaking an OS FD until GC. Replaced
   with a single static closed-socket singleton.
5. **FIXED — `QuicPooledConnection.kt` — `onHeaders` parses `:status`
   with `v.toInt()` inside the listener on the I/O thread.** Now uses
   `toIntOrNull()`; malformed `:status` surfaces per-stream via a
   negative sentinel that `Quiche4jCallServer` turns into an
   `IOException`.
6. **FIXED — `Quiche4jCallServer.kt` — `stream.headersFuture.get()` has
   no timeout.** Wired `chain.readTimeoutMillis()` through as
   `get(timeoutMs, MILLISECONDS)`, with the OkHttp convention that
   `readTimeout == 0` means "no bound" preserved.
7. **FIXED — `Quiche4jInterceptor.kt` catch block only catches
   `IOException`.** Broadened to `Exception` so `Force(fallback=true)`
   catches quiche JNI `RuntimeException`s too. Cancelled calls still
   skip the retry.
8. **FIXED — `QuicPooledConnection.kt` — pool lock holds across the
   entire handshake.** Replaced the process-wide `ReentrantLock` with
   `ConcurrentHashMap.compute` per key. Distinct origins handshake in
   parallel; same-origin races serialise and the second call reuses
   the pooled connection the first produces.
9. **`QuicPooledConnection.kt` — `cancelStream` doesn't remove the stream
   from `streams` nor decrement `activeCount`.** Callers always pair
   `cancelStream` with `releaseStream` today (even the cancellation
   hook's failure path reaches it via `QuicBodySource.close`), so
   this is latent rather than live — will matter when pool eviction
   lands (M2-T3).
10. **FIXED — `QuicPooledConnection.kt` — `close()` doesn't join the
    I/O thread.** Now `ioThread?.join(500)` — above the 50ms socket
    receive timeout so the loop has time to exit cleanly.

## Possible bugs / concurrency smells

11. **FIXED — `openStream` + `sendBodyChunk` use `CountDownLatch` with
    no timeout.** Both migrated to `CompletableFuture.get(timeout, MS)`
    via a shared `awaitFuture` helper; openStream honours the chain's
    `connectTimeoutMillis`, sendBodyChunk honours `writeTimeoutMillis`.
12. **FIXED — `QuicRequestBodySink.kt` flow-control back-off is
    `Thread.sleep(5)` + unbounded retry.** Retry moved off the call
    thread entirely: `sendBodyChunk` re-queues itself on the I/O
    thread's retry list when quiche returns `DONE`, bounded by the
    caller's write-timeout deadline. Call thread only sees a positive
    byte count or an `IOException`.
13. **`QuicRequestBodySink.kt` — on duplex, `sendBodyChunk` blocks the
    call thread via a latch.** Block is now via `CompletableFuture`
    (bounded by write-timeout), not a latch. Document the synchronous-
    writer deadlock risk in the sink kdoc.
14. **FIXED — `Quiche4jInterceptor.kt` — `chain as
    RealInterceptorChain`.** Now `chain as? RealInterceptorChain ?:
    return chain.proceed(…)` — a test-wrapped chain falls through to
    the standard transport instead of CCE-ing at the user's call site.
15. **FIXED — `HttpsAwareDns.kt` — `computeIfAbsent` caches nulls
    forever.** Added a 5-minute TTL (configurable via `cacheTtlMillis`)
    on both hits and misses.
16. **FIXED — `HttpsAwareDns.kt` — `resolver.lookup` called twice when
    there's no h3 record.** Single resolver call now, filter in-memory.
17. **FIXED — `HttpsAwareDns.kt` / `AndroidHttpsServiceRecordResolver.kt`
    — `Executors.newCachedThreadPool` with no cap.** Default is a
    shared `newFixedThreadPool(2)` daemon pool; constructor takes an
    overridable `executor` param.
18. **`QuicPooledConnection.kt` — `onData` drains into a single
    `ByteArrayOutputStream` and emits one `BodyEvent.Bytes`.** For a
    large body this doubles memory. Prefer streaming smaller chunks.
19. **FIXED — `QuicBodySource.kt` / `QuicRequestBodySink.kt` both return
    `Timeout.NONE`.** Both now expose a mutable `Timeout` seeded from
    chain read/write timeouts, consulted on every read/write so
    OkHttp `.readTimeout(…)` / `.writeTimeout(…)` behave as expected.
20. **`QuicPooledConnection.kt` — I/O loop's outer `while` checks
    `conn.isClosed` each iteration, no happens-before with the native
    state.** Probably fine today but fragile if another thread ever
    calls `conn.close()`.
21. **FIXED — `AltSvcEntry.kt` — `splitAuthority` uses `lastIndexOf(':')`
    which breaks for IPv6 `[2001:db8::1]:443`.** Bracketed IPv6 parsed
    properly; unbracketed (malformed) rejected.
22. **FIXED — `AltSvcEntry.kt` — `splitAtCommas` quote tracking: an
    unbalanced quote flipped a depth counter and never flipped back,
    swallowing the rest of the header.** Toggle semantics now; a
    malformed entry drops cleanly without eating the rest.

## Missing tests

- **Concurrent requests to the same origin.** We verify `pool.size == 1`
  after *sequential* calls; need a parallel-execute test to prove the
  concurrent case too.
- **Pool eviction after `max_idle_timeout`.** No test for the transition
  from "pooled" to "closed, re-handshake on next call".
- **Duplex end-to-end.** `QuicRequestBodySink` has zero direct coverage.
  Flow-control `DONE` retry, final `close() → fin=true`, and the "call
  writeTo on call thread" contract are all unpinned.
- **Cancel during body read.** `CancellationHook fires synchronously`
  pins the callback itself but not that a cancel actually delivers an
  IOException to a blocked `QuicBodySource.read`.
- **Cancel before headers arrive.** The race against `headersFuture.get()`
  (bug #2 above — the reason for the fix) has no regression test.
- **`headersFuture.get()` timeout behaviour (bug #6).** Server that
  accepts but never sends `:status`.
- **Terminal throws `RuntimeException` vs `IOException` for
  `Force(fallback=true)` (bug #7).** Pins the catch-scope contract.
- **TrustManager rejection.** A stub `X509TrustManager` throwing in
  `checkServerTrusted` should surface `SSLPeerUnverifiedException` to
  the caller; no direct test.
- **`OkHostnameVerifier` cert-path vs generic-session-path branches
  in `verifyPeer`.** Both untested.
- **FIXED — Trailers.** A second `onHeaders` event now routes into
  `QuicStream.trailersFuture`, wired into `Response.Builder.trailers`
  via a `TrailersSource` adapter. Pseudo-headers (":…") are dropped
  defensively per RFC 9114 §4.1.1. End-of-stream without trailers
  completes the future with `Headers.EMPTY` so `response.trailers()`
  doesn't block forever.
- **Non-200 responses and empty bodies.** 404 / HEAD / 500 with
  content-length-but-no-body exercises `BodyEvent.End`-first paths that
  are untested.
- **`Alt-Svc: clear` through the full interceptor flow.** Parser is
  tested; the interceptor's special-case handling at
  `Quiche4jInterceptor.updateAltSvcFromResponse` isn't.
- **Multiple `Alt-Svc` response headers** (joined with `, `) — the joining
  breaks if any individual header is `clear`.
- **`Http3Preference.Force(portOverride)` actually honouring the
  override port on the happy path.** Existing test only exercises the
  *fallback* when the override is wrong.
- **Plaintext `http://` URLs fall through.** One-liner.
- **Empty DNS result.** Throws "No addresses for" — untested.
- **`DnsJavaHttpsServiceRecordResolver` parsing a real wire-format
  response.** Needs a recorded-answer fixture.
- **`AndroidHttpsServiceRecordResolver`** — JVM-side unit test with a
  fake `DnsResolver` callback to pin parse logic. Instrumentation test
  already covers the happy path.
- **`AltSvcEntry.toHeaderValue()` round-trip.** Callers persisting the
  cache to disk break silently if the format drifts.
- **EventListener ordering invariants** — `responseBodyStart/End`,
  `connectionAcquired/Released`, `requestFailed/responseFailed`. Load-
  bearing for metrics; easy to regress.

## Kdoc improvements

- **`Quiche4jInterceptor.Builder.build()` — no kdoc** explaining each
  `build()` creates a fresh `Quiche4jEngine` (new pool). Callers who
  `build()` twice and expect sharing get surprised.
- **`HttpsAware.getHttpsServiceRecord` — "Must be fast / non-blocking"**
  but `HttpsAwareDns` blocks up to 500ms. Tighten the contract.
- **`HttpsServiceRecordResolver.lookup` — says "failures surface as
  exceptions"** but `Quiche4jInterceptor` swallows them. Document what
  the caller will actually observe.
- **`AltSvcCache.put` — replaces wholesale.** RFC 7838 allows incremental
  updates; our behaviour needs spelling out or changing.
- **`QuicConnectionHandle` — `route()` returns synthetic data** that will
  mislead EventListeners inspecting it (incorrect `dns`, `protocols`,
  etc.). Warn.
- **`CancellationHook.attach` — "safe to call multiple times per call"**
  is misleading; each *registration* fires at most once, so three
  registrations → three callbacks on cancel. Say so.
- **`Http3Preference.Force.portOverride` — what happens with 0 or
  negative values.** `InetSocketAddress` throws; validate or document.
- **`Quiche4jEngine.acquire`** — doesn't mention one-entry-per-host+port
  scope or the lifetime implications of `maxIdleTimeoutMillis`.

## Minor suggestions

- **`String.defaultPort()` in `Quiche4jCallServer`** reinvents
  `HttpUrl.defaultPort(scheme)`. Use the existing one.
- **`AtomicReference + CountDownLatch` pattern reinvented in `openStream`
  and `sendBodyChunk`.** Replace with `CompletableFuture<T>` — shorter
  code, and `get(timeout)` gives us bugs #11 and the future
  `readTimeout` wiring for free.

---

## On the quiche dependency: crate or vendored?

Short answer: **we already use the crate, and should stay on it. Pinning
rather than floating is the right default; bump deliberately, not on every
release.**

Current state (`quiche4j-jni/Cargo.toml`):

```toml
quiche = "0.26.1"
```

That pulls from crates.io, not a submodule or git dependency — good. The
vendored-boringssl feature (`boringssl-vendored`, default) means cargo
builds quiche's bundled C BoringSSL from source inside the quiche crate
itself. That's *vendored TLS inside a crated quiche*, not a vendored
copy of the quiche crate itself. So we're already on the "take new
versions" side of the fence.

### Bump cadence

Auto-bumping to the latest quiche on every release isn't free:

- quiche doesn't guarantee API stability. `Connection::timeout()` vs
  `Connection::timeout_nanos()` and similar have rotated between
  releases. Our JNI binds to the Rust API directly — signature changes
  break the build.
- quiche's TLS surface (the `boring` crate version when using
  `boringssl-boring-crate`) has changed between quiche minors. The
  feature-flag dance I did for the [ECH
  investigation](upstream-issues/quiche-client-ech-hook.md) would look
  different on 0.22 vs 0.26.
- binary size shifts. quiche 0.26 ships a bigger `.so` than 0.24 due to
  qlog hooks. For mobile artifacts this matters.

Recommended policy:

1. **Pin** in `Cargo.toml` (done).
2. **Manual bump** on a cadence (quarterly, or on security advisories)
   with a diff review of quiche's CHANGELOG against our JNI surface.
3. **CI smoke test** (M2) that builds the latest quiche against our JNI
   bindings on a schedule, so API breakage is visible on our side even
   if we haven't bumped yet. Same shape as the existing
   container-tests / Caddy pipeline.
4. **Don't vendor a fork** of quiche. If we need an unreleased patch
   (e.g. the ECH hook from the upstream issue), pin to a git rev of
   cloudflare/quiche rather than forking — the fork won't get security
   fixes.

### Why this works well for quiche4j specifically

The JNI layer is a thin wrapper — every new quiche API surfaces as a few
lines of Rust + Java. The cost of tracking quiche is roughly "review the
CHANGELOG and write JNI shims for anything new we want". That's much
cheaper than vendoring.

What the user probably has in mind: swap the pinned `0.26.1` for
`^0.26`. That floats within `0.26.x` (patch bumps only on quiche's
semver). Reasonable — quiche actually does patch 0.26 with bugfixes, and
0.26.x → 0.26.y shouldn't break our bindings. I'd propose `^0.26` as a
balance: patch-level auto-bump, minor bumps stay manual.
