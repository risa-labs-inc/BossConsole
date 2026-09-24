package ai.rever.boss.crash

import ai.rever.boss.plugin.sandbox.ui.PluginRenderRecovery

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
 * [recordFailureAndShouldContain] returns true while failures stay under
 * [maxFailures] within [windowMillis], and false once they don't — at which point
 * the caller should fall back to the default handler and let the app go down
 * honestly.
 *
 * Thread-safe: render exceptions arrive on the AWT event thread, but nothing
 * guarantees a single window or a single thread.
 */
class RenderCrashPolicy(
    private val maxFailures: Int = DEFAULT_MAX_FAILURES,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val incidentGapMillis: Long = DEFAULT_INCIDENT_GAP_MILLIS,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    companion object {
        /**
         * Three is enough to ride out a transient bad frame and the retry that
         * usually follows it, without masking a scene that throws every frame.
         */
        const val DEFAULT_MAX_FAILURES = 3
        const val DEFAULT_WINDOW_MILLIS = 10_000L
        const val DEFAULT_INCIDENT_GAP_MILLIS = PluginRenderRecovery.REBUILD_GRACE_MILLIS
    }

    private data class RecordedFailure(
        val id: Long,
        val timestamp: Long,
    )

    private val recentFailures = ArrayDeque<RecordedFailure>()
    private val currentThreadFailureId = ThreadLocal<Long?>()
    private var nextFailureId = 0L
    private var incidentStartedAt: Long? = null
    private var lastFailureAt: Long? = null

    /**
     * Record a render failure and report whether to contain it.
     *
     * @return true to swallow and keep the window alive, false to escalate.
     */
    @Synchronized
    fun recordFailureAndShouldContain(): Boolean {
        val timestamp = now()
        val previousFailureAt = lastFailureAt
        if (previousFailureAt == null || timestamp - previousFailureAt !in 0..incidentGapMillis) {
            incidentStartedAt = timestamp
        }
        lastFailureAt = timestamp
        // Only failures inside the window count, so a healthy app that hits one
        // bad frame an hour never escalates.
        while (recentFailures.isNotEmpty() && timestamp - recentFailures.first().timestamp > windowMillis) {
            recentFailures.removeFirst()
        }
        val recorded = RecordedFailure(id = ++nextFailureId, timestamp = timestamp)
        recentFailures.addLast(recorded)
        currentThreadFailureId.set(recorded.id)
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
     * per suspect. Counting actual rebuild or quarantine progress immediately would
     * escalate and dispose the window before the culprit was found. All refunds are
     * nevertheless bounded by the same incident deadline as [noteSettlingFault], so
     * a slow recovery loop cannot manufacture progress forever.
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
    fun noteRecoveryProgress(): Boolean = refundCurrentThreadFailureWithinIncident()

    /**
     * Refund queued work from a just-quarantined subtree while this incident is young.
     *
     * A per-suspect refund is not bounded: with enough mounted plugins, one full
     * narrowing pass takes longer than [windowMillis], so counted `Unexplained`
     * outcomes age out before the next pass and escalation becomes unreachable.
     * This deadline is anchored at the first failure in a recovery incident and is
     * never extended by recovery outcomes. Once it expires, settling faults remain
     * counted and the ordinary circuit breaker escalates within [maxFailures] more
     * contained frames. A gap longer than [incidentGapMillis] starts a new incident
     * and earns a fresh allowance. The default matches
     * [PluginRenderRecovery.REBUILD_GRACE_MILLIS], so an intermittent stream cannot
     * keep yesterday's expired anchor while recovery itself starts a fresh cycle.
     *
     * @return true when the just-recorded fault was refunded.
     */
    @Synchronized
    fun noteSettlingFault(): Boolean = refundCurrentThreadFailureWithinIncident()

    /** Finish the current fault without refunding it. */
    fun noteUnproductiveFault() {
        currentThreadFailureId.remove()
    }

    /**
     * Refund the exact fault recorded by this thread, rather than whichever window
     * happened to append to the shared deque most recently.
     */
    private fun refundCurrentThreadFailureWithinIncident(): Boolean {
        val failureId = currentThreadFailureId.get()
        currentThreadFailureId.remove()
        val failure = recentFailures.firstOrNull { it.id == failureId }
        val startedAt = incidentStartedAt
        val canRefund =
            failure != null &&
                startedAt != null &&
                failure.timestamp - startedAt in 0..windowMillis
        if (canRefund) recentFailures.remove(failure)
        return canRefund
    }
}
