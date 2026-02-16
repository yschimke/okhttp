# Package mockwebserver3.socket

This package provides a mock implementation of `okio.Socket` for testing network protocols and applications without real network stack.

## MockSocket

`MockSocket` implements the `okio.Socket` interface. It is designed to be used in pairs, where one side represents the client and the other represents the server.

By default, the behavior of `MockSocket` closely matches real Sockets, including defaults and options.

### Basic Usage

```kotlin
val (client, server) = MockSocket.pair()

// Client side
client.sink.writeUtf8("Hello Server")
client.sink.flush()

// Server side
val request = server.source.readUtf8()
println(request) // "Hello Server"

server.sink.writeUtf8("Hello Client")
server.sink.flush()

// Client side
client.source.readUtf8() // Returns "Hello Client"

// Check events (Sequence Diagram style)
println(client.events)
// Output:
// 0 ns: Client -> write: Hello Server
// 0 ns: Server -> read: Hello Server
// 500 ms: Server -> write: Hello Client
// 500 ms: Client -> read: Hello Client
```

### Controlling Time

By default, `MockSocket` uses the system clock. You can inject a `FakeClock` to control timeouts precisely.

```kotlin
val clock = FakeClock()
val (client, server) = MockSocket.pair(clock)

client.source.timeout().timeout(100, TimeUnit.MILLISECONDS)

// This will block until the clock is advanced or data is available
val result = async {
  check(client.clock.now == 0L)
  try {
    client.source.readUtf8()
  } catch (e: InterruptedIOException) {
    println("Timed out as expected!")
  }
  check(client.clock.now == TimeUnit.MILLISECONDS.toNanos(100))
}

clock.advanceBy(100, TimeUnit.MILLISECONDS)
result.await()

// Check events
println(client.events)
// Output:
// 0 ns: Client -> read wait: 100 ms
// 100 ms: Client -> read failed: timeout
```

### Automatic Time Advancement

For simpler tests where you want time to pass automatically when blocked:

```kotlin
val clock = AutoClock()
val (client, server) = MockSocket.pair(clock)

client.source.timeout().timeout(100, TimeUnit.MILLISECONDS)

// This will automatically advance the clock by 100ms and fail
try {
  client.source.readUtf8()
} catch (e: InterruptedIOException) {
  println("Timed out automatically!")
}

// Check events
println(client.events)
// Output:
// 0 ns: Client -> read wait: 100 ms
// 100 ms: Client -> read failed: timeout
```

### Fault Injection

You can inject faults to simulate network issues.

```kotlin
val (client, server) = MockSocket.pair()

client.faults = object : MockSocket.Faults {
  var bytesRead = 0L
  override fun postRead(byteCount: Long) {
    bytesRead += byteCount
    if (bytesRead > 10) throw IOException("Network failure after 10 bytes")
  }
}

try {
    client.source.readUtf8()
} catch (e: IOException) {
    println(e) // IOException: Network failure after 10 bytes
}

// Check events
println(client.events)
// Output:
// 0 ns: Client -> read success: 10 bytes
// 0 ns: Client -> read failed: Network failure after 10 bytes
```

## High Level Goal

`MockSocket` is designed to be a low-level building block, similar to how `FakeFileSystem` allows testing file-based logic without a real disk. It enables testing:
- Protocol implementations (HTTP/2, etc.)
- Timeout handling logic
- Testing error recovery and retry mechanisms
- Half-closed socket behaviors

## Okio Wishlist

To make time simulation even better, we'd love to see the following in Okio:

- **Open `Timeout` methods**: Currently, `Timeout.deadline(long, TimeUnit)` is final and hardcodes `System.nanoTime()`. This makes it impossible to fully virtualize time for libraries that rely on this method. If it were open, or if `Timeout` accepted a `Clock`, we could implement `FakeTimeout` correctly.
- **`Clock` interface**: A standard `Clock` interface in Okio would allow libraries to be testable with simulated time out of the box.
- **This library**: `MockSocket` demonstrates how powerful a virtualized time and socket implementation can be. We hope it inspires standard support in Okio.

## Missing Features (TODO)

- **Full `Socket` API Coverage**: methods like `connect`, `bind`, and `setSoTimeout` are not fully supported or simulated.
- **Simulated Connect Timeouts**: `MockSocket` instances are created connected; `connect()` timeouts are not simulated.
