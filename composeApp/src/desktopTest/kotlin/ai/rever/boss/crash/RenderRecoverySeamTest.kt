package ai.rever.boss.crash

import ai.rever.boss.plugin.sandbox.ui.PluginCrashRegistry
import ai.rever.boss.plugin.sandbox.ui.PluginRenderRecovery
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drives the real [PluginRenderRecovery] against the real [RenderCrashPolicy],
 * which neither of their own test classes does.
 *
 * That gap hid a regression. Fixing "the budget expires before narrowing
 * converges" by clearing the burst on any progress over-corrected into "the
 * budget never expires": the narrowing loop manufactures progress indefinitely —
 * rebuild, suspect each mounted plugin in turn, end `Unexplained`, which resets
 * the incident and re-mounts the released panels so the next fault rebuilds
 * again. With a full reset every cycle the count never reached the limit, so a
 * genuinely corrupt scene span forever instead of escalating. Both halves passed
 * their own unit tests.
 */
class RenderRecoverySeamTest {
    private val plugins = listOf("plugin.a", "plugin.b", "plugin.c")
    private val error = IllegalArgumentException("""Key "coll-dup" was already used.""")

    @BeforeTest
    fun setUp() {
        PluginRenderRecovery.reset()
        plugins.forEach { PluginRenderRecovery.registerMounted(it) }
    }

    // No manual clearCrash here: reset() releases the held suspect itself now.
    // Needing those calls was the sign that it did not.
    @AfterTest
    fun tearDown() = PluginRenderRecovery.reset()

    @Test
    fun `an outcome recovery could not act on is left counted`() {
        // The pairing that production uses, asserted directly: Unexplained and
        // NotPluginRelated must keep accumulating or escalation never arrives.
        val policy = RenderCrashPolicy(now = { 0L })
        policy.recordFailureAndShouldContain()

        val effect = noteRecoveryOutcome(policy, PluginRenderRecovery.Outcome.Unexplained)

        assertFalse(effect.visibleProgress, "Unexplained is not progress")
        assertFalse(effect.faultRefunded, "Unexplained must stay counted")
        assertEquals(1, policy.recentFailureCount(), "an unproductive fault must stay counted")
    }

    @Test
    fun `settling refunds queued work without requesting another repaint`() {
        val policy = RenderCrashPolicy(now = { 0L })
        policy.recordFailureAndShouldContain()

        val effect =
            noteRecoveryOutcome(
                policy,
                PluginRenderRecovery.Outcome.Settling(setOf("plugin.c")),
            )

        assertFalse(effect.visibleProgress, "settling changed no registry or generation state")
        assertTrue(effect.faultRefunded, "early queued work should still be refunded")
        assertEquals(0, policy.recentFailureCount())
    }

    private data class FrameResult(
        val route: WindowExceptionRoute,
        val outcome: PluginRenderRecovery.Outcome? = null,
        val effect: RecoveryOutcomeEffect? = null,
    )

    /**
     * One frame of a scene that throws every repaint.
     *
     * Calls the same [noteRecoveryOutcome] the handler does rather than
     * re-implementing the pairing. The earlier version duplicated the
     * `Rebuilt || Quarantined` condition, which meant deleting the call from
     * `containRenderFault` left these tests green — verified, and the reason this
     * now goes through production code.
     */
    private fun frame(
        policy: RenderCrashPolicy,
        clockMillis: Long,
    ): FrameResult {
        val route = decideWindowExceptionRoute(error, attributedPluginId = null, policy = policy)
        if (route == WindowExceptionRoute.Contain) {
            val outcome = PluginRenderRecovery.onUnattributedRenderException(error, now = clockMillis)
            return FrameResult(route, outcome, noteRecoveryOutcome(policy, outcome))
        }
        return FrameResult(route)
    }

    @Test
    fun `a scene that throws every frame eventually escalates`() {
        var now = 0L
        val policy = RenderCrashPolicy(now = { now })

        // 200 frames at 16ms is a little over three seconds — comfortably inside
        // the ten-second window, so nothing ages out and escalation must come
        // from the budget rather than from the clock.
        var escalatedAt: Int? = null
        for (frameNumber in 1..200) {
            if (frame(policy, now).route == WindowExceptionRoute.Escalate) {
                escalatedAt = frameNumber
                break
            }
            now += 16
        }

        assertTrue(
            escalatedAt != null,
            "a permanently broken scene never escalated - the app would spin forever, " +
                "which is the failure RenderCrashPolicy exists to prevent",
        )
    }

