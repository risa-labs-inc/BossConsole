package ai.rever.boss.kernel

/**
 * Counts overlapping reaps and fences work prepared before them. A new request after a completed
 * mode-switch reap is allowed; a request prepared before it is stale even after the depth reaches zero.
 * Process creation runs outside the monitor so it cannot consume a shutdown hook's waiting budget.
 */
internal class ReapSpawnGate {
    private var depth = 0
    private var generation = 0L

    @Synchronized
    fun generation(): Long = generation

    @Synchronized
    fun isReaping(): Boolean = depth > 0

    @Synchronized
    fun beginReap() {
        depth++
        generation++
    }

    @Synchronized
    fun endReap() {
        check(depth > 0) { "Unbalanced reap completion" }
        depth--
    }

    private fun admits(expected: Long): Boolean = synchronized(this) { depth == 0 && generation == expected }

    fun <T> spawn(
        expectedGeneration: Long,
        createChild: () -> T,
        discardChild: (T) -> Unit,
    ): T {
        if (!admits(expectedGeneration)) throw ReapAdmissionException()
        val child = createChild()
        if (!admits(expectedGeneration)) {
            discardChild(child)
            throw ReapAdmissionException()
        }
        return child
    }
}

internal class ReapAdmissionException : IllegalStateException("A reap interrupted process startup")

internal val reapSpawnGate = ReapSpawnGate()
