package ai.rever.boss.sharing

/**
 * Retiring a session wins over late resource creation. Ownership publication and retirement are
 * atomic, but teardown runs outside the monitor: native/EDT cleanup may call back into the host.
 */
internal class OwnedAppResources : AutoCloseable {
    private val monitor = Any()
    private val resources = mutableListOf<AutoCloseable>()

    @Volatile var isClosed: Boolean = false
        private set

    fun own(resource: AutoCloseable): Boolean {
        val retireNow =
            synchronized(monitor) {
                if (isClosed) {
                    true
                } else {
                    if (resources.none { it === resource }) resources.add(resource)
                    false
                }
            }
        if (retireNow) runCatching { resource.close() }
        return !retireNow
    }

    override fun close() {
        val retired =
            synchronized(monitor) {
                if (isClosed) return
                isClosed = true
                resources.toList().asReversed().also { resources.clear() }
            }
        retired.forEach { runCatching { it.close() } }
    }
}
