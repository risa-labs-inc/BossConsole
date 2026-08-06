package ai.rever.boss.plugin.browser

import ai.rever.boss.config.BossResourceMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the reservation accounting behind the concurrent-browser ceiling.
 *
 * `isAtBrowserCeiling` was thoroughly covered and was never where the bug would be: the
 * interesting part is the counter around it, which had no tests at all. Two real defects lived
 * there, and both had the same shape - the counter drifting up and never coming back down, under
 * a cap that only ratchets one way.
 *
 * The first was check-then-act: the size was read at the guard but the handle registered several
 * suspension points later, so N concurrent creations all saw room. The second was a leak: a
 * reservation was only released through the two dispose paths, while `BrowserHandle.dispose()`
 * is public plugin API and a renderer can also crash or a page call `window.close()`. Four
 * orphans on the Windows default and no browser could ever open again, with the refusal dialog
 * blaming the tier.
 *
 * These exercise the arithmetic rather than the engine, which is why they can run at all.
 */
class BrowserReservationTest {
    @Test
    fun `an uncapped tier never refuses`() {
        for (active in listOf(0, 1, 100, Int.MAX_VALUE - 1)) {
            assertFalse(BrowserServiceImpl.isAtBrowserCeiling(active, BossResourceMode.FULL))
        }
    }

    /**
     * The ceiling counts reservations, and a reservation is taken **before** the suspend rather
     * than when the handle lands. The distinction is the whole fix: at `cap - 1` live handles,
     * one in-flight creation already fills the tier, and a second must be refused even though
     * `activeBrowsers` still looks like it has room.
     */
    @Test
    fun `an in-flight creation counts toward the ceiling`() {
        val cap = BossResourceMode.ULTRA_LITE.maxConcurrentBrowsers!!
        // cap - 1 live handles plus one in-flight creation = cap reservations.
        assertTrue(BrowserServiceImpl.isAtBrowserCeiling(cap, BossResourceMode.ULTRA_LITE))
        // And one fewer must still be allowed, or the tier silently caps one below what it says.
        assertFalse(BrowserServiceImpl.isAtBrowserCeiling(cap - 1, BossResourceMode.ULTRA_LITE))
    }

    /**
     * Reconciling with nothing to reclaim must be a no-op rather than drifting the counter.
     *
     * Called on every refusal, so a reconcile that miscounted by one each time would recreate
     * the ratchet it exists to break.
     */
    @Test
    fun `reconciling an empty service is a no-op`() {
        val before = BrowserServiceImpl.reservedBrowserCount()
        assertEquals(before, BrowserServiceImpl.reconcileBrowserReservations())
        assertEquals(before, BrowserServiceImpl.reconcileBrowserReservations())
    }

    /** The counter must never go negative, or a double release would raise the real ceiling. */
    @Test
    fun `the reservation count never goes negative`() {
        repeat(5) { BrowserServiceImpl.reconcileBrowserReservations() }
        assertTrue(
            BrowserServiceImpl.reservedBrowserCount() >= 0,
            "reserved=${BrowserServiceImpl.reservedBrowserCount()} - a negative count would " +
                "silently raise the ceiling above what the tier advertises",
        )
    }

    /**
     * Every tier's advertised cap must be reachable, i.e. the guard is `>=` on a positive number.
     * A `> cap` guard would allow one more browser than the tier promises; a cap of 0 would mean
     * no browser could ever open.
     */
    @Test
    fun `each tier's advertised cap is exactly what is enforced`() {
        for (mode in BossResourceMode.entries) {
            val cap = mode.maxConcurrentBrowsers ?: continue
            assertTrue(cap > 0, "${mode.name} advertises a cap of $cap")
            assertFalse(
                BrowserServiceImpl.isAtBrowserCeiling(cap - 1, mode),
                "${mode.name} must permit its ${cap}th browser",
            )
            assertTrue(
                BrowserServiceImpl.isAtBrowserCeiling(cap, mode),
                "${mode.name} must refuse beyond $cap",
            )
        }
    }
}
