package ai.rever.boss.components.plugin

/** A new release gets a fresh budget; repeated failure of the same release never cycles forever. */
internal class AutomaticPluginRetryPolicy {
    private data class Failure(
        val version: String,
        val attempts: Int,
        val nextAttempt: Long,
    )

    private val failures = mutableMapOf<String, Failure>()

    fun canAttempt(
        id: String,
        version: String,
        now: Long,
    ): Boolean {
        val failure = failures[id]?.takeIf { it.version == version } ?: return true
        return failure.attempts < 3 && now >= failure.nextAttempt
    }

    fun failed(
        id: String,
        version: String,
        now: Long,
    ): Boolean {
        val attempts = (failures[id]?.takeIf { it.version == version }?.attempts ?: 0) + 1
        failures[id] = Failure(version, attempts, now + 5 * 60_000)
        return attempts < 3
    }

    fun clear(id: String) {
        failures.remove(id)
    }
}
