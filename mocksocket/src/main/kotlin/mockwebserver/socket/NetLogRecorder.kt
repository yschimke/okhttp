package mockwebserver.socket

import java.io.File
import okio.Buffer

import java.io.Closeable

public class NetLogRecorder(file: File) : SocketEventListener, Closeable {
    private val writer = file.printWriter()
    private var isFirstEvent = true
    private var closed = false

    init {
        writer.println("{")
        writer.println("  \"constants\": {},")
        writer.println("  \"events\": [")
        writer.flush()
    }

    override fun onEvent(event: SocketEvent) {
        val time = event.timestampNanos / 1_000_000

        val jsonEvent = when (event) {
            is SocketEvent.Connect -> {
                """
                {
                  "phase": 1,
                  "source": { "id": ${event.socketName.hashCode()}, "type": 10 },
                  "time": "$time",
                  "type": 67,
                  "params": {
                    "address": "${event.host}:${event.port}"
                  }
                }
                """.trimIndent()
            }
            is SocketEvent.ReadSuccess -> {
                // Not recording actual base64 payload to save memory, just counts
                """
                {
                  "phase": 0,
                  "source": { "id": ${event.socketName.hashCode()}, "type": 10 },
                  "time": "$time",
                  "type": 113,
                  "params": { "byte_count": ${event.byteCount} }
                }
                """.trimIndent()
            }
            is SocketEvent.WriteSuccess -> {
                """
                {
                  "phase": 0,
                  "source": { "id": ${event.socketName.hashCode()}, "type": 10 },
                  "time": "$time",
                  "type": 114,
                  "params": { "byte_count": ${event.byteCount} }
                }
                """.trimIndent()
            }
            is SocketEvent.Close -> {
                """
                {
                  "phase": 2,
                  "source": { "id": ${event.socketName.hashCode()}, "type": 10 },
                  "time": "$time",
                  "type": 67
                }
                """.trimIndent()
            }
            else -> null
        }

        if (jsonEvent != null) {
            synchronized(this) {
                if (!isFirstEvent) {
                    writer.println(",")
                }
                isFirstEvent = false
                writer.print(jsonEvent.replace("\n", "\n    "))
                writer.flush()
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (!isFirstEvent) writer.println()
        writer.println("  ]")
        writer.println("}")
        writer.close()
    }
}
