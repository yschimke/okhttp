package mockwebserver.socket

public interface SocketEventListener {
    public fun onEvent(event: SocketEvent)
}

public class NoOpSocketEventListener : SocketEventListener {
    override fun onEvent(event: SocketEvent) {}
}

public class MemorySocketEventListener(
    private val _events: MutableList<SocketEvent> = mutableListOf()
) : SocketEventListener {
    public val events: List<SocketEvent>
        get() = _events.toList()

    override fun onEvent(event: SocketEvent) {
        _events.add(event)
    }
}
