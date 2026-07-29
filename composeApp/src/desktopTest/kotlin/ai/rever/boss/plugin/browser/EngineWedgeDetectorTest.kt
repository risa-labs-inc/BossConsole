package ai.rever.boss.plugin.browser

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [EngineWedgeDetector] — the policy that decides when a browser engine that
 * keeps refusing to create browsers should be recycled. Pure: no JxBrowser engine required,
 * and the clock is supplied by the caller, matching the style of [BrowserServiceImplTest].
 */
class EngineWedgeDetectorTest {
    private fun detector(
        threshold: Int = 2,
        cooldownMs: Long = 30_000L,
        maxRecycles: Int = 3,
    ) = EngineWedgeDetector(threshold, cooldownMs, maxRecycles)

    @Test
    fun `trips on the second consecutive failure`() {
        val detector = detector()

        assertFalse(detector.recordFailure(nowMs = 0, generation = 1), "one failure is not enough")
        assertTrue(detector.recordFailure(nowMs = 100, generation = 1), "second consecutive failure trips")
    }

    @Test
    fun `a success between failures resets the counter`() {
        val detector = detector()

        assertFalse(detector.recordFailure(nowMs = 0, generation = 1))
        detector.recordSuccess()
        assertFalse(detector.recordFailure(nowMs = 100, generation = 1), "counter restarted after the success")
        assertTrue(detector.recordFailure(nowMs = 200, generation = 1))
    }

    @Test
    fun `failures against an already-recycled generation do not count`() {
        val detector = detector()

        assertFalse(detector.recordFailure(nowMs = 0, generation = 1))
        assertTrue(detector.recordFailure(nowMs = 100, generation = 1))

        // In-flight creations that were already racing the swap land here. They describe the
        // engine we just replaced, so they must neither trip again nor accumulate.
        assertFalse(detector.recordFailure(nowMs = 110, generation = 1))
        assertFalse(detector.recordFailure(nowMs = 120, generation = 1))
        assertFalse(detector.recordFailure(nowMs = 130, generation = 1))

        // The first failure on the replacement engine is only the first of its threshold.
        assertFalse(detector.recordFailure(nowMs = 40_000, generation = 2))
        assertTrue(detector.recordFailure(nowMs = 40_100, generation = 2))
    }

    @Test
    fun `cooldown suppresses a second recycle inside the window`() {
        val detector = detector()

        assertFalse(detector.recordFailure(nowMs = 0, generation = 1))
        assertTrue(detector.recordFailure(nowMs = 100, generation = 1))

        // Fresh generation, and enough failures to clear the threshold, but too soon.
        assertFalse(detector.recordFailure(nowMs = 200, generation = 2))
        assertFalse(detector.recordFailure(nowMs = 300, generation = 2), "still inside the 30s cooldown")
        assertFalse(detector.recordFailure(nowMs = 29_999, generation = 2), "boundary is exclusive")

        assertTrue(detector.recordFailure(nowMs = 30_100, generation = 2), "recycles once the cooldown expires")
    }

    @Test
    fun `stops recycling after the per-run cap and reports itself exhausted`() {
        val detector = detector(maxRecycles = 2)

        assertFalse(detector.recordFailure(nowMs = 0, generation = 1))
        assertTrue(detector.recordFailure(nowMs = 100, generation = 1))

        assertFalse(detector.recordFailure(nowMs = 60_000, generation = 2))
        assertTrue(detector.recordFailure(nowMs = 60_100, generation = 2))

        assertFalse(detector.isExhausted, "budget spent but the engine has not failed since")

        // Budget spent: a genuinely dead engine now degrades to the pre-existing behaviour
        // (failure surfaced to the caller) instead of restarting Chromium forever.
        assertFalse(detector.recordFailure(nowMs = 120_000, generation = 3))
        assertFalse(detector.recordFailure(nowMs = 120_100, generation = 3))
        assertFalse(detector.recordFailure(nowMs = 180_000, generation = 3), "cap holds even after the cooldown")

        assertTrue(detector.isExhausted)
        assertTrue(detector.recycleAttempts == 2)
    }

    @Test
    fun `a later success clears the exhausted state`() {
        val detector = detector(maxRecycles = 1)

        assertFalse(detector.recordFailure(nowMs = 0, generation = 1))
        assertTrue(detector.recordFailure(nowMs = 100, generation = 1))

        assertFalse(detector.recordFailure(nowMs = 60_000, generation = 2))
        assertFalse(detector.recordFailure(nowMs = 60_100, generation = 2))
        assertTrue(detector.isExhausted)

        detector.recordSuccess()
        assertFalse(detector.isExhausted, "the engine recovered on its own")
    }
}
