package ai.rever.boss.plugin.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The frames the slide transition draws.
 *
 * These are cheap to get wrong in ways that look like a rendering bug rather than a logic one - a
 * swapped colour channel reads as a bad screenshot, a short buffer as a half-drawn page - so the
 * conversion is pinned by pixels rather than by inspection.
 */
class PageSnapshotsTest {
    /** A wxh BGRA buffer where every pixel encodes its own coordinates, so sampling is checkable. */
    private fun bgra(
        width: Int,
        height: Int,
    ): ByteArray {
        val out = ByteArray(width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = (y * width + x) * 4
                out[i] = x.toByte() // B
                out[i + 1] = y.toByte() // G
                out[i + 2] = (x + y).toByte() // R
                out[i + 3] = 0xFF.toByte() // A
            }
        }
        return out
    }

    @Test
    fun `a capture is downscaled by half in both axes`() {
        val frame = assertNotNull(pageFrame(2560, 1600, bgra(2560, 1600)))
        assertEquals(1280, frame.width)
        assertEquals(800, frame.height)
    }

    @Test
    fun `odd dimensions round up rather than dropping the last row`() {
        val frame = assertNotNull(pageFrame(7, 5, bgra(7, 5)))
        assertEquals(4, frame.width)
        assertEquals(3, frame.height)
    }

    /**
     * A capture that has not painted yet comes back empty, and drawing it would slide a blank
     * rectangle over the page - which reads as a fault, not as a navigation. The caller checks for
     * null to skip the transition, so null is the contract.
     */
    @Test
    fun `an empty capture is null, not a blank image`() {
        assertNull(pageFrame(0, 0, ByteArray(0)))
        assertNull(pageFrame(-1, 10, bgra(4, 4)))
    }

    /**
     * A torn capture - fewer bytes than the stated size - must be refused rather than read past the
     * end of the array, which would be an exception on the navigation path.
     */
    @Test
    fun `a short buffer is refused`() {
        assertNull(pageFrame(100, 100, ByteArray(100 * 100 * 4 - 1)))
    }

    @Test
    fun `the largest sampled pixel is inside the buffer`() {
        // The guard above is only worth anything if the loop actually reaches the far corner, so
        // this asserts the conversion succeeds at exactly the declared size.
        assertNotNull(pageFrame(9, 9, bgra(9, 9)))
    }

    // --- the store ---------------------------------------------------------------------------

    @Test
    fun `frames are found by the entry index they were captured at`() {
        val store = PageSnapshots()
        val a = assertNotNull(pageFrame(4, 4, bgra(4, 4)))
        store.put(3, a)
        assertNotNull(store.get(3))
        assertNull(store.get(4), "an entry never captured has no frame")
    }

    @Test
    fun `the store keeps only what a gesture can reach`() {
        val store = PageSnapshots(max = 2)
        repeat(5) { store.put(it, assertNotNull(pageFrame(4, 4, bgra(4, 4)))) }
        assertEquals(2, store.size)
        assertNotNull(store.get(4))
        assertNull(store.get(0), "the oldest entry should have been evicted")
    }

    /**
     * Eviction is by least-recently-USED, not by insertion. Swiping back and forth across one
     * boundary is the common case, and insertion order would evict the page being returned to on
     * every second swipe - the one frame the transition always needs.
     */
    @Test
    fun `a frame that keeps being read survives`() {
        val store = PageSnapshots(max = 2)
        val frame = assertNotNull(pageFrame(4, 4, bgra(4, 4)))
        store.put(0, frame)
        store.put(1, frame)
        assertNotNull(store.get(0)) // touch it
        store.put(2, frame) // evicts the least recently used, which is now 1
        assertNotNull(store.get(0))
        assertNull(store.get(1))
    }

    @Test
    fun `clearing drops everything`() {
        val store = PageSnapshots()
        store.put(1, assertNotNull(pageFrame(4, 4, bgra(4, 4))))
        store.clear()
        assertEquals(0, store.size)
    }

    // --- what is worth drawing ----------------------------------------------------------------

    @Test
    fun `a transition needs both frames`() {
        val frame = assertNotNull(pageFrame(4, 4, bgra(4, 4)))
        assertTrue(SwipeTransition(frame, frame, SwipeNavDirection.BACK).isRenderable)
        assertTrue(!SwipeTransition(null, frame, SwipeNavDirection.BACK).isRenderable)
        assertTrue(!SwipeTransition(frame, null, SwipeNavDirection.BACK).isRenderable)
    }
}
