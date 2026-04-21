/*
 * Copyright (C) 2026 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package okhttp3.quiche4j

import io.quiche4j.Connection
import io.quiche4j.Quiche
import io.quiche4j.http3.Http3ConfigBuilder
import io.quiche4j.http3.Http3Connection
import io.quiche4j.http3.Http3EventListener
import io.quiche4j.http3.Http3Header
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509TrustManager
import okhttp3.Handshake
import okhttp3.TlsVersion

/**
 * Key for [Quiche4jEngine]'s connection pool: one long-lived QUIC connection per origin.
 */
internal data class PoolKey(
  val host: String,
  val port: Int,
)

/**
 * A long-lived QUIC connection to a single origin, owned by [Quiche4jEngine]. Runs a dedicated
 * daemon thread that drives the UDP socket, feeds packets into quiche, polls the H/3 connection,
 * and fans out results to per-stream [QuicStream] objects.
 *
 * Quiche is not thread-safe. All mutations of [conn] and [h3] happen on the I/O thread. Other
 * threads (callers) interact via [openStream] / [QuicStream] queues only.
 */
internal class QuicPooledConnection private constructor(
  val key: PoolKey,
  val socket: DatagramSocket,
  val peer: InetSocketAddress,
  val conn: Connection,
  val h3: Http3Connection,
  val handshake: Handshake,
) {
  private val streams = ConcurrentHashMap<Long, QuicStream>()
  private val taskQueue = LinkedBlockingQueue<Runnable>()

  // Tasks that hit DONE (stream flow-control-blocked) and need another shot after the next
  // receive+poll+send cycle. Only touched on the I/O thread — no sync needed.
  private val retryQueue = ArrayDeque<Runnable>()
  // Dedicated send/receive buffers. Previously these were aliased onto one array, which meant
  // an outbound ACK could be clobbered by the next `socket.receive()` before it actually hit
  // the wire. Keep them separate.
  private val sendBuf = ByteArray(Quiche4jEngine.MAX_DATAGRAM_SIZE)
  private val recvPacketBuf = ByteArray(Quiche4jEngine.MAX_DATAGRAM_SIZE)
  private val recvBuf = ByteArray(Quiche4jEngine.MAX_DATAGRAM_SIZE * 32)
  private val activeCount = AtomicInteger(0)

  @Volatile private var ioThread: Thread? = null

  @Volatile var closed: Boolean = false
    private set

  val isIdle: Boolean
    get() = activeCount.get() == 0

  /**
   * Schedule [task] on the I/O thread. Returns quickly; the caller is expected to wait on
   * per-stream signals for results.
   */
  fun submit(task: Runnable) {
    taskQueue.put(task)
  }

  /**
   * Open a new H/3 request stream for [headers]. Sends the headers (+ fin if [body] is null)
   * and, if [body] is non-null, the body too. Returns the [QuicStream] the caller can wait on
   * for headers + body events.
   *
   * The actual quiche calls run on the I/O thread. [timeoutMillis] bounds the caller-side wait
   * for the I/O thread to pick up the task; `<= 0` means no timeout. In practice the task is
   * trivially short so the timeout is really a liveness check — if we exceed it, the I/O
   * thread has wedged and the stream can't be opened.
   */
  fun openStream(
    headers: List<Http3Header>,
    body: ByteArray?,
    streamingBody: Boolean = false,
    timeoutMillis: Long = 0L,
  ): QuicStream {
    activeCount.incrementAndGet()
    val future = CompletableFuture<QuicStream>()
    submit {
      try {
        val hasBody = body != null || streamingBody
        val streamId: Long = h3.sendRequest(headers.toTypedArray(), !hasBody)
        if (streamId < 0) throw IOException("quiche4j sendRequest failed: $streamId")
        val stream = QuicStream(streamId, this)
        streams[streamId] = stream
        if (body != null) {
          val n = h3.sendBody(streamId, body, true)
          if (n < 0 && n != Quiche.ErrorCode.DONE.toLong()) {
            throw IOException("quiche4j sendBody failed: $n")
          }
        }
        future.complete(stream)
      } catch (t: Throwable) {
        future.completeExceptionally(t)
      }
    }
    return try {
      awaitFuture(future, timeoutMillis, "openStream")
    } catch (e: IOException) {
      activeCount.decrementAndGet()
      throw e
    }
  }

  /**
   * Signal that the caller is done with [stream]. Decrements the active-stream counter so the
   * pool knows the connection is idle and may be reused.
   */
  fun releaseStream(stream: QuicStream) {
    streams.remove(stream.streamId, stream)
    activeCount.decrementAndGet()
  }

  /**
   * Write a chunk of the request body on the I/O thread. Returns the number of bytes quiche
   * accepted (may be less than [bytes].size). Used by [QuicRequestBodySink] for duplex request
   * bodies.
   *
   * If quiche returns `DONE` (stream flow-control-blocked), the task re-queues itself on the
   * I/O thread's retry list and is re-attempted after the next receive/poll/send cycle — so
   * flow-control back-off happens on the I/O thread in lock-step with quiche's window updates,
   * not as a timed busy-loop on the call thread. The caller's [timeoutMillis] bounds the total
   * time spent waiting; `<= 0` means no timeout.
   */
  fun sendBodyChunk(
    stream: QuicStream,
    bytes: ByteArray,
    fin: Boolean,
    timeoutMillis: Long = 0L,
  ): Long {
    val future = CompletableFuture<Long>()
    val deadlineNs =
      if (timeoutMillis > 0) {
        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
      } else {
        Long.MAX_VALUE
      }
    lateinit var attempt: Runnable
    attempt =
      Runnable {
        if (future.isDone) return@Runnable // caller already timed out / was cancelled
        if (closed || conn.isClosed) {
          future.completeExceptionally(IOException("connection closed"))
          return@Runnable
        }
        try {
          val n = h3.sendBody(stream.streamId, bytes, fin)
          when {
            n >= 0 -> future.complete(n)
            n == Quiche.ErrorCode.DONE.toLong() -> {
              // Stream flow-control-blocked. Defer until next I/O iteration so quiche has had a
              // chance to receive + process MAX_STREAM_DATA frames. No Thread.sleep on any
              // thread — the loop's socket.receive timeout bounds the re-try cadence.
              if (System.nanoTime() > deadlineNs) {
                future.completeExceptionally(
                  IOException("sendBodyChunk timed out after ${timeoutMillis}ms (flow-control)"),
                )
              } else {
                retryQueue += attempt
              }
            }
            else -> future.completeExceptionally(IOException("quiche4j sendBody failed: $n"))
          }
        } catch (t: Throwable) {
          future.completeExceptionally(t)
        }
      }
    submit(attempt)
    return awaitFuture(future, timeoutMillis, "sendBodyChunk")
  }

  /**
   * Cancel an in-flight stream: send STOP_SENDING and RESET_STREAM on the I/O thread, then
   * unblock any caller waiting on headers or body from this stream with an IOException.
   * Idempotent — repeated calls no-op after the first.
   */
  fun cancelStream(stream: QuicStream) {
    if (stream.finished) return
    submit {
      try {
        conn.streamShutdown(stream.streamId, Connection.ShutdownDirection.READ, 0L)
      } catch (_: Throwable) { /* best effort */ }
      try {
        conn.streamShutdown(stream.streamId, Connection.ShutdownDirection.WRITE, 0L)
      } catch (_: Throwable) { /* best effort */ }
    }
    stream.deliverFailure(IOException("call was canceled"))
  }

  private fun startIoThread() {
    val t =
      Thread({ ioLoop() }, "quiche4j-io-${key.host}:${key.port}").apply {
        isDaemon = true
      }
    ioThread = t
    t.start()
  }

  private fun ioLoop() {
    try {
      while (!closed && !conn.isClosed) {
        // 1. Drain any task submissions (sendRequest/sendBody). Must run on I/O thread so that
        //    we don't race with recv/poll/send on the quiche pointers.
        while (true) {
          val task = taskQueue.poll() ?: break
          try {
            task.run()
          } catch (t: Throwable) {
            failAllStreams(t)
          }
        }

        // 2. Read one packet with a short timeout (so we stay responsive to new tasks).
        try {
          val packet = DatagramPacket(recvPacketBuf, recvPacketBuf.size)
          socket.receive(packet)
          val view = recvPacketBuf.copyOfRange(packet.offset, packet.length)
          val local = socket.localSocketAddress as InetSocketAddress
          val from = packet.socketAddress as InetSocketAddress
          val r = conn.recv(view, from, local)
          if (r < 0 && r.toLong() != Quiche.ErrorCode.DONE.toLong()) {
            throw IOException("QUIC recv failed: $r")
          }
        } catch (_: SocketTimeoutException) {
          conn.onTimeout()
        }

        // 3. Poll H/3 events and dispatch to streams.
        pollAll()

        // 4. Drain outbound packets.
        drainSend()

        // 5. Re-attempt previously flow-control-blocked sends. Running these *after*
        //    receive+poll+drainSend means quiche has just ingested any MAX_STREAM_DATA frames
        //    the peer sent us; if the window opened, the retry succeeds this cycle. If it
        //    didn't, the task re-enqueues itself and we try again next cycle (bounded by each
        //    task's deadline, so no infinite spin).
        if (retryQueue.isNotEmpty()) {
          val batch = ArrayList<Runnable>(retryQueue)
          retryQueue.clear()
          for (task in batch) {
            try {
              task.run()
            } catch (t: Throwable) {
              failAllStreams(t)
            }
          }
          // Any retry that ran send and produced outbound bytes needs drainSend to flush them.
          drainSend()
        }
      }
    } catch (t: Throwable) {
      failAllStreams(t)
    } finally {
      try {
        if (!conn.isClosed) conn.close(true, 0x00, "done".toByteArray())
      } catch (_: Throwable) {
        // best effort
      }
      try {
        drainSend()
      } catch (_: Throwable) {
        // best effort
      }
      socket.close()
      // Fail any streams that are still waiting.
      failAllStreams(IOException("quiche4j connection closed"))
      closed = true
    }
  }

  private fun pollAll() {
    while (true) {
      // Dispatch inside the callback. The callback runs on the I/O thread (this thread), so
      // there's no race with the rest of pollAll/recv/send. Previously we staged events into
      // an AtomicReference and dispatched after poll returned — harmless but added a per-
      // event allocation and, worse, drained the whole body into one big array on each
      // `onData` callback (doubling memory for multi-MB downloads). Now each recvBody chunk
      // ships directly to the stream's bodyQueue as its own BodyEvent, so the consumer in
      // QuicBodySource sees bounded-size deliveries.
      val pollResult =
        h3.poll(object : Http3EventListener {
          override fun onHeaders(
            sid: Long,
            headers: MutableList<Http3Header>,
            hasBody: Boolean,
          ) {
            var status = -1
            val normal = mutableListOf<Pair<String, String>>()
            for (h in headers) {
              val n = h.name()
              val v = h.value()
              if (n == ":status") {
                // toIntOrNull so a malformed `:status: abc` from a broken/hostile peer fails
                // just this stream (via status == -1 → IOException downstream), not the whole
                // pooled connection via an unhandled NumberFormatException on the I/O thread.
                status = v.toIntOrNull() ?: -1
              } else {
                normal += n to v
              }
            }
            streams[sid]?.deliverHeaders(status, normal)
          }

          override fun onData(sid: Long) {
            val stream = streams[sid] ?: return
            // Stream each recvBody chunk as its own BodyEvent. recvBuf is reused each pass
            // so we must hand the bytes to the stream in a container the I/O loop won't
            // touch again — an okio.Buffer, so the consumer's sink.write(buffer, n) can
            // transfer segments instead of doing a second byte copy.
            while (true) {
              val n = h3.recvBody(sid, recvBuf)
              if (n <= 0) break
              val chunk = okio.Buffer().apply { write(recvBuf, 0, n) }
              stream.deliverBody(chunk)
            }
          }

          override fun onFinished(sid: Long) {
            streams[sid]?.deliverEnd()
          }
        })
      if (pollResult == Quiche.ErrorCode.DONE.toLong()) break
      if (pollResult < 0) throw IOException("h3.poll failed: $pollResult")
    }
  }

  private fun drainSend() {
    while (true) {
      val n = conn.send(sendBuf)
      if (n <= 0) break
      socket.send(DatagramPacket(sendBuf, n, peer))
    }
  }

  private fun failAllStreams(cause: Throwable) {
    streams.values.forEach { it.deliverFailure(cause) }
    streams.clear()
  }

  /**
   * Await [future] with [timeoutMillis] and translate the three ways it can fail into
   * `IOException`s the interceptor layer expects. `<= 0` means no timeout. Helper for the
   * `submit { … future.complete(…) }` pattern used by [openStream] and [sendBodyChunk].
   */
  private fun <T> awaitFuture(
    future: CompletableFuture<T>,
    timeoutMillis: Long,
    op: String,
  ): T =
    try {
      if (timeoutMillis <= 0L) {
        future.get()
      } else {
        future.get(timeoutMillis, TimeUnit.MILLISECONDS)
      }
    } catch (e: TimeoutException) {
      throw IOException("$op timed out after ${timeoutMillis}ms", e)
    } catch (e: ExecutionException) {
      val cause = e.cause ?: e
      throw cause as? IOException ?: IOException(cause)
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      throw InterruptedIOException("interrupted waiting for $op")
    }

  fun close() {
    if (closed) return
    closed = true
    // Nudge the I/O thread out of blocking receive().
    try {
      socket.close()
    } catch (_: Throwable) {
      // best effort
    }
    // Wait briefly for the I/O thread to exit — otherwise a test that creates + closes
    // engines in sequence leaks threads between tests. 500ms is comfortably above the
    // socket.receive timeout (50ms) that bounds the loop cycle.
    val t = ioThread
    if (t != null && t !== Thread.currentThread()) {
      try {
        t.join(500)
      } catch (ie: InterruptedException) {
        Thread.currentThread().interrupt()
      }
    }
  }

  companion object {
    /**
     * Test-only: returns a [QuicPooledConnection] instance whose constructor has been
     * bypassed — every field is zero/null. Only the code paths that *don't* touch the
     * native connection / socket / I/O thread are safe to exercise on the result. We use
     * it to hand a [QuicStream] a non-null `connection` parameter in unit tests that
     * never traverse `close()` / `sendBodyChunk()` / cancellation. Kept in main sources
     * (rather than duplicated via reflection from test code) so the unsafe call is
     * colocated with the class it instantiates.
     */
    internal fun newStubForTesting(): QuicPooledConnection {
      val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
      unsafeField.isAccessible = true
      val unsafe = unsafeField.get(null) as sun.misc.Unsafe
      return unsafe.allocateInstance(QuicPooledConnection::class.java) as QuicPooledConnection
    }

    fun connect(
      key: PoolKey,
      peer: InetSocketAddress,
      engine: Quiche4jEngine,
      trustManager: X509TrustManager,
      hostnameVerifier: HostnameVerifier,
      handshakeTimeoutMillis: Long,
      maxIdleTimeoutMillis: Long,
    ): QuicPooledConnection {
      val config = engine.newConfig(maxIdleTimeoutMillis)
      val connId: ByteArray = Quiche.newConnectionId()
      val socket =
        DatagramSocket().apply {
          // Short timeout so the I/O thread is responsive to queued tasks.
          soTimeout = 50
        }
      val local = socket.localSocketAddress as InetSocketAddress
      val quicConn: Connection =
        try {
          Quiche.connect(key.host, connId, config, local, peer)
        } catch (e: Exception) {
          socket.close()
          throw e
        }

      try {
        pumpHandshake(quicConn, socket, peer, handshakeTimeoutMillis)
        // quiche's verify_peer is off by design; we run the equivalent of
        // X509ExtendedTrustManager + HostnameVerifier ourselves against the peer cert chain.
        // Any failure throws SSLPeerUnverifiedException before we expose the connection.
        val peerCerts = verifyPeer(quicConn, key.host, trustManager, hostnameVerifier)
        val h3Config = Http3ConfigBuilder().build()
        val h3 = Http3Connection.withTransport(quicConn, h3Config)
        val handshake = buildHandshake(peerCerts)
        val conn =
          QuicPooledConnection(
            key = key,
            socket = socket,
            peer = peer,
            conn = quicConn,
            h3 = h3,
            handshake = handshake,
          )
        conn.startIoThread()
        return conn
      } catch (t: Throwable) {
        try {
          quicConn.close(true, 0x00, "error".toByteArray())
        } catch (_: Throwable) {
          // best effort
        }
        socket.close()
        throw t
      }
    }

    private fun pumpHandshake(
      conn: Connection,
      socket: DatagramSocket,
      peer: InetSocketAddress,
      handshakeTimeoutMillis: Long,
    ) {
      val buf = ByteArray(Quiche4jEngine.MAX_DATAGRAM_SIZE)
      // First send (initial crypto).
      drainSendStatic(conn, socket, buf, peer)
      val prevTimeout = socket.soTimeout
      socket.soTimeout = 200
      val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(handshakeTimeoutMillis)
      try {
        while (!conn.isEstablished) {
          if (conn.isClosed) throw IOException("QUIC connection closed during handshake")
          if (System.nanoTime() > deadline) throw IOException("QUIC handshake timed out")
          try {
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet)
            val view = buf.copyOfRange(packet.offset, packet.length)
            val local = socket.localSocketAddress as InetSocketAddress
            val from = packet.socketAddress as InetSocketAddress
            val r = conn.recv(view, from, local)
            if (r < 0 && r.toLong() != Quiche.ErrorCode.DONE.toLong()) {
              throw IOException("QUIC recv failed during handshake: $r")
            }
          } catch (_: SocketTimeoutException) {
            conn.onTimeout()
          }
          drainSendStatic(conn, socket, buf, peer)
        }
      } finally {
        socket.soTimeout = prevTimeout
      }
    }

    private fun drainSendStatic(
      conn: Connection,
      socket: DatagramSocket,
      buf: ByteArray,
      peer: InetSocketAddress,
    ) {
      while (true) {
        val n = conn.send(buf)
        if (n <= 0) break
        socket.send(DatagramPacket(buf, n, peer))
      }
    }

    private fun verifyPeer(
      conn: Connection,
      host: String,
      trustManager: X509TrustManager,
      hostnameVerifier: HostnameVerifier,
    ): List<X509Certificate> {
      val chain: Array<out ByteArray> =
        conn.peerCertificateChain()
          ?: throw SSLPeerUnverifiedException("peer presented no certificate chain")
      if (chain.isEmpty()) {
        throw SSLPeerUnverifiedException("peer presented an empty certificate chain")
      }
      val cf = CertificateFactory.getInstance("X.509")
      val certs =
        chain.map { der ->
          cf.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }
      // authType "UNKNOWN" because quiche doesn't currently surface the TLS 1.3 key exchange.
      // Most trust managers only inspect the chain; those that care about authType (Android's,
      // for example) fall back to generic PKIX validation.
      trustManager.checkServerTrusted(certs.toTypedArray(), "UNKNOWN")

      // For the OkHttp-native hostname path we'd call hostnameVerifier.verify(host, sslSession),
      // but we have no SSLSession here. Prefer OkHostnameVerifier's cert-based path when we see
      // it; otherwise synthesize a minimal SSLSession-like wrapper.
      val leaf = certs.first()
      val verified =
        if (hostnameVerifier === okhttp3.internal.tls.OkHostnameVerifier) {
          okhttp3.internal.tls.OkHostnameVerifier.verify(host, leaf)
        } else {
          hostnameVerifier.verify(host, SyntheticPeerSession(certs))
        }
      if (!verified) {
        throw SSLPeerUnverifiedException(
          "hostname '$host' not verified by leaf cert ${leaf.subjectX500Principal}",
        )
      }
      return certs
    }

    private fun buildHandshake(peerCerts: List<X509Certificate>): Handshake =
      Handshake.get(
        tlsVersion = TlsVersion.TLS_1_3,
        cipherSuite = okhttp3.CipherSuite.TLS_AES_128_GCM_SHA256,
        peerCertificates = peerCerts,
        localCertificates = emptyList(),
      )
  }
}

