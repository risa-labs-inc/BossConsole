package ai.rever.boss.components.workspaces

/**
 * Prevents a Save entry point from starting overlapping writes.
 *
 * A press made while a save is in flight is remembered as one queued press. When the write
 * settles, the caller decides how to handle that queued press:
 *
 * - the unnamed File-menu save reruns once using the latest layout;
 * - the named Save Space dialog ignores it, because replaying the same name would mint another
 *   Space.
 *
 * The latch belongs to the call site rather than [WorkspaceManager], because only the caller
 * knows whether an overlapping action should be replayed. `saveCurrentWorkspace` returns before
 * its write settles, so [settle] clears [inFlight] from the completion callback.
 */
internal class SaveInFlightLatch {
    var inFlight = false
        private set

    private var queued = false

    /**
     * Returns `true` when this press may start immediately. While a save is in flight, remembers
     * at most one queued press and returns `false`.
     */
    fun press(): Boolean {
        if (inFlight) {
            queued = true
            return false
        }
        return true
    }

    /** Marks a save as started; further presses are remembered until [settle]. */
    fun begin() {
        inFlight = true
        queued = false
    }

    /**
     * Clears the in-flight state before invoking [onQueued]. If the callback throws, the latch
     * therefore remains open instead of wedging the Save action.
     */
    fun settle(onQueued: () -> Unit) {
        inFlight = false
        if (queued) {
            queued = false
            onQueued()
        }
    }
}
