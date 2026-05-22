/*
 * Copyright 2016 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package okhttp3.dnsoverhttps

import java.io.EOFException
import java.net.InetAddress
import java.net.UnknownHostException
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.utf8Size

/**
 * Trivial Dns Encoder/Decoder, basically ripped from Netty full implementation.
 */
internal object DnsRecordCodec {
  private const val SERVFAIL = 2
  private const val NXDOMAIN = 3
  const val TYPE_A = 0x0001
  const val TYPE_AAAA = 0x001c
  private const val TYPE_PTR = 0x000c

  /** SVCB (RFC 9460), used here for completeness. */
  const val TYPE_SVCB = 0x0040

  /** HTTPS (RFC 9460). */
  const val TYPE_HTTPS = 0x0041

  /** SvcParamKey for `ech` (RFC 9460, ECH SvcParam). */
  const val SVCPARAM_KEY_ECH = 5
  private val ASCII = Charsets.US_ASCII

  fun encodeQuery(
    host: String,
    type: Int,
  ): ByteString =
    Buffer()
      .apply {
        writeShort(0) // query id
        writeShort(256) // flags with recursion
        writeShort(1) // question count
        writeShort(0) // answerCount
        writeShort(0) // authorityResourceCount
        writeShort(0) // additional

        val nameBuf = Buffer()
        val labels = host.split('.').dropLastWhile { it.isEmpty() }
        for (label in labels) {
          val utf8ByteCount = label.utf8Size()
          require(utf8ByteCount == label.length.toLong()) { "non-ascii hostname: $host" }
          nameBuf.writeByte(utf8ByteCount.toInt())
          nameBuf.writeUtf8(label)
        }
        nameBuf.writeByte(0) // end

        nameBuf.copyTo(this, 0, nameBuf.size)
        writeShort(type)
        writeShort(1) // CLASS_IN
      }.readByteString()

  @Throws(Exception::class)
  fun decodeAnswers(
    hostname: String,
    byteString: ByteString,
  ): List<InetAddress> {
    val result = mutableListOf<InetAddress>()

    val buf = Buffer()
    buf.write(byteString)
    buf.readShort() // query id

    val flags = buf.readShort().toInt() and 0xffff
    require(flags shr 15 != 0) { "not a response" }

    val responseCode = flags and 0xf

    if (responseCode == NXDOMAIN) {
      throw UnknownHostException("$hostname: NXDOMAIN")
    } else if (responseCode == SERVFAIL) {
      throw UnknownHostException("$hostname: SERVFAIL")
    }

    val questionCount = buf.readShort().toInt() and 0xffff
    val answerCount = buf.readShort().toInt() and 0xffff
    buf.readShort() // authority record count
    buf.readShort() // additional record count

    for (i in 0 until questionCount) {
      skipName(buf) // name
      buf.readShort() // type
      buf.readShort() // class
    }

    for (i in 0 until answerCount) {
      skipName(buf) // name

      val type = buf.readShort().toInt() and 0xffff
      buf.readShort() // class
      @Suppress("UNUSED_VARIABLE")
      val ttl = buf.readInt().toLong() and 0xffffffffL // ttl
      val length = buf.readShort().toInt() and 0xffff

      if (type == TYPE_A || type == TYPE_AAAA) {
        val bytes = ByteArray(length)
        buf.read(bytes)
        result.add(InetAddress.getByAddress(bytes))
      } else {
        buf.skip(length.toLong())
      }
    }

    return result
  }

