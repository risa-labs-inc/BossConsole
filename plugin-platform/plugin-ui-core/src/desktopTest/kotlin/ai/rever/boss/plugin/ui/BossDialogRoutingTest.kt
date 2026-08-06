package ai.rever.boss.plugin.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntRect
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

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

/**
 * The arming rule for a freshly-opened modal, which is where the user-visible regression lives.
 *
 * A heavyweight modal is a new window placed over the cursor, so the click that opened it is still in
 * flight. The first version of this guard let a 200ms timer arm it unconditionally: a modifier-click
 * held longer than that - plus the window's own first-frame latency - expired the timer while the
 * button was still down, and the release then chose whichever option sat under the pointer. That is
 * the reported bug, reintroduced intermittently, which is worse than not guarding at all.
 */
class ModalInputArmingRuleTest {
    @Test
    fun `a held button vetoes arming, even once the timer has elapsed`() {
        assertFalse(shouldArmModalInput(pointerDown = true))
    }

    @Test
    fun `an idle pointer arms it`() {
        assertTrue(shouldArmModalInput(pointerDown = false))
    }

    @Test
    fun `the veto does not depend on which signal is asking`() {
        // Both the pointer handler and the timer call this with the same question, so there is no
        // path on which "the timer fired" outranks "a button is down".
        for (askedByTimer in listOf(true, false)) {
            assertFalse(shouldArmModalInput(pointerDown = true), "armed while pressed (timer=$askedByTimer)")
        }
    }
}

/**
 * The px-to-dp conversion for a popup's anchor.
 *
 * Compose measures layout in PIXELS; the host places overlay content in AWT LOGICAL UNITS. Shipping
 * the raw pixel rect put the URL-bar suggestion list at roughly double its intended position on a 2x
 * display - and looked perfectly correct on a 1x one, which is why this is tested at several scale
 * factors rather than eyeballed on one screen.
 */
class AnchorRectConversionTest {
    @Test
    fun `at 1x the numbers are unchanged, which is why the bug hid`() {
        val r = anchorRectInDp(Rect(120f, 40f, 620f, 68f), density = 1f)
        assertEquals(IntRect(120, 40, 620, 68), r)
    }

    @Test
    fun `at 2x every edge halves`() {
        val r = anchorRectInDp(Rect(240f, 80f, 1240f, 136f), density = 2f)
        assertEquals(IntRect(120, 40, 620, 68), r)
    }

    @Test
    fun `a 150 percent display is handled, not just integral scales`() {
        val r = anchorRectInDp(Rect(180f, 60f, 930f, 102f), density = 1.5f)
        assertEquals(IntRect(120, 40, 620, 68), r)
    }

    @Test
    fun `a nonsensical density yields an empty rect rather than a divide by zero`() {
        // An anchor at the origin degrades to cursor-like placement; NaN coordinates would propagate
        // into a window position and put the popup somewhere unrecoverable.
        assertEquals(IntRect.Zero, anchorRectInDp(Rect(10f, 10f, 20f, 20f), density = 0f))
    }
}
