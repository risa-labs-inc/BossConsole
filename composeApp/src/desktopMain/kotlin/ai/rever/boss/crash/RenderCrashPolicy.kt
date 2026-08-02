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
     * Forget the current burst because recovery made progress.
     *
     * Without this the budget races the narrowing loop and loses. The unattributed
     * path is exactly the case where the same dirty node throws every frame, so
     * faults arrive ~16ms apart and always inside one window, while narrowing
     * costs one fault to rebuild plus one per suspect tried. With three panels
     * mounted that is rebuild, suspect #1, release #1 and suspect #2 — and the
     * fourth fault escalates and disposes the window, killing the app before the
     * culprit was found and after quarantining two innocents.
     *
     * So only faults where recovery achieved *nothing* count toward escalation. A
     * rebuild or a fresh quarantine is progress and resets the budget; a fault
     * that recovery could do nothing with does not, and still escalates.
     */
    @Synchronized
    fun noteRecoveryProgress() = recentFailures.clear()
}