  /**
   * Decodes the first `HTTPS` answer (RFC 9460 type 65) into its SvcPriority, target name,
   * and parsed SvcParams. Returns `null` if the response contains no HTTPS RR.
   */
  @Throws(Exception::class)
  fun decodeFirstHttpsAnswer(
    hostname: String,
    byteString: ByteString,
  ): HttpsRecord? {
    val buf = Buffer()
    buf.write(byteString)
    buf.readShort() // query id

    val flags = buf.readShort().toInt() and 0xffff
    require(flags shr 15 != 0) { "not a response" }

    val responseCode = flags and 0xf
    if (responseCode == NXDOMAIN) {
      throw UnknownHostException("$hostname: NXDOMAIN")
    } else if (responseCode == SERVFAIL) {
      throw UnknownHostException("$hostname: SERVFAIL")
    }

    val questionCount = buf.readShort().toInt() and 0xffff
    val answerCount = buf.readShort().toInt() and 0xffff
    buf.readShort() // authority record count
    buf.readShort() // additional record count

    for (i in 0 until questionCount) {
      skipName(buf) // name
      buf.readShort() // type
      buf.readShort() // class
    }

    for (i in 0 until answerCount) {
      skipName(buf) // name

      val type = buf.readShort().toInt() and 0xffff
      buf.readShort() // class
      buf.readInt() // ttl
      val length = buf.readShort().toInt() and 0xffff

      if (type == TYPE_HTTPS || type == TYPE_SVCB) {
        val rdata = ByteArray(length)
        buf.read(rdata)
        return parseHttpsRdata(rdata)
      } else {
        buf.skip(length.toLong())
      }
    }

    return null
  }

  /**
   * Parses an HTTPS/SVCB record's RDATA per RFC 9460. The wire format is
   * `priority(2) | targetName(label-encoded) | SvcParams*` where each SvcParam is
   * `key(2) | length(2) | value(length)` in strictly ascending key order.
   *
   * Compression pointers MUST NOT appear in HTTPS/SVCB records, so a simple label decoder
   * is sufficient for the target name.
   */
  private fun parseHttpsRdata(rdata: ByteArray): HttpsRecord {
    val rd = Buffer().apply { write(rdata) }

    val priority = rd.readShort().toInt() and 0xffff

    // Decode target name (label-encoded; no compression in HTTPS/SVCB).
    val target = StringBuilder()
    while (true) {
      val labelLen = rd.readByte().toInt() and 0xff
      if (labelLen == 0) break
      check(labelLen and 0xc0 == 0) { "compression not allowed in HTTPS RR" }
      if (target.isNotEmpty()) target.append('.')
      val labelBytes = ByteArray(labelLen)
      rd.read(labelBytes)
      target.append(String(labelBytes, ASCII))
    }

    var ech: ByteString? = null
    val alpn = mutableListOf<String>()
    val ipv4Hints = mutableListOf<ByteArray>()
    val ipv6Hints = mutableListOf<ByteArray>()
    var port: Int? = null

    while (!rd.exhausted()) {
      val key = rd.readShort().toInt() and 0xffff
      val len = rd.readShort().toInt() and 0xffff
      val value = ByteArray(len)
      rd.read(value)

      when (key) {
        1 -> { // alpn
          val ab = Buffer().apply { write(value) }
          while (!ab.exhausted()) {
            val pl = ab.readByte().toInt() and 0xff
            val protoBytes = ByteArray(pl)
            ab.read(protoBytes)
            alpn.add(String(protoBytes, ASCII))
          }
        }
        3 -> { // port
          if (len == 2) port = (value[0].toInt() and 0xff) shl 8 or (value[1].toInt() and 0xff)
        }
        4 -> { // ipv4hint (n * 4 bytes)
          var i = 0
          while (i + 4 <= value.size) {
            ipv4Hints.add(value.copyOfRange(i, i + 4))
            i += 4
          }
        }
        6 -> { // ipv6hint (n * 16 bytes)
          var i = 0
          while (i + 16 <= value.size) {
            ipv6Hints.add(value.copyOfRange(i, i + 16))
            i += 16
          }
        }
        SVCPARAM_KEY_ECH -> ech = value.toByteString()
        // Other keys are ignored.
      }
    }

    return HttpsRecord(
      priority = priority,
      target = target.toString(),
      alpn = alpn,
      port = port,
      ipv4Hints = ipv4Hints.map { InetAddress.getByAddress(it) },
      ipv6Hints = ipv6Hints.map { InetAddress.getByAddress(it) },
      ech = ech,
    )
  }

  @Throws(EOFException::class)
  private fun skipName(source: Buffer) {
    // 0 - 63 bytes
    var length = source.readByte().toInt()

    if (length < 0) {
      // compressed name pointer, first two bits are 1
      // drop second byte of compression offset
      source.skip(1)
    } else {
      while (length > 0) {
        // skip each part of the domain name
        source.skip(length.toLong())
        length = source.readByte().toInt()
      }
    }
  }
}
