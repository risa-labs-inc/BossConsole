package ai.rever.boss.crash

/**
 * Decides whether an exception escaping the Compose render loop should be
 * contained or allowed to take the window down.
 *
 * Compose's default `WindowExceptionHandler` shows a dialog and **disposes the
 * window**, which in a Compose `application {}` ends the app. That is the right
 * answer for a genuinely unrecoverable host fault and the wrong one for a single
 * bad frame — and until [ai.rever.boss.plugin.sandbox.ui.PluginRenderBoundary]
 * covers every path, a plugin's layout bug can still arrive here unattributed
 * (BossConsole-Releases#16 killed the app exactly this way).
 *
 * Containing unconditionally is not the answer either: if the scene is genuinely
 * corrupt, every subsequent frame throws and the user gets an app that repaints
 * forever without working. So: tolerate a burst, then stop pretending.
 *
 * [shouldContain] returns true while failures stay under [maxFailures] within
 * [windowMillis], and false once they don't — at which point the caller should
 * fall back to the default handler and let the app go down honestly.
 *
 * Thread-safe: render exceptions arrive on the AWT event thread, but nothing
 * guarantees a single window or a single thread.
 */
class RenderCrashPolicy(
    private val maxFailures: Int = DEFAULT_MAX_FAILURES,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    companion object {
        /**
         * Three is enough to ride out a transient bad frame and the retry that
         * usually follows it, without masking a scene that throws every frame.
         */
        const val DEFAULT_MAX_FAILURES = 3
        const val DEFAULT_WINDOW_MILLIS = 10_000L
    }

    private val recentFailures = ArrayDeque<Long>()

    /**
     * Record a render failure and report whether to contain it.
     *
     * @return true to swallow and keep the window alive, false to escalate.
     */
    @Synchronized
    fun recordFailureAndShouldContain(): Boolean {
        val timestamp = now()
        // Only failures inside the window count, so a healthy app that hits one
        // bad frame an hour never escalates.
        while (recentFailures.isNotEmpty() && timestamp - recentFailures.first() > windowMillis) {
            recentFailures.removeFirst()
        }
        recentFailures.addLast(timestamp)
        return recentFailures.size <= maxFailures
    }

    /** Failures currently inside the window. Exposed for logging and tests. */
    @Synchronized
    fun recentFailureCount(): Int = recentFailures.size

    /**
     * Un-count the fault just recorded, because recovery made progress on it.
     *
     * Narrowing needs room: faults from a repainting subtree arrive ~16ms apart,
     * all inside one window, while the loop spends one fault to rebuild plus one
     * per suspect. Counting those would escalate and dispose the window before
     * the culprit was found.
     *
     * It removes exactly that one fault rather than clearing the deque, and the
     * difference matters. Clearing made [Escalate][WindowExceptionRoute.Escalate]
     * unreachable whenever two or more panels were mounted: the narrowing loop
     * manufactures progress indefinitely — rebuild, suspect each plugin in turn,
     * end Unexplained, which resets the incident and re-mounts the released
     * panels so the next fault rebuilds again — so a full reset every cycle meant
     * the count never reached the limit and a genuinely corrupt scene span
     * forever. Removing one keeps the unproductive faults accumulating, so the
     * loop gets its room and escalation stays reachable.
     */
    @Synchronized
    fun noteRecoveryProgress() {
        recentFailures.removeLastOrNull()
    }
}
