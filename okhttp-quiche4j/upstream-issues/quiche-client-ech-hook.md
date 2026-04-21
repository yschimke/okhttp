# quiche: expose a hook for client-side ECH (Encrypted Client Hello)

Draft of an issue to raise on
[cloudflare/quiche](https://github.com/cloudflare/quiche). Captured here
instead of filed so the context is pinned alongside the okhttp-quiche4j
POC that motivates it.

## Summary

There is no supported way to use Encrypted Client Hello (RFC 9460 +
draft-ietf-tls-esni) on the **client** side of a quiche connection. The
closest escape hatch (`Config::with_boring_ssl_ctx_builder`) only exposes
the `SslContext`, but BoringSSL's client ECH configuration is per-`Ssl`,
not per-`SslContext`. quiche builds the `Ssl` object internally in
`connect()` and does not expose a hook for per-connection setup between
`Ssl` creation and the handshake start, so a caller has no place to call
`SSL_set1_ech_config_list` on the right object.

Server-side ECH is already possible via `SSL_CTX_set1_ech_keys`, so this
is a client-side gap only.

## Motivating use case

An HTTP/3 transport for OkHttp (details in the motivating POC link below)
wants to honour the `ech` SVCB parameter from the origin's HTTPS DNS
record. Chromium and Firefox already do this for TCP+TLS, and H/3 inherits
the same privacy benefit — in fact H/3 is a more natural fit because
HTTPS records deliver both the protocol hint (`alpn=h3`) and the ECH
config in the same query.

The use case is plain:

```
HTTPS record for example.com:
  alpn = "h3"
  ech = <base64 HPKE config blob>
  …

H/3 client:
  1. Resolve HTTPS record.
  2. Connect to example.com over UDP/QUIC.
  3. Wrap the ClientHello in ECH using the blob from step 1.
```

Today step 3 is impossible from a pure library consumer of quiche; it
requires patching the crate.

## Why the existing `with_boring_ssl_ctx_builder` isn't enough

```rust
// quiche 0.26.1, src/lib.rs:637
#[cfg(feature = "boringssl-boring-crate")]
pub fn with_boring_ssl_ctx_builder(
    version: u32, tls_ctx_builder: boring::ssl::SslContextBuilder,
) -> Result<Config> { … }
```

We get to configure the `SslContextBuilder`, but client-side ECH in
boring is at the `Ssl` level:

```rust
// boring 4.21.2, src/ssl/mod.rs:3900
impl SslRef {
    #[corresponds(SSL_set1_ech_config_list)]
    pub fn set_ech_config_list(&mut self, ech_config_list: &[u8]) -> Result<(), ErrorStack> { … }
}
```

There is no `SslContextBuilder::set_ech_config_list` equivalent — ECH
config is intentionally per-connection so you can update on retry.

Running `SSL_CTX_set_tlsext_servername_callback` would fire too late
(server-side only, post-ClientHello), and no matching
`set_tlsext_client_ech_config_callback` exists.

## Proposed API

Either of the two options below would unblock the use case. They're not
mutually exclusive.

### Option A — a first-class ECH API on `Config`

```rust
impl Config {
    /// Set the ECH ConfigList used for the next `connect()` call from this
    /// Config. The bytes are the contents of the `ech` SVCB parameter from
    /// the origin's HTTPS DNS record (RFC 9460 §7).
    ///
    /// Ignored on the server side — use `Config::with_boring_ssl_ctx_builder`
    /// + `SSL_CTX_set1_ech_keys` for server ECH.
    pub fn set_ech_config_list(&mut self, config_list: &[u8]) -> Result<()>;
}
```

Pros: no feature flag juggling, works with the default `boringssl-vendored`
build, matches what most callers will want.

Cons: adds ECH as a quiche-visible concept.

### Option B — a per-SSL setup callback

```rust
impl Config {
    /// Registers a callback that runs on each new client `Ssl` object
    /// between construction and handshake start. Enables per-connection
    /// configuration that isn't available on `SslContext`, including
    /// `SSL_set1_ech_config_list`.
    #[cfg(feature = "boringssl-boring-crate")]
    pub fn set_ssl_init_callback<F>(&mut self, callback: F)
    where
        F: Fn(&mut boring::ssl::SslRef) -> Result<(), ErrorStack> + Send + Sync + 'static;
}
```

Pros: general-purpose; unblocks other per-SSL knobs (certificate
transparency callbacks, TLS 1.3 early-data policy toggles, etc.) without
quiche needing to know about each one.

Cons: only useful with the `boringssl-boring-crate` feature; couples
callers to `boring` types.

## Current okhttp-quiche4j workaround (none)

We parse the `echConfigList` field from HTTPS DNS records
(via dnsjava / `android.net.DnsResolver`) and plumb it through the
`HttpsServiceRecord` type in the POC module, but the last step — feeding
the bytes into the QUIC handshake — has to be dropped on the floor. The
PLAN.md for the module lists ECH as ⏳ blocked on this issue.

## Links

- HPKE / ECH draft: draft-ietf-tls-esni
- RFC 9460 (SVCB / HTTPS records, carrying the ECH config)
- BoringSSL `SSL_set1_ech_config_list`
- quiche `Config::with_boring_ssl_ctx_builder`
- Motivating POC (context only):
  https://github.com/yschimke/okhttp/tree/quiche4j-poc/okhttp-quiche4j
