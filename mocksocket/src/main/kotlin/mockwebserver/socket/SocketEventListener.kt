package mockwebserver.socket

public interface SocketEventListener {
    public fun onEvent(event: SocketEvent)
}

public class NoOpSocketEventListener : SocketEventListener {
    override fun onEvent(event: SocketEvent) {}
}

public class MemorySocketEventListener : SocketEventListener {
    private val _events = mutableListOf<SocketEvent>()
    public val events: List<SocketEvent>
        get() = _events.toList()

    override fun onEvent(event: SocketEvent) {
        _events.add(event)
    }
}