    @Test
    fun `a large mounted set cannot refund settling forever`() {
        for (index in plugins.size until 32) {
            PluginRenderRecovery.registerMounted("plugin.$index")
        }
        var now = 0L
        val policy = RenderCrashPolicy(now = { now })
        var escalatedAt: Long? = null
        val expectedSuspects = PluginRenderRecovery.mountedPlugins().toSet()
        val triedSuspects = mutableSetOf<String>()

        while (now <= 48_000) {
            val result = frame(policy, now)
            if (result.route == WindowExceptionRoute.Escalate) {
                escalatedAt = now
                break
            }
            val outcome = result.outcome
            if (outcome is PluginRenderRecovery.Outcome.Quarantined) triedSuspects += outcome.plugins
            now += 16
        }

        val escalationTime =
            assertNotNull(
                escalatedAt,
                "32 mounted plugins kept refunding a permanent fault for 48 seconds",
            )
        assertTrue(
            triedSuspects.containsAll(expectedSuspects),
            "the bounded allowance expired before one complete narrowing pass: tried ${triedSuspects.size}/32",
        )
        val escalationCeiling =
            RenderCrashPolicy.DEFAULT_WINDOW_MILLIS +
                (RenderCrashPolicy.DEFAULT_MAX_FAILURES + 1) * 16L
        assertTrue(
            escalationTime <= escalationCeiling,
            "a continuous corrupt burst should escalate by $escalationCeiling ms, got $escalatedAt",
        )
    }

    @Test
    fun `narrowing is given enough room to reach every mounted plugin first`() {
        var now = 0L
        val policy = RenderCrashPolicy(now = { now })

        // Escalating before the loop has tried each suspect would kill the app
        // without ever finding the culprit — the bug the progress allowance fixes.
        val quarantined = mutableSetOf<String>()
        for (frameNumber in 1..200) {
            val result = frame(policy, now)
            if (result.route == WindowExceptionRoute.Escalate) break
            val outcome = result.outcome
            if (outcome is PluginRenderRecovery.Outcome.Quarantined) quarantined += outcome.plugins
            now += 16
        }

        assertTrue(
            quarantined.containsAll(plugins),
            "narrowing escalated before trying every mounted plugin: tried $quarantined",
        )
    }

    @Test
    fun `in-flight quarantine faults cannot consume the crash budget`() {
        var now = 1_000L
        val policy = RenderCrashPolicy(now = { now })

        assertEquals(WindowExceptionRoute.Contain, frame(policy, now).route) // rebuild
        now += 16
        assertEquals(WindowExceptionRoute.Contain, frame(policy, now).route) // quarantine c

        repeat(12) {
            now += 16
            assertTrue(
                frame(policy, now).route == WindowExceptionRoute.Contain,
                "the circuit breaker fired before the quarantined subtree could leave Compose",
            )
            assertEquals(0, policy.recentFailureCount(), "bounded settling faults must be refunded")
            assertTrue(PluginCrashRegistry.hasCrashed("plugin.c"), "the same suspect must remain held")
        }
    }

    @Test
    fun `a permanent fault still escalates after every settle deadline`() {
        var now = 1_000L
        val policy = RenderCrashPolicy(now = { now })
        var escalatedAt: Int? = null
        val escalationCeiling =
            RenderCrashPolicy.DEFAULT_WINDOW_MILLIS +
                (RenderCrashPolicy.DEFAULT_MAX_FAILURES + 1) * 16L
        val maximumFrames =
            (escalationCeiling / 16L + RenderCrashPolicy.DEFAULT_MAX_FAILURES + 2).toInt()

        for (frameNumber in 1..maximumFrames) {
            if (frame(policy, now).route == WindowExceptionRoute.Escalate) {
                escalatedAt = frameNumber
                break
            }
            now += 16
        }

        assertTrue(escalatedAt != null, "settling must not turn containment into an infinite loop")
        assertTrue(
            now - 1_000 <= escalationCeiling,
            "a corrupt scene should fail honestly by its burst deadline; " +
                "got frame $escalatedAt at ${now - 1_000} ms",
        )
    }

    @Test
    fun `slow visible progress cannot refund a corrupt scene forever`() {
        val cadenceMillis = 1_000L
        var now = 0L
        val policy = RenderCrashPolicy(now = { now })
        var escalatedAt: Long? = null

        while (now <= 30_000) {
            if (frame(policy, now).route == WindowExceptionRoute.Escalate) {
                escalatedAt = now
                break
            }
            now += cadenceMillis
        }

        val escalationTime = assertNotNull(escalatedAt, "slow recovery progress hid a permanent fault")
        val ceiling =
            RenderCrashPolicy.DEFAULT_WINDOW_MILLIS +
                (RenderCrashPolicy.DEFAULT_MAX_FAILURES + 1) * cadenceMillis
        assertTrue(
            escalationTime <= ceiling,
            "a slow corrupt incident should escalate by $ceiling ms, got $escalationTime",
        )
    }

    @Test
    fun `policy incident gap matches the recovery machine grace`() {
        assertEquals(
            PluginRenderRecovery.REBUILD_GRACE_MILLIS,
            RenderCrashPolicy.DEFAULT_INCIDENT_GAP_MILLIS,
        )
    }
}
