package ai.rever.boss.crash

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the containment decision for exceptions escaping the Compose render
 * loop.
 *
 * Both directions matter and they pull against each other. Containing too
 * eagerly leaves the user an app that repaints forever without working;
 * escalating too eagerly restores the behaviour this exists to fix, where one
 * bad frame disposes the window and ends the session
 * (BossConsole-Releases#16).
 */
class RenderCrashPolicyTest {
    /** Controllable clock — a real one would make the window assertions timing-dependent. */
    private class FakeClock(
        var now: Long = 0L,
    ) {
        fun advance(millis: Long) {
            now += millis
        }
    }

    private fun policy(
        clock: FakeClock,
        maxFailures: Int = RenderCrashPolicy.DEFAULT_MAX_FAILURES,
        windowMillis: Long = RenderCrashPolicy.DEFAULT_WINDOW_MILLIS,
    ) = RenderCrashPolicy(maxFailures = maxFailures, windowMillis = windowMillis, now = { clock.now })

    @Test
    fun `a burst up to the limit is contained`() {
        val clock = FakeClock()
        val policy = policy(clock)

        repeat(3) { attempt ->
            assertTrue(policy.recordFailureAndShouldContain(), "failure ${attempt + 1} should have been contained")
        }
    }

    @Test
    fun `the failure past the limit escalates`() {
        val clock = FakeClock()
        val policy = policy(clock)

        repeat(3) { policy.recordFailureAndShouldContain() }

        assertFalse(policy.recordFailureAndShouldContain(), "a scene that keeps throwing must not be contained forever")
    }

    @Test
    fun `failures older than the window do not count`() {
        val clock = FakeClock()
        val policy = policy(clock)

        repeat(3) { policy.recordFailureAndShouldContain() }
        clock.advance(10_001)

        assertTrue(
            policy.recordFailureAndShouldContain(),
            "an app that hits one bad frame long after the last one is healthy, not looping",
        )
        assertTrue(policy.recentFailureCount() == 1, "stale failures should have been discarded")
    }

    @Test
    fun `failures just inside the window still count`() {
        val clock = FakeClock()
        val policy = policy(clock)

        repeat(3) { policy.recordFailureAndShouldContain() }
        // Inside the window by a millisecond: this is still the same burst.
        clock.advance(9_999)

        assertFalse(policy.recordFailureAndShouldContain(), "a failure inside the window must not reset the count")
    }

    @Test
    fun `a slow trickle never escalates`() {
        val clock = FakeClock()
        val policy = policy(clock)

        // One failure per minute, far apart: annoying, but the app is rendering.
        repeat(20) {
            assertTrue(policy.recordFailureAndShouldContain(), "a widely spaced failure should always be contained")
            clock.advance(60_000)
        }
    }

    @Test
    fun `recovery progress resets the budget so narrowing can finish`() {
        // The budget used to race the narrowing loop and win. Faults from a
        // repainting subtree arrive ~16ms apart, so they all land in one window,
        // while narrowing spends one fault per suspect. With three panels the
        // fourth fault escalated and disposed the window — killing the app before
        // the culprit was found.
        val clock = FakeClock()
        val policy = policy(clock)

        repeat(10) {
            assertTrue(
                policy.recordFailureAndShouldContain(),
                "a fault that recovery is making progress on must not escalate",
            )
            policy.noteRecoveryProgress()
            clock.advance(16)
        }
    }

    @Test
    fun `faults recovery cannot help with still escalate`() {
        // The other half: progress resets the budget, so no progress must not.
        val clock = FakeClock()
        val policy = policy(clock)

        repeat(RenderCrashPolicy.DEFAULT_MAX_FAILURES) {
            assertTrue(policy.recordFailureAndShouldContain())
            clock.advance(16)
        }

        assertFalse(
            policy.recordFailureAndShouldContain(),
            "without progress the breaker must still give up",
        )
    }

    @Test
    fun `the no-arg constructor uses the documented defaults`() {
        // main.kt constructs it with no arguments, so the defaults are the values
        // that actually ship.
        val policy = RenderCrashPolicy()

        repeat(RenderCrashPolicy.DEFAULT_MAX_FAILURES) {
            assertTrue(policy.recordFailureAndShouldContain(), "a failure within the default budget")
        }
        assertFalse(policy.recordFailureAndShouldContain(), "the failure past the default budget must escalate")
    }
}
