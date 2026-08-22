package ai.rever.boss.performance

import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether a status-bar footprint indicator is currently on screen anywhere.
 *
 * A count rather than a flag because a BOSS session can have several windows, each with its own
 * status bar, and they mount and dispose independently. A boolean would have the last window to
 * close switch sampling off for the ones still open, or the first to open leave it on forever.
 *
 * Driven from the indicator composable's own lifecycle, which is the exact signal: the composable
 * is only in the tree when the bottom bar is visible *and* the indicator is enabled in settings,
 * so mounting means "a person can see this number" without any of the three predicates being
 * restated here and drifting.
 */
object FootprintDisplay {
    private val mounted = AtomicInteger(0)

    /** True while at least one indicator is in the composition. */
    val isOnScreen: Boolean get() = mounted.get() > 0

    /**
     * Report an indicator entering or leaving the composition.
     *
     * Clamped at zero so an unpaired dispose cannot drive the count negative and switch sampling
     * off for every other window. Compose does not promise a dispose for every mount under all
     * teardown paths, and a latched-off sampler would be silent and permanent.
     */
    fun setMounted(isMounted: Boolean) {
        if (isMounted) {
            mounted.incrementAndGet()
        } else {
            mounted.updateAndGet { (it - 1).coerceAtLeast(0) }
        }
    }
}
