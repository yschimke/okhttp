package mockwebserver.socket

import assertk.assertThat
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import java.io.File
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CaptureTest {
    private val clock = AutoClock()
    private val profile = NetworkProfile()
    private val fileNetLog = File("build/reports/test-netlog.json")
    private val filePcap = File("build/reports/test-capture.pcap")

    @BeforeEach
    fun setUp() {
        fileNetLog.delete()
        filePcap.delete()
        fileNetLog.parentFile?.mkdirs()
    }

    @AfterEach
    fun tearDown() {
        // Leave files for manual inspection if needed, or delete.
    }

    @Test
    fun exportPcapAndNetlog(): Unit = runBlocking {
        // Compose multiple listeners so both pcap and netlog can be generated in a single pass.
        val netLogRecorder = NetLogRecorder(fileNetLog)
        val pcapRecorder = PcapRecorder(filePcap)
        val multiListener = object : SocketEventListener {
            override fun onEvent(event: SocketEvent) {
                 netLogRecorder.onEvent(event)
                 pcapRecorder.onEvent(event)
            }
        }
    
        val (client, server) = MockSocket.pair(clock, profile, multiListener).connect()
        
        // Enable record mode to capture actual Buffer payloads in SocketEvents!
        client.recordMode = RecordMode.ENABLED
        server.recordMode = RecordMode.ENABLED

        val request = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n"
        val response = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK"

        // Client sends request
        client.writeSuspending(Buffer().writeUtf8(request), request.length.toLong())
        
        // Server reads request
        val serverReadBuffer = Buffer()
        server.readSuspending(serverReadBuffer, request.length.toLong())
        assertThat(serverReadBuffer.readUtf8() == request).isTrue()

        // Server sends response
        server.writeSuspending(Buffer().writeUtf8(response), response.length.toLong())

        // Client reads response
        val clientReadBuffer = Buffer()
        client.readSuspending(clientReadBuffer, response.length.toLong())
        assertThat(clientReadBuffer.readUtf8() == response).isTrue()
        
        client.closeSuspending()
        server.closeSuspending()

        // Close streams
        netLogRecorder.close()
        pcapRecorder.close()

        // Verify traces got written
        assertThat(fileNetLog.exists()).isTrue()
        assertThat(fileNetLog.length()).isGreaterThan(100L)

        assertThat(filePcap.exists()).isTrue()
        assertThat(filePcap.length()).isGreaterThan(100L)
    }
}
