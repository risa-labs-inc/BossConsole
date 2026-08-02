package ai.rever.boss.crash

import ai.rever.boss.plugin.sandbox.ui.PluginRenderRecovery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The user-facing half of render containment.
 *
 * This decision lived inline in the window exception handler and regressed twice
 * there, both times invisibly, because nothing could assert it. Every test below
 * corresponds to one of those regressions.
 */
class RenderRecoveryToastTest {
    private val quarantined = PluginRenderRecovery.Outcome.Quarantined(setOf("plugin.a"))
    private val rebuilt = PluginRenderRecovery.Outcome.Rebuilt(setOf("plugin.a"))

    @Test
    fun `every outcome says something`() {
        // Regression one: the toast was gated on "recovery made progress", so
        // Unexplained and NotPluginRelated — the outcomes that leave the window
        // broken, and so the ones the user most needs — said nothing at all.
        val outcomes =
            listOf(
                quarantined,
                rebuilt,
                PluginRenderRecovery.Outcome.Unexplained,
                PluginRenderRecovery.Outcome.NotPluginRelated,
            )

        outcomes.forEach { outcome ->
            val toaster = RenderRecoveryToaster()
            assertNotNull(toaster.toastFor(outcome, now = 0L), "$outcome told the user nothing")
        }
    }

    @Test
    fun `a repeated verdict is suppressed while its toast is still up`() {
        val toaster = RenderRecoveryToaster(durationMs = 8_000L)

        assertNotNull(toaster.toastFor(rebuilt, now = 0L))
        assertNull(toaster.toastFor(rebuilt, now = 16L), "the same verdict must not re-toast next frame")
        assertNull(toaster.toastFor(rebuilt, now = 7_999L))
    }

    @Test
    fun `the same verdict hours later is not swallowed`() {
        // Regression two, half one: the gate was a process-lifetime "last message",
        // so a Rebuilt at 09:00 silenced an unrelated Rebuilt at 14:00 — a silently
        // recovered window, which is the failure this path exists to announce.
        val toaster = RenderRecoveryToaster(durationMs = 8_000L)
        assertNotNull(toaster.toastFor(rebuilt, now = 0L))

        val hoursLater = 5 * 60 * 60 * 1000L
        assertNotNull(
            toaster.toastFor(rebuilt, now = hoursLater),
            "an unrelated recurrence hours later must still be announced",
        )
    }

    @Test
    fun `a storm of alternating verdicts is bounded`() {
        // Regression two, half two: suppressing only *identical consecutive*
        // messages bounded nothing, because a narrowing cycle emits a different
        // verdict on almost every frame. At 16ms that is ~60 toasts a second, each
        // one cancelling the previous job on the main dispatcher.
        val toaster = RenderRecoveryToaster(durationMs = 8_000L)
        val cycle =
            listOf(
                rebuilt,
                PluginRenderRecovery.Outcome.Quarantined(setOf("plugin.a")),
                PluginRenderRecovery.Outcome.Quarantined(setOf("plugin.b")),
                PluginRenderRecovery.Outcome.Quarantined(setOf("plugin.c")),
                PluginRenderRecovery.Outcome.Unexplained,
            )

        var shown = 0
        var now = 0L
        // Five seconds of faults every 16ms — ~312 frames, all inside one toast window.
        repeat(312) { frame ->
            if (toaster.toastFor(cycle[frame % cycle.size], now) != null) shown++
            now += 16
        }

        assertEquals(
            cycle.size,
            shown,
            "each distinct verdict should speak once per window, not once per frame",
        )
    }

    @Test
    fun `the quarantine message names the plugin and how to recover it`() {
        // This is the wording the generic "Plugin X crashed" toast was overwriting.
        val message = RenderRecoveryToaster.messageFor(quarantined)

        assertTrue(message.contains("plugin.a"), "the user cannot act without knowing which plugin")
        assertTrue(message.contains("Restart"), "and not without being told how to bring it back")
    }
}
