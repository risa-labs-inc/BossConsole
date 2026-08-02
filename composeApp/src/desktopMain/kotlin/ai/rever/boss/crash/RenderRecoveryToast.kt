package ai.rever.boss.crash

import ai.rever.boss.plugin.sandbox.ui.PluginRenderRecovery

/** How long a render-recovery toast stays up, and so how long a repeat is suppressed. */
const val RENDER_RECOVERY_TOAST_MILLIS = 8_000L

/**
 * What to tell the user after an unattributed render exception, and whether to say
 * it at all.
 *
 * Extracted from `main.kt` because this decision has now regressed twice while
 * living inline in the window exception handler, where nothing could assert it:
 *
 * 1. First it was gated on "did recovery make progress", which silenced
 *    [PluginRenderRecovery.Outcome.Unexplained] and
 *    [PluginRenderRecovery.Outcome.NotPluginRelated] — the two outcomes that leave
 *    the window *broken* and so the two the user most needs to hear about.
 * 2. Then it was gated on the message differing from the previous one, which fixed
 *    that and broke two other things: a narrowing cycle emits a *different* verdict
 *    on almost every frame, so at 16ms intervals it still toasted ~60×/s; and
 *    because the gate was process-lifetime, a `Rebuilt` at 09:00 silenced an
 *    unrelated `Rebuilt` at 14:00 completely.
 *
 * Hence per-message *and* time-bounded: each distinct verdict speaks at most once
 * per toast duration. A storm is capped at roughly one toast per mounted plugin per
 * 8 seconds instead of sixty a second, and an hours-later recurrence is never
 * silently swallowed.
 */
class RenderRecoveryToaster(
    private val durationMs: Long = RENDER_RECOVERY_TOAST_MILLIS,
) {
    /**
     * When each message was last shown.
     *
     * EDT-confined — the window exception handler is the only caller — so this is
     * deliberately unsynchronized. Bounded by clearing wholesale: keys are one per
     * verdict shape, so the ceiling is only reachable by a session that has cycled
     * through an implausible number of plugin sets, and losing the history just
     * means one extra toast.
     */
    private val lastShownAt = HashMap<String, Long>()

    /**
     * The message to show for [outcome], or null when it was shown too recently.
     *
     * @param now monotonic milliseconds; injectable so a test does not have to sleep.
     */
    fun toastFor(
        outcome: PluginRenderRecovery.Outcome,
        now: Long,
    ): String? {
        val message = messageFor(outcome)
        val previous = lastShownAt[message]
        if (previous != null && now - previous < durationMs) return null
        if (lastShownAt.size >= MAX_TRACKED_MESSAGES) lastShownAt.clear()
        lastShownAt[message] = now
        return message
    }

    companion object {
        private const val MAX_TRACKED_MESSAGES = 64

        /**
         * The wording, in one place so it is reviewable as a set: the whole point of
         * recovery is that the user finds out something happened, since the failure
         * it handles used to leave a silently broken window.
         */
        fun messageFor(outcome: PluginRenderRecovery.Outcome): String =
            when (outcome) {
                is PluginRenderRecovery.Outcome.Quarantined -> {
                    "Paused ${outcome.plugins.joinToString()} — it kept failing to render. " +
                        "Restart it from the panel menu."
                }

                is PluginRenderRecovery.Outcome.Rebuilt -> {
                    "A plugin panel failed to render and was reloaded."
                }

                PluginRenderRecovery.Outcome.Unexplained -> {
                    "A UI component keeps failing to render. No plugin accounts for it; " +
                        "the window is still usable."
                }

                PluginRenderRecovery.Outcome.NotPluginRelated -> {
                    "A UI component failed to render and was recovered."
                }
            }
    }
}
