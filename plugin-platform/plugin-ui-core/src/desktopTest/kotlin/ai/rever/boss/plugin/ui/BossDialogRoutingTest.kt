package ai.rever.boss.plugin.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins which modals escape into a heavyweight window.
 *
 * Composing a `Window` needs a display, so [shouldRouteHeavyweight] is the only part of the
 * decision a unit test can reach - and it is the part where every regression would land, because
 * each of its three inputs suppresses the heavyweight path for a different reason.
 */
class BossDialogRoutingTest {
    @Test
    fun `a browser-hosting window with a registered renderer routes heavyweight`() {
        assertTrue(
            shouldRouteHeavyweight(
                useHeavyweightOverlays = true,
                hasRenderer = true,
                hostNeedsHeavyweight = true,
            ),
        )
    }

    @Test
    fun `OFF_SCREEN installs keep the lightweight path, so they cannot regress`() {
        assertFalse(
            shouldRouteHeavyweight(
                useHeavyweightOverlays = false,
                hasRenderer = true,
                hostNeedsHeavyweight = true,
            ),
        )
    }

    @Test
    fun `with nothing injected there is nowhere to route to, so it falls back`() {
        assertFalse(
            shouldRouteHeavyweight(
                useHeavyweightOverlays = true,
                hasRenderer = false,
                hostNeedsHeavyweight = true,
            ),
        )
    }

    @Test
    fun `a secondary window stays lightweight even under HARDWARE`() {
        // Settings and the first-run setup window host no browser surface. An always-on-top window
        // sized to the MAIN window would cover the wrong window and then keep floating over it,
        // because a heavyweight modal deliberately survives focus moving to another window of the
        // same application.
        assertFalse(
            shouldRouteHeavyweight(
                useHeavyweightOverlays = true,
                hasRenderer = true,
                hostNeedsHeavyweight = false,
            ),
        )
    }

    @Test
    fun `the missing-renderer diagnostic fires once, not once per frame`() {
        val messages = mutableListOf<String>()
        val previous = BossOverlayHost.diagnostics
        try {
            BossOverlayHost.diagnostics = { messages += it }
            repeat(5) { BossOverlayHost.reportMissingModalRenderer() }
        } finally {
            BossOverlayHost.diagnostics = previous
        }
        // A dialog recomposes freely; a per-composition warning would bury the log it is meant to
        // surface in.
        assertTrue(messages.size <= 1, "expected at most one report, got ${messages.size}")
    }
}
