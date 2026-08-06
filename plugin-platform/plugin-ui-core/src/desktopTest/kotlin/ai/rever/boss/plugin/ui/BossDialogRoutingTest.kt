package ai.rever.boss.plugin.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
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
        val r = anchorRectInDp(Offset(120f, 40f), IntSize(500, 28), density = 1f)
        assertEquals(IntRect(120, 40, 620, 68), r)
    }

    @Test
    fun `at 2x position and size both halve`() {
        val r = anchorRectInDp(Offset(240f, 80f), IntSize(1000, 56), density = 2f)
        assertEquals(IntRect(120, 40, 620, 68), r)
    }

    @Test
    fun `a 150 percent display is handled, not just integral scales`() {
        val r = anchorRectInDp(Offset(180f, 60f), IntSize(750, 42), density = 1.5f)
        assertEquals(IntRect(120, 40, 620, 68), r)
    }

    @Test
    fun `the anchor keeps its width, which is what the content is sized to`() {
        // The list stretched edge to edge because a zero-width anchor left the content inheriting
        // the overlay window's width. Width has to survive the conversion.
        val r = anchorRectInDp(Offset(240f, 80f), IntSize(1000, 0), density = 2f)
        assertEquals(500, r.width)
    }

    @Test
    fun `a zero-height anchor still reports its position, so the popup opens there`() {
        // The anchor Box wraps a zero-size probe, so zero height is the NORMAL case, not an error.
        val r = anchorRectInDp(Offset(240f, 80f), IntSize(1000, 0), density = 2f)
        assertEquals(40, r.top)
        assertEquals(40, r.bottom)
    }

    @Test
    fun `a nonsensical density yields an empty rect rather than a divide by zero`() {
        assertEquals(IntRect.Zero, anchorRectInDp(Offset(10f, 10f), IntSize(10, 10), density = 0f))
    }

    @Test
    fun `an unplaced layout reporting NaN does not become a NaN window position`() {
        assertEquals(IntRect.Zero, anchorRectInDp(Offset(Float.NaN, Float.NaN), IntSize(10, 10), density = 2f))
    }
}
