# Package mockwebserver3.socket

This package provides a mock implementation of standard Java Sockets and Okio Sockets for testing network protocols and applications without a real network stack.

## MockSocket

`MockSocket` implements a high-fidelity socket simulation using Kotlin Coroutines. It's designed to be used in pairs, where one side represents the client and the other represents the server.

### Basic Usage

```kotlin
val (client, server) = MockSocket.pair()

// Client side
client.sink.writeUtf8("Hello Server")
client.sink.flush()

// Server side
val request = server.source.readUtf8()
println(request) // "Hello Server"
```

### Controlling Time and Clock Modes

`MockSocket` supports different clock modes for deterministic testing:

1. **`SYSTEM`**: Uses `System.nanoTime()` and real-world delays.
2. **`FakeClock`**: Manual clock advancement. Time only passes when you call `clock.advanceBy()`.
3. **`AutoClock`**: Automatically advances simulated time when a coroutine is blocked waiting for data or a timeout. This is ideal for testing complex timeout interactions without manual clock management.

```kotlin
val clock = FakeClock()
val (client, server) = MockSocket.pair(clock)

client.source.timeout().timeout(100, TimeUnit.MILLISECONDS)

// This will block until the clock is advanced or data is available
launch {
  clock.advanceBy(100, TimeUnit.MILLISECONDS)
}
assertFailure { client.source.buffer().readUtf8() }
  .isInstanceOf(InterruptedIOException::class.java)
```

### Network Profiles (Latency and Throughput)

You can simulate realistic network conditions using `NetworkProfile`:

```kotlin
val profile = NetworkProfile(
  latencyNanos = TimeUnit.MILLISECONDS.toNanos(50), // 50ms handshake latency
  bytesPerSecond = 1024 * 10, // 10 KB/s throughput
  maxWriteBufferSize = 1024 * 64 // 64 KB write buffer
)
val (client, server) = MockSocket.pair(profile = profile)
```

- **Latency**: Simulated on connection handshake and optionally on packet delivery.
- **Throughput**: Throttles writes to match the specified bytes per second.
- **Backlog**: `MockServerSocket` enforces backlog limits on incoming connections.

### Java Socket Compatibility (MockSocketAdapter)

If you need to use `MockSocket` with code that expects a `java.net.Socket`, use the adapter:

```kotlin
val mockSocket = MockSocket()
val adapter = MockSocketAdapter(mockSocket, InetSocketAddress("example.com", 80))
// Now use 'adapter' as a standard java.net.Socket
```

### Event Recording and Tracing

All socket operations are recorded as `SocketEvent`s, which can be used to generate sequence diagrams or verify the exact order of network interactions:

```kotlin
println(client.events)
// Output:
// 0 ns: Client -> connect
// 50 ms: Client -> write: 12 bytes
// 150 ms: Server -> read: 12 bytes
```

## MockServerSocket

`MockServerSocket` simulates a `java.net.ServerSocket`. It maintains an internal queue of pending connections (respecting the backlog limit) and provides a suspending `acceptSuspending()` method.

```kotlin
val server = MockServerSocket(clock)
server.bind(InetSocketAddress(0))

launch {
  val socket = server.accept()
  // ...
}
```

## Features and Goals

- **High Fidelity**: Accurate simulation of FIN/RST packets, half-closed states, and socket options.
- **Deterministic**: Fully virtualized time when used with `FakeClock`.
- **Lightweight**: No real kernel resources or ports used.
- **Tracing**: Complete lifecycle reporting for easier debugging of network races.
