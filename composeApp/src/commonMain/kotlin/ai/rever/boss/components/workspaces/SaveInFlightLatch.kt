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
 *
 * This latch is main-thread-only by design (tied to Compose UI event dispatch). It is not
 * synchronized for concurrent cross-thread access; all calls to [press], [tryStart], [begin], and
 * [settle] must occur on the main (UI) thread.
 */
internal class SaveInFlightLatch {
    var inFlight = false
        private set

    private var queued = false

    /**
     * Returns `true` when this press may start immediately. While a save is in flight, remembers
     * at most one queued press and returns `false`.
     *
     * When this returns `true`, the caller must reach [begin] synchronously before yielding control.
     * Where possible, prefer [tryStart] to fold both into one operation.
     */
    fun press(): Boolean {
        if (inFlight) {
            queued = true
            return false
        }
        return true
    }

    /**
     * Attempts to start a save immediately, folding [press] and [begin] into a single step.
     *
     * Returns `true` when the save starts immediately, transitioning [inFlight] to `true` and
     * clearing [queued]. While a save is already in flight, remembers at most one queued press
     * and returns `false`.
     *
     * Prefer this method when the caller does not require multi-step synchronous preparation
     * between checking availability and starting the save.
     */
    fun tryStart(): Boolean {
        if (inFlight) {
            queued = true
            return false
        }
        inFlight = true
        queued = false
        return true
    }

    /**
     * Marks a save as started; further presses are remembered until [settle].
     *
     * Must be reached synchronously following a successful [press].
     */
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
