package ai.rever.boss.crash

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
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
        incidentGapMillis: Long = RenderCrashPolicy.DEFAULT_INCIDENT_GAP_MILLIS,
    ) = RenderCrashPolicy(
        maxFailures = maxFailures,
        windowMillis = windowMillis,
        incidentGapMillis = incidentGapMillis,
        now = { clock.now },
    )

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
        assertEquals(1, policy.recentFailureCount(), "stale failures should have been discarded")
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
    fun `recovery progress un-counts the fault so narrowing can finish`() {
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
    fun `settling refunds expire once per continuous burst`() {
        val clock = FakeClock()
        val policy = policy(clock, windowMillis = 100L, incidentGapMillis = 100L)

        assertTrue(policy.recordFailureAndShouldContain())
        assertTrue(policy.noteSettlingFault(), "the first queued fault gets settle room")
        clock.advance(100)
        assertTrue(policy.recordFailureAndShouldContain())
        assertTrue(policy.noteSettlingFault(), "the burst deadline is inclusive")
        clock.advance(1)
        assertTrue(policy.recordFailureAndShouldContain())

        assertFalse(policy.noteSettlingFault(), "settling must not refund one continuous burst forever")
        assertEquals(1, policy.recentFailureCount(), "the expired settling fault must stay counted")
    }

    @Test
    fun `settling after a quiet window receives a fresh bounded allowance`() {
        val clock = FakeClock()
        val policy = policy(clock, windowMillis = 100L, incidentGapMillis = 100L)

        assertTrue(policy.recordFailureAndShouldContain())
        assertTrue(policy.noteSettlingFault())
        clock.advance(101)
        assertTrue(policy.recordFailureAndShouldContain())

        assertTrue(policy.noteSettlingFault(), "a later incident must not inherit an expired settle deadline")
        assertEquals(0, policy.recentFailureCount())
    }

    @Test
    fun `visible recovery progress expires at the same incident deadline`() {
        val clock = FakeClock()
        val policy = policy(clock, windowMillis = 100L, incidentGapMillis = 100L)

        assertTrue(policy.recordFailureAndShouldContain())
        assertTrue(policy.noteRecoveryProgress())
        clock.advance(100)
        assertTrue(policy.recordFailureAndShouldContain())
        assertTrue(policy.noteRecoveryProgress(), "the incident deadline is inclusive")
        clock.advance(1)
        assertTrue(policy.recordFailureAndShouldContain())

        assertFalse(policy.noteRecoveryProgress(), "visible progress must not manufacture refunds forever")
        assertEquals(1, policy.recentFailureCount(), "expired progress must stay counted")
    }

    @Test
    fun `intermittent recovery reanchors before a later fast burst`() {
        val clock = FakeClock()
        val policy = policy(clock)

        repeat(3) {
            assertTrue(policy.recordFailureAndShouldContain())
            assertTrue(policy.noteRecoveryProgress())
            clock.advance(RenderCrashPolicy.DEFAULT_INCIDENT_GAP_MILLIS + 1)
        }

        assertTrue(policy.recordFailureAndShouldContain())
        assertTrue(
            policy.noteSettlingFault(),
            "a fresh recovery incident must not inherit the intermittent stream's expired allowance",
        )
    }

    @Test
    fun `a thread refunds its own recorded fault after another thread records`() {
        val clock = AtomicLong(0L)
        val policy = RenderCrashPolicy(windowMillis = 100L, now = clock::get)
        val firstRecorded = CountDownLatch(1)
        val secondRecorded = CountDownLatch(1)
        val firstRefunded = AtomicBoolean()
        val firstThread =
            Thread {
                policy.recordFailureAndShouldContain()
                firstRecorded.countDown()
                secondRecorded.await()
                firstRefunded.set(policy.noteRecoveryProgress())
            }
        val secondThread =
            Thread {
                firstRecorded.await()
                clock.set(10L)
                policy.recordFailureAndShouldContain()
                secondRecorded.countDown()
            }

        firstThread.start()
        secondThread.start()
        firstThread.join()
        secondThread.join()
        assertTrue(firstRefunded.get(), "the first thread should find its own pending fault")
        clock.set(101L)
        assertTrue(policy.recordFailureAndShouldContain())

        assertEquals(
            2,
            policy.recentFailureCount(),
            "the second thread's newer fault must remain counted after the first thread refunds",
        )
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
