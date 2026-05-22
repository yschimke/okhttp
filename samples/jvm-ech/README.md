# jvm-ech

End-to-end Encrypted Client Hello (ECH) on the JVM using OkHttp.

## What this proves

The cross-platform ECH plumbing in `commonJvmAndroid` (originally added for Android API 37+)
works on the JVM with no Android-specific code. The two pieces required are:

1. **DNS that surfaces the `ech` SvcParam from HTTPS DNS records (RFC 9460).** Implemented in
   `okhttp-dnsoverhttps` as a small custom parser inside `DnsRecordCodec` plus an
   `EchAwareDnsOverHttps` wrapper that issues a parallel `HTTPS` (type 65) query alongside the
   normal `A`/`AAAA` lookups.
2. **A TLS provider that exposes an "apply ECH config list to the socket" hook.** No stock JVM
   TLS provider does this (as of May 2026) — see the feasibility note on the parent branch — so
   we use the DEfO fork of Google's Conscrypt. `ConscryptEchModeConfiguration` reflectively
   binds to `org.conscrypt.Conscrypt.setEchConfigList(SSLSocket, byte[])`. When that method is
   absent (stock Conscrypt) the implementation behaves like `EchModeConfiguration.Unspecified`
   and connections proceed with standard TLS.

## Building DEfO Conscrypt locally

The DEfO fork is not published to Maven Central. Build it once and install into your local
Maven cache:

```bash
git clone https://github.com/defo-project/conscrypt
cd conscrypt
# DEfO Conscrypt builds against the DEfO BoringSSL fork; follow their BUILDING.md
# (BoringSSL/CMake/Ninja prerequisites).
./gradlew :openjdk-uber:publishToMavenLocal
```

Note the resulting version string (e.g. `2.5.3-defo`) and pass it to this sample:

```bash
./gradlew :samples:jvm-ech:run \
  -PdefoConscryptVersion=2.5.3-defo \
  --args="https://crypto.cloudflare.com/cdn-cgi/trace"
```

When the sample reports `sni=encrypted` in the response body, ECH was actually negotiated.

## Without DEfO Conscrypt

You can still run the sample with stock Conscrypt to exercise the DNS path:

```bash
./gradlew :samples:jvm-ech:run -PdefoConscryptVersion=2.5.3
```

The DoH lookup will fetch the HTTPS record and report whether an ECH config was present in DNS,
but the TLS handshake will proceed without ECH (since stock Conscrypt has no ECH hook).
