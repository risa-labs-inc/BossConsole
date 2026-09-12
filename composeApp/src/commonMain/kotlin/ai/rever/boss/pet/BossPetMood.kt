package ai.rever.boss.pet

/**
 * What the floating BOSS pet is showing right now.
 *
 * One value, derived entirely from [BossPetController]'s task bookkeeping so the same inputs always
 * produce the same mood - which is what lets the controller be unit-tested without a UI. The window
 * in `desktopMain` renders each of these; nothing here knows about Compose.
 *
 * The four moods map onto the issue's requested states (idle, working, completed,
 * needs-attention/failed). [Completed] and [Failed] carry the label of the task they are announcing
 * so the pet can say *which* action finished rather than a bare "done".
 */
sealed interface BossPetMood {
    /** Nothing is running and nothing is waiting to be acknowledged. The resting state. */
    data object Idle : BossPetMood

    /**
     * At least one task is in flight. [activeCount] is how many, so the pet can distinguish one
     * agent working from several, and never shows a number below 1.
     */
    data class Working(
        val activeCount: Int,
    ) : BossPetMood

    /**
     * The next unacknowledged task finished successfully and has not been acknowledged yet. [label] names it
     * (e.g. "Build finished"). Reverts to [Idle] on [BossPetController.dismissAnnouncement] or after
     * the auto-idle timeout, or to [Working] if another task is still running.
     */
    data class Completed(
        val label: String,
        val taskId: String,
        val sequence: Long = 0,
        val requiresAcknowledgement: Boolean = false,
    ) : BossPetMood

    /**
     * The next unacknowledged task failed and wants attention. [label] names it. Unlike [Completed] this never
     * auto-dismisses on a timeout - a failure the user never saw is the thing this state exists to
     * prevent - so it clears only on an explicit [BossPetController.dismissAnnouncement].
     */
    data class Failed(
        val label: String,
        val taskId: String,
        val sequence: Long = 0,
        val occurrences: Long = 1,
    ) : BossPetMood
}
