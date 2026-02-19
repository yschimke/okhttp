package okhttp3.dnsoverhttps

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.io.File
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory
import mockwebserver.socket.NetLogRecorder
import mockwebserver.socket.PcapRecorder
import mockwebserver.socket.SocketDecorator
import mockwebserver.socket.SocketEvent
import mockwebserver.socket.SocketEventListener
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue

class DnsOverHttpsCaptureTest {
    private val server = MockWebServer()
    private val fileNetLog = File("build/reports/doh-capture-netlog.json")
    private val filePcap = File("build/reports/doh-capture-capture.pcap")

    private lateinit var netLogRecorder: NetLogRecorder
    private lateinit var pcapRecorder: PcapRecorder

    @BeforeEach
    fun setUp() {
        fileNetLog.delete()
        filePcap.delete()
        fileNetLog.parentFile?.mkdirs()

        server.start()

        netLogRecorder = NetLogRecorder(fileNetLog)
        pcapRecorder = PcapRecorder(filePcap)
    }

    @AfterEach
    fun tearDown() {
        server.close()
        netLogRecorder.close()
        pcapRecorder.close()
    }

    private fun buildLocalhost(): DnsOverHttps {
        val multiListener = object : SocketEventListener {
            override fun onEvent(event: SocketEvent) {
                netLogRecorder.onEvent(event)
                pcapRecorder.onEvent(event)
            }
        }

        // We inject the decorator overriding SocketFactory inside OkHttpClient!
        val recordingSocketFactory = object : SocketFactory() {
            private val delegate = SocketFactory.getDefault()
            
            override fun createSocket(host: String?, port: Int): Socket {
                val s = delegate.createSocket(host, port)
                return SocketDecorator(s, multiListener, socketName = "DohSocket-1")
            }

            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket {
                val s = delegate.createSocket(host, port, localHost, localPort)
                return SocketDecorator(s, multiListener, socketName = "DohSocket-2")
            }

            override fun createSocket(host: InetAddress?, port: Int): Socket {
                val s = delegate.createSocket(host, port)
                return SocketDecorator(s, multiListener, socketName = "DohSocket-3")
            }

            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket {
                val s = delegate.createSocket(address, port, localAddress, localPort)
                return SocketDecorator(s, multiListener, socketName = "DohSocket-4")
            }

            override fun createSocket(): Socket {
                val s = delegate.createSocket()
                return SocketDecorator(s, multiListener, socketName = "DohSocket-5")
            }
        }

        val bootstrapClient = OkHttpClient.Builder()
            .socketFactory(recordingSocketFactory)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .build()

        return DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url(server.url("/lookup?ct"))
            .build()
    }

    @Test
    fun getOneWithCapture() {
        server.enqueue(
            DnsOverHttpsTest.dnsResponse(
                "0000818000010003000000000567726170680866616365626f6f6b03636f6d0000010001c00c000500010" +
                        "0000a6d000603617069c012c0300005000100000cde000c04737461720463313072c012c04200010001000" +
                        "0003b00049df00112",
            ),
        )

        val dns = buildLocalhost()
        val result = dns.lookup("google.com")
        
        // Assert identical to okhttp3 original test
        assertThat(result).isEqualTo(listOf(InetAddress.getByName("157.240.1.18")))
        
        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.method).isEqualTo("GET")
        assertThat(recordedRequest.url.encodedQuery)
            .isEqualTo("ct&dns=AAABAAABAAAAAAAABmdvb2dsZQNjb20AAAEAAQ")

        netLogRecorder.close()
        pcapRecorder.close()

        // Explicitly assert we captured
        assertTrue(fileNetLog.exists())
        assertTrue(fileNetLog.length() > 100)
        
        assertTrue(filePcap.exists())
        assertTrue(filePcap.length() > 100)
    }
}
