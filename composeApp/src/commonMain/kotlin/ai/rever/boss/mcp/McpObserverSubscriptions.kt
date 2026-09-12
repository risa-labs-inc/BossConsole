package ai.rever.boss.mcp

/**
 * Host-owned registration handles. Snapshots retain handles, not unloaded plugin instances.
 * Callbacks must be non-blocking. Removal severs the handle's reference, so subsequent
 * dispatch cannot admit the old observer. A callback admitted before removal may finish.
 * No plugin code runs under the registry lock, including during reentrant removal.
 */
internal class McpObserverSubscriptions<T : Any> {
    private val lock = Any()
    private val subscriptions = mutableMapOf<String, Subscription<T>>()

    fun register(
        id: String,
        observer: T,
    ) {
        synchronized(lock) { subscriptions.putIfAbsent(id, Subscription(observer)) }
    }

    fun unregister(id: String) {
        val subscription = synchronized(lock) { subscriptions.remove(id) }
        subscription?.close()
    }

    fun snapshot(): List<Subscription<T>> = synchronized(lock) { subscriptions.values.toList() }

    internal class Subscription<T : Any>(
        observer: T,
    ) {
        @Volatile
        private var observer: T? = observer

        // Never inspect plugin-defined Throwable properties or observer metadata in a failure path.
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        fun dispatch(action: (T) -> Unit) {
            val current = observer ?: return
            try {
                action(current)
            } catch (_: Throwable) {
                // Best effort observation: the tool result and sibling observers remain unaffected.
            }
        }

        fun close() {
            observer = null
        }
    }
}
