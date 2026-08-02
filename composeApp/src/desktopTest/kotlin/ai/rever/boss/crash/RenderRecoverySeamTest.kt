package ai.rever.boss.crash

import ai.rever.boss.plugin.sandbox.ui.PluginCrashRegistry
import ai.rever.boss.plugin.sandbox.ui.PluginRenderRecovery
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
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

    @AfterTest
    fun tearDown() {
        PluginRenderRecovery.reset()
        plugins.forEach { PluginCrashRegistry.clearCrash(it) }
    }

    /** One frame of a scene that throws every repaint, wired as containRenderFault does. */
    private fun frame(
        policy: RenderCrashPolicy,
        clockMillis: Long,
    ): WindowExceptionRoute {
        val route = decideWindowExceptionRoute(error, attributedPluginId = null, policy = policy)
        if (route == WindowExceptionRoute.Contain) {
            val outcome = PluginRenderRecovery.onUnattributedRenderException(error, now = clockMillis)
            if (outcome is PluginRenderRecovery.Outcome.Rebuilt ||
                outcome is PluginRenderRecovery.Outcome.Quarantined
            ) {
                policy.noteRecoveryProgress()
            }
        }
        return route
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
            if (frame(policy, now) == WindowExceptionRoute.Escalate) {
                escalatedAt = frameNumber
                break
            }
            now += 16
        }

        assertTrue(
            escalatedAt != null,
            "a permanently broken scene never escalated — the app would spin forever, " +
                "which is the failure RenderCrashPolicy exists to prevent",
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
            val route = decideWindowExceptionRoute(error, null, policy)
            if (route == WindowExceptionRoute.Escalate) break
            val outcome = PluginRenderRecovery.onUnattributedRenderException(error, now = now)
            if (outcome is PluginRenderRecovery.Outcome.Quarantined) {
                quarantined += outcome.plugins
                policy.noteRecoveryProgress()
            } else if (outcome is PluginRenderRecovery.Outcome.Rebuilt) {
                policy.noteRecoveryProgress()
            }
            now += 16
        }

        assertTrue(
            quarantined.containsAll(plugins),
            "narrowing escalated before trying every mounted plugin: tried $quarantined",
        )
    }
}
