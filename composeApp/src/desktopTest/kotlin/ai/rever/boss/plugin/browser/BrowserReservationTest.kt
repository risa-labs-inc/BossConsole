package ai.rever.boss.plugin.browser

import ai.rever.boss.config.BossResourceMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    // region LRU eviction

    private val now = 1_000_000L
    private val grace = 30_000L

    private fun candidate(
        id: String,
        idleMs: Long,
        pointerOver: Boolean = false,
    ) = BrowserServiceImpl.BrowserEvictCandidate(
        id = id,
        lastInteractionMs = now - idleMs,
        isPointerOver = pointerOver,
    )

    @Test
    fun `the least recently used browser is the victim`() {
        val victim =
            BrowserServiceImpl.selectBrowserEvictionVictim(
                listOf(
                    candidate("fresh", idleMs = grace + 1),
                    candidate("oldest", idleMs = 10 * grace),
                    candidate("middle", idleMs = 5 * grace),
                ),
                nowMs = now,
            )
        assertEquals("oldest", victim)
    }

    /**
     * Closing the browser the user is looking at, in order to open one they just asked for, is a
     * strictly worse outcome than declining the new one. This is the case that would make the
     * feature feel like a malfunction rather than a policy.
     */
    @Test
    fun `the browser under the pointer is never evicted`() {
        val victim =
            BrowserServiceImpl.selectBrowserEvictionVictim(
                listOf(candidate("watched", idleMs = 100 * grace, pointerOver = true)),
                nowMs = now,
            )
        assertNull(victim, "the browser under the pointer must survive even when it is the oldest")
    }

    @Test
    fun `the pointer wins over age when both are candidates`() {
        val victim =
            BrowserServiceImpl.selectBrowserEvictionVictim(
                listOf(
                    candidate("watched-and-oldest", idleMs = 100 * grace, pointerOver = true),
                    candidate("idle", idleMs = 2 * grace),
                ),
                nowMs = now,
            )
        assertEquals("idle", victim)
    }

    /**
     * Without a grace period, opening several tabs quickly under a reduced tier would evict the
     * one opened moments earlier, so a burst of opens cannibalises itself.
     */
    @Test
    fun `a browser used moments ago is not evicted`() {
        assertNull(
            BrowserServiceImpl.selectBrowserEvictionVictim(
                listOf(candidate("justOpened", idleMs = 0), candidate("alsoRecent", idleMs = 5_000)),
                nowMs = now,
            ),
        )
    }

    @Test
    fun `refusing is the fallback when every candidate is protected`() {
        // Null here is what keeps the ceiling a ceiling: the caller declines, visibly and with a
        // reason, rather than closing something it should not.
        assertNull(BrowserServiceImpl.selectBrowserEvictionVictim(emptyList(), nowMs = now))
    }

    @Test
    fun `a browser idle exactly the grace period is eligible`() {
        assertEquals(
            "borderline",
            BrowserServiceImpl.selectBrowserEvictionVictim(
                listOf(candidate("borderline", idleMs = grace)),
                nowMs = now,
            ),
        )
    }

    // endregion
}
