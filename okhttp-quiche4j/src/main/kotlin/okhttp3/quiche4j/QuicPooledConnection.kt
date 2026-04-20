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
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
  private val sendBuf = ByteArray(Quiche4jEngine.MAX_DATAGRAM_SIZE)
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
   * The actual quiche calls run on the I/O thread.
   */
  fun openStream(
    headers: List<Http3Header>,
    body: ByteArray?,
  ): QuicStream {
    activeCount.incrementAndGet()
    val streamHolder = java.util.concurrent.atomic.AtomicReference<QuicStream>()
    val errorHolder = java.util.concurrent.atomic.AtomicReference<Throwable>()
    val latch = java.util.concurrent.CountDownLatch(1)

    submit {
      try {
        val hasBody = body != null
        val streamId: Long = h3.sendRequest(headers.toTypedArray(), !hasBody)
        if (streamId < 0) throw IOException("quiche4j sendRequest failed: $streamId")
        val stream = QuicStream(streamId, this)
        streams[streamId] = stream
        streamHolder.set(stream)
        if (body != null) {
          val n = h3.sendBody(streamId, body, true)
          if (n < 0 && n != Quiche.ErrorCode.DONE.toLong()) {
            throw IOException("quiche4j sendBody failed: $n")
          }
        }
      } catch (t: Throwable) {
        errorHolder.set(t)
      } finally {
        latch.countDown()
      }
    }
    latch.await()
    errorHolder.get()?.let {
      activeCount.decrementAndGet()
      throw it as? IOException ?: IOException(it)
    }
    return streamHolder.get() ?: throw IOException("openStream failed with no stream")
  }

  /**
   * Signal that the caller is done with [stream]. Decrements the active-stream counter so the
   * pool knows the connection is idle and may be reused.
   */
  fun releaseStream(stream: QuicStream) {
    streams.remove(stream.streamId, stream)
    activeCount.decrementAndGet()
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
          val packet = DatagramPacket(sendBuf, sendBuf.size)
          socket.receive(packet)
          val view = sendBuf.copyOfRange(packet.offset, packet.length)
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
      val pending = java.util.concurrent.atomic.AtomicReference<PendingEvent>()
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
              if (n == ":status") status = v.toInt()
              else normal += n to v
            }
            pending.set(PendingEvent.Headers(sid, status, normal, hasBody))
          }

          override fun onData(sid: Long) {
            // Drain as much as possible into a Bytes event. quiche doesn't tell us how many bytes
            // are pending so we keep reading until recvBody returns DONE.
            val out = java.io.ByteArrayOutputStream()
            while (true) {
              val n = h3.recvBody(sid, recvBuf)
              if (n <= 0) break
              out.write(recvBuf, 0, n)
            }
            pending.set(PendingEvent.Body(sid, out.toByteArray()))
          }

          override fun onFinished(sid: Long) {
            pending.set(PendingEvent.Finished(sid))
          }
        })
      if (pollResult == Quiche.ErrorCode.DONE.toLong()) break
      if (pollResult < 0) throw IOException("h3.poll failed: $pollResult")

      when (val evt = pending.get()) {
        is PendingEvent.Headers -> streams[evt.sid]?.deliverHeaders(evt.status, evt.headers)
        is PendingEvent.Body -> if (evt.data.isNotEmpty()) streams[evt.sid]?.deliverBody(evt.data)
        is PendingEvent.Finished -> streams[evt.sid]?.deliverEnd()
        null -> { /* event with no listener payload */ }
      }
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

  fun close() {
    if (closed) return
    closed = true
    // Nudge the I/O thread out of blocking receive().
    try {
      socket.close()
    } catch (_: Throwable) {
      // best effort
    }
  }

  private sealed class PendingEvent {
    class Headers(
      val sid: Long,
      val status: Int,
      val headers: List<Pair<String, String>>,
      val hasBody: Boolean,
    ) : PendingEvent()

    class Body(
      val sid: Long,
      val data: ByteArray,
    ) : PendingEvent()

    class Finished(
      val sid: Long,
    ) : PendingEvent()
  }

  companion object {
    fun connect(
      key: PoolKey,
      peer: InetSocketAddress,
      engine: Quiche4jEngine,
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
        val h3Config = Http3ConfigBuilder().build()
        val h3 = Http3Connection.withTransport(quicConn, h3Config)
        val handshake = buildHandshake(quicConn)
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

    private fun buildHandshake(conn: Connection): Handshake {
      val peerCerts: List<java.security.cert.Certificate> =
        try {
          val chain = conn.peerCertificateChain()
          if (chain.isNullOrEmpty()) {
            emptyList()
          } else {
            val cf = java.security.cert.CertificateFactory.getInstance("X.509")
            chain.map { der -> cf.generateCertificate(java.io.ByteArrayInputStream(der)) }
          }
        } catch (_: Throwable) {
          emptyList()
        }
      return Handshake.get(
        tlsVersion = TlsVersion.TLS_1_3,
        cipherSuite = okhttp3.CipherSuite.TLS_AES_128_GCM_SHA256,
        peerCertificates = peerCerts,
        localCertificates = emptyList(),
      )
    }
  }
}
