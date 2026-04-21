# quiche: expose negotiated TLS parameters on `Connection`

Draft of an issue to raise on
[cloudflare/quiche](https://github.com/cloudflare/quiche). Captured here
instead of filed so the context is pinned alongside the okhttp-quiche4j
POC that motivates it.

## Summary

`quiche::Connection` has no public accessor for the negotiated TLS 1.3
cipher suite or the protocol version. Both are held internally on
`Handshake` but not surfaced. quiche itself uses them (qlog, trace logs —
see `self.handshake.cipher()` at `src/lib.rs:2271` and `:3377`), so the
information already exists; it just isn't exposed.

Anyone wrapping quiche for higher-level HTTP libraries (OkHttp,
language-native HTTP clients, observability tooling) ends up either
pinning placeholder values or forking to add a getter. Every downstream
consumer repeats the same tiny patch.

## Motivating use case

OkHttp's [`Handshake`](https://square.github.io/okhttp/5.x/okhttp/okhttp3/-handshake/index.html)
carries the cipher suite and TLS version. A Response returned from an
HTTP/3 transport based on quiche should match the shape OkHttp users
already rely on from H/1.1 and H/2:

```kotlin
response.handshake!!.cipherSuite   // e.g. TLS_AES_128_GCM_SHA256
response.handshake!!.tlsVersion    // e.g. TLS_1_3
```

Today the quiche4j-backed bridge has to hard-code
`CipherSuite.TLS_AES_128_GCM_SHA256` and `TlsVersion.TLS_1_3` because
the underlying quiche `Connection` won't say what it actually
negotiated. The ALPN bytes (`application_proto()`) and the peer cert
chain (`peer_cert_chain()`) are both accessible on `Connection` already;
cipher + TLS version are the odd ones out.

## Current API shape

```rust
// quiche 0.26.1, src/lib.rs — public on Connection:
pub fn application_proto(&self) -> &[u8] { … }
pub fn peer_cert(&self) -> Option<&[u8]> { … }
pub fn peer_cert_chain(&self) -> Option<Vec<&[u8]>> { … }
pub fn session(&self) -> Option<&[u8]> { … }
pub fn trace_id(&self) -> &str { … }
pub fn stats(&self) -> Stats { … }

// Used internally, not exposed:
self.handshake.cipher()                 // Option<CipherSuite>-like
self.handshake.protocol_version()       // TLS version number
```

Inside quiche:

```rust
trace!("{} connection established: proto={:?} cipher={:?} curve={:?} sigalg={:?} …",
       …
       self.handshake.cipher(),
       …
);
```

So the values are already read on the happy path — they just can't leave
the crate.

## Proposed additions

```rust
impl Connection {
    /// Returns the negotiated TLS 1.3 cipher suite, or None if the handshake
    /// has not yet completed. The integer is the TLS cipher suite code point
    /// (RFC 8446 §B.4) — e.g. 0x1301 for TLS_AES_128_GCM_SHA256.
    pub fn cipher(&self) -> Option<u16>;

    /// Returns the negotiated TLS protocol version as a TLS ProtocolVersion
    /// code point (e.g. 0x0304 for TLS 1.3). QUIC requires TLS 1.3 today,
    /// but exposing the value keeps us honest if that changes.
    pub fn tls_protocol_version(&self) -> Option<u16>;
}
```

Integer code points keep the API minimal — no new enums, no extra types
to version-lock. Wrappers can map to whatever language-native enum they
prefer.

## Why this is small

Both values are already read by `trace!` inside quiche. Exposing them is
a couple of `pub fn` shims into `self.handshake`. No feature gates, no
crate-graph changes.

## Current okhttp-quiche4j workaround

We pin `TlsVersion.TLS_1_3` and `CipherSuite.TLS_AES_128_GCM_SHA256`
in the `Handshake` returned for every H/3 response. The PLAN.md for the
module lists this as an upstream gap; this file is the draft issue.

## Links

- quiche `Connection` (0.26.1): cipher read at `src/lib.rs:2271`, `:3377`,
  `:7709–7712`.
- RFC 8446 §B.4 (TLS cipher suite registry).
- Motivating POC (context only):
  https://github.com/yschimke/okhttp/tree/quiche4j-poc/okhttp-quiche4j
