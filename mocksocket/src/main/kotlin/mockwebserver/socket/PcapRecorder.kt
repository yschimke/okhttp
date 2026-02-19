package mockwebserver.socket

import io.pkts.PcapOutputStream
import io.pkts.packet.IPv4Packet
import io.pkts.packet.MACPacket
import io.pkts.packet.TCPPacket
import io.pkts.protocol.Protocol
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import okio.Buffer

import java.io.Closeable

public class PcapRecorder(file: File) : SocketEventListener, Closeable {

    private val out = io.pkts.PcapOutputStream.create(io.pkts.frame.PcapGlobalHeader.createDefaultHeader(), FileOutputStream(file))
    
    // Track synthetic sequence numbers per socket to map TCP window flow
    private val sequenceNumbers = mutableMapOf<String, Long>()
    private val ackNumbers = mutableMapOf<String, Long>()

    override fun onEvent(event: SocketEvent) {
        val timeUsec = event.timestampNanos / 1000

        // In a true pcap we need synthetic IPs and Ports. MockSocket captures these as Strings/Ints.
        val srcIp = "127.0.0.1"
        val dstIp = "127.0.0.2"
        val srcPort = 49152
        val dstPort = 8080
        
        var seq = sequenceNumbers.getOrDefault(event.socketName, 1000L)
        var ack = ackNumbers.getOrDefault(event.socketName, 1000L)

        when (event) {
            is SocketEvent.Connect -> {
                 // SYN
                 writePacket(out, timeUsec, srcIp, dstIp, srcPort, event.port, seq, ack, syn = true, ackFlag = false, payload = null)
                 seq++
            }
            is SocketEvent.WriteSuccess -> {
                // PSH, ACK
                val payloadBytes = event.payload?.readByteArray()
                writePacket(out, timeUsec, srcIp, dstIp, srcPort, dstPort, seq, ack, syn = false, ackFlag = true, psh = true, payload = payloadBytes)
                if (payloadBytes != null) seq += payloadBytes.size
            }
            is SocketEvent.ReadSuccess -> {
                // For reads, we write from the perspective of the server sending to the client
                val payloadBytes = event.payload?.readByteArray()
                writePacket(out, timeUsec, dstIp, srcIp, dstPort, srcPort, ack, seq, syn = false, ackFlag = true, psh = true, payload = payloadBytes)
                if (payloadBytes != null) ack += payloadBytes.size
            }
            is SocketEvent.Close -> {
                // FIN, ACK
                writePacket(out, timeUsec, srcIp, dstIp, srcPort, dstPort, seq, ack, syn = false, ackFlag = true, fin = true, payload = null)
                seq++
            }
            else -> {}
        }

        sequenceNumbers[event.socketName] = seq
        ackNumbers[event.socketName] = ack
    }

    override fun close() {
        out.close()
    }

    private fun writePacket(
        out: PcapOutputStream,
        timestampUsec: Long,
        srcIp: String,
        dstIp: String,
        srcPort: Int,
        dstPort: Int,
        seq: Long,
        ack: Long,
        syn: Boolean = false,
        ackFlag: Boolean = false,
        fin: Boolean = false,
        psh: Boolean = false,
        payload: ByteArray? = null
    ) {
        // Because pkts.io is built around reading packets rather than forging them from scratch natively as a builder
        // we manually construct a raw Ethernet + IPv4 + TCP packet byte string for the writer, using standard standard header lengths.
        
        val tcpLen = 20 + (payload?.size ?: 0)
        val ipv4Len = 20 + tcpLen
        val totalLen = 14 + ipv4Len
        
        val pkt = Buffer()
        
        // Ethernet (14 bytes)
        pkt.write(ByteArray(6) { 0x00 }) // Dst MAC
        pkt.write(ByteArray(6) { 0x00 }) // Src MAC
        pkt.writeShort(0x0800)           // Type IPv4
        
        // IPv4 (20 bytes)
        pkt.writeByte(0x45) // Version 4, IHL 5
        pkt.writeByte(0x00) // DSCP
        pkt.writeShort(ipv4Len) // Total Length
        pkt.writeShort(0x0000) // Identification
        pkt.writeShort(0x4000) // Flags + Fragment offset
        pkt.writeByte(0x40) // TTL 64
        pkt.writeByte(0x06) // Protocol TCP (6)
        pkt.writeShort(0x0000) // Checksum (ignored by most readers if missing)
        
        val srcParts = srcIp.split(".").map { it.toInt().toByte() }
        val dstParts = dstIp.split(".").map { it.toInt().toByte() }
        pkt.write(srcParts.toByteArray())
        pkt.write(dstParts.toByteArray())
        
        // TCP (20 bytes)
        pkt.writeShort(srcPort) // Source port
        pkt.writeShort(dstPort) // Dest port
        pkt.writeInt(seq.toInt()) // Sequence Number
        pkt.writeInt(ack.toInt()) // Ack Number
        
        val dataOffset = (5 shl 4).toByte()
        pkt.writeByte(dataOffset.toInt())
        
        var flags = 0
        if (fin) flags = flags or 0x01
        if (syn) flags = flags or 0x02
        if (psh) flags = flags or 0x08
        if (ackFlag) flags = flags or 0x10
        pkt.writeByte(flags)
        
        pkt.writeShort(65535) // Window size
        pkt.writeShort(0x0000) // Checksum
        pkt.writeShort(0x0000) // Urgent pointer
        
        // Payload
        if (payload != null) {
            pkt.write(payload)
        }
        
        val rawPkt = pkt.readByteArray()
        
        // Calculate timestamp components for pcap
        val timeSec = timestampUsec / 1_000_000
        val timeUsecRem = timestampUsec % 1_000_000
        
        val recordHeader = io.pkts.frame.PcapRecordHeader.createDefaultHeader(timestampUsec)
        val globalHeader = io.pkts.frame.PcapGlobalHeader.createDefaultHeader()
        val frame = io.pkts.packet.impl.PCapPacketImpl(globalHeader, recordHeader, io.pkts.buffer.Buffers.wrap(rawPkt))
        out.write(frame)
    }
}