/**
 * Minimal [javax.net.ssl.SSLSession] used only so we can call a user-supplied generic
 * [HostnameVerifier].verify(host, session) when it isn't OkHttp's own [OkHostnameVerifier].
 * Only [peerCertificates] is meaningful; every other method returns a safe no-op so verifiers
 * that touch only the cert chain (the typical case) work correctly. If a verifier reaches for
 * cipher-suite or session-id details we don't have, they get dummy values.
 */
private class SyntheticPeerSession(
  private val peerCerts: List<X509Certificate>,
) : javax.net.ssl.SSLSession {
  override fun getPeerCertificates(): Array<java.security.cert.Certificate> =
    peerCerts.toTypedArray()

  override fun getPeerCertificateChain(): Array<javax.security.cert.X509Certificate> =
    emptyArray()

  override fun getPeerPrincipal(): java.security.Principal = peerCerts.first().subjectX500Principal

  override fun getPeerHost(): String = ""

  override fun getPeerPort(): Int = -1

  override fun getId(): ByteArray = ByteArray(0)

  override fun getSessionContext(): javax.net.ssl.SSLSessionContext? = null

  override fun getCreationTime(): Long = 0

  override fun getLastAccessedTime(): Long = 0

  override fun invalidate() {}

  override fun isValid(): Boolean = true

  override fun putValue(
    name: String,
    value: Any,
  ) {}

  override fun getValue(name: String): Any? = null

  override fun removeValue(name: String) {}

  override fun getValueNames(): Array<String> = emptyArray()

  override fun getLocalCertificates(): Array<java.security.cert.Certificate> = emptyArray()

  override fun getCipherSuite(): String = "UNKNOWN"

  override fun getProtocol(): String = "TLSv1.3"

  override fun getPacketBufferSize(): Int = 0

  override fun getApplicationBufferSize(): Int = 0

  override fun getLocalPrincipal(): java.security.Principal? = null
}
