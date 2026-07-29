package ai.rever.boss.plugin.browser

/**
 * Decides when a browser engine that keeps refusing to mint browsers should be recycled.
 *
 * A wedged engine is one whose Chromium process is alive — so [com.teamdev.jxbrowser.engine.Engine.isClosed]
 * stays false and [FluckEngine]'s self-healing getter never fires — but whose IPC no longer
 * works, so every `newBrowser()` fails. Left alone that state persists until the Chromium
 * process happens to die; a real incident ran 14 minutes and 24 consecutive failures before
 * clearing on its own.
 *
 * `newBrowser()` takes no URL, so a failure there is always engine-level: nothing about the
 * caller's request can cause it. Two in a row is therefore a strong signal, not a guess.
 *
 * Recycling restarts Chromium and reloads every open browser tab, so the trip is fenced:
 * a cooldown stops a re-wedge from spinning, and a per-run cap means a genuinely dead engine
 * degrades to the pre-existing behaviour (error surfaced, manual retry) instead of looping.
 *
 * Pure and lock-guarded — no JxBrowser types, and the caller supplies the clock, so the
 * policy is unit-testable without an engine.
 */
internal class EngineWedgeDetector(
    private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val maxRecycles: Int = DEFAULT_MAX_RECYCLES,
) {
    private val lock = Any()
    private var consecutiveFailures = 0
    private var recycleCount = 0
    private var lastRecycleMs: Long? = null
    private var lastRecycledGeneration: Long? = null

    /** A browser was created successfully — the engine is demonstrably fine. */
    fun recordSuccess() {
        synchronized(lock) { consecutiveFailures = 0 }
    }

    /**
     * Record a `newBrowser()` failure against [generation].
     *
     * @return true when the caller should recycle the engine. Returns true at most once per
     *   engine generation, and never more than [maxRecycles] times per app run.
     */
    fun recordFailure(
        nowMs: Long,
        generation: Long,
    ): Boolean =
        synchronized(lock) {
            // Failures against a generation we already recycled are in-flight stragglers
            // racing the swap — they describe the engine we just replaced, not its
            // replacement, so they must not count toward tripping again.
            if (lastRecycledGeneration == generation) {
                return@synchronized false
            }

            consecutiveFailures++

            val tripped =
                consecutiveFailures >= failureThreshold &&
                    recycleCount < maxRecycles &&
                    (lastRecycleMs?.let { nowMs - it >= cooldownMs } ?: true)

            if (tripped) {
                consecutiveFailures = 0
                recycleCount++
                lastRecycleMs = nowMs
                lastRecycledGeneration = generation
            }
            tripped
        }

    /** How many automatic recycles have been performed this run — for logging. */
    val recycleAttempts: Int
        get() = synchronized(lock) { recycleCount }

    /**
     * True when the engine still looks wedged but the recycle budget is spent, i.e. we have
     * stopped trying to repair it. Drives [FluckEngine.isEngineHealthy].
     */
    val isExhausted: Boolean
        get() =
            synchronized(lock) {
                recycleCount >= maxRecycles && consecutiveFailures >= failureThreshold
            }

    companion object {
        const val DEFAULT_FAILURE_THRESHOLD = 2
        const val DEFAULT_COOLDOWN_MS = 30_000L
        const val DEFAULT_MAX_RECYCLES = 3
    }
}
