# jvm-ech

End-to-end Encrypted Client Hello (ECH) on the JVM using OkHttp.

## What this proves

The cross-platform ECH plumbing in `commonJvmAndroid` (originally added for Android API 37+)
works on the JVM with no Android-specific code, and **with no reflection** in either okhttp or
the sample.

Two pieces are required:

1. **DNS that surfaces the `ech` SvcParam from HTTPS DNS records (RFC 9460).** Implemented in
   `okhttp-dnsoverhttps` as a custom parser inside `DnsRecordCodec` plus an
   `EchAwareDnsOverHttps` wrapper that issues a parallel `HTTPS` (type 65) query alongside the
   normal `A`/`AAAA` lookups.
2. **A TLS provider that exposes an "apply ECH config list to the socket" hook.** No stock JVM
   TLS provider does this (as of May 2026) — see the feasibility note on the parent branch — so
   the sample uses the **DEfO / Guardian Project's** ECH-enabled Conscrypt build.
   `ConscryptEchModeConfiguration` (in this module, not in okhttp) calls
   `org.conscrypt.Conscrypt.setEchConfigList(...)` directly.

Because the call is direct, **linking this module against stock Conscrypt is a compile-time
error**. That's the intended guard — there is no silent ECH-strip path.

## Dependency

The Guardian Project (the same people behind DEfO) publishes the ECH-enabled Conscrypt fork to
Maven Central as `info.guardianproject.conscrypt:conscrypt-openjdk`. The sample uses it
directly, so **no local build is required** for x86_64 Linux:

```bash
./gradlew :samples:jvm-ech:run \
  --args="https://crypto.cloudflare.com/cdn-cgi/trace"
```

Only the `linux-x86_64` classifier is published. On other architectures (Apple Silicon, etc.)
either:
- Override the classifier: `-PguardianConscryptClassifier=...` (no other published variants
  exist today, so this is mostly a hook for future builds);
- Or build the DEfO Conscrypt fork yourself from
  https://github.com/defo-project/conscrypt and install with
  `./gradlew :openjdk-uber:publishToMavenLocal`. You'd then add `mavenLocal()` to this
  module's `repositories { }`.

When the sample reports `sni=encrypted` in the response body, ECH was actually negotiated.

## How the wiring looks from the user's perspective

```kotlin
Security.insertProviderAt(Conscrypt.newProvider(), 1)

val echAwareDns = EchAwareDnsOverHttps(dnsOverHttps)

val client = OkHttpClient.Builder()
  .dns(echAwareDns)
  .echModeConfiguration(ConscryptEchModeConfiguration())
  .build()
```

The okhttp side has no Conscrypt-specific code — `EchModeConfiguration` is a public SPI that
this module implements. Anyone bringing their own ECH-capable TLS provider (BouncyCastle, a
custom build, whatever) wires it the same way.
