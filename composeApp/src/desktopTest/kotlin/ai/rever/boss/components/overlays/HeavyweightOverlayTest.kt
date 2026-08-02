package ai.rever.boss.components.overlays

import ai.rever.boss.plugin.browser.parseTopInsetDp
import ai.rever.boss.plugin.browser.shouldRetainSurface
import androidx.compose.ui.unit.IntOffset
import com.teamdev.jxbrowser.engine.RenderingMode
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the pure decisions behind the HARDWARE_ACCELERATED overlay path.
 *
 * These are the pieces with no other safety net: they are consumed inside composables and their
 * failure mode is visual (a menu in the wrong place, a tab that paints blank), which no other test
 * in this module would catch.
 */
class HeavyweightOverlayTest {
    // --- contentOffsetFor: the coordinate-space trap ---

    @Test
    fun `the cursor wins over the caller's anchor, and is converted from screen space`() {
        // The anchor must NOT be preferred. Callers compute it for the lightweight Popup branch,
        // which positions relative to the parent LAYOUT NODE: BossTabButton and BossActionButton
        // pass positionInParent(), and the Modifier.contextMenu path passes the pointer's
        // element-local position. Treating any of those as window-local displaces the menu by the
        // distance from the widget's parent to the window origin. Only the cursor is in a space
        // this function can convert, so only the cursor is trusted.
        val parentRelativeAnchor = IntOffset(120, 48)
        assertEquals(
            IntOffset(100, 200),
            contentOffsetFor(
                cursorX = 1300,
                cursorY = 500,
                anchor = parentRelativeAnchor,
                windowX = 1200,
                windowY = 300,
            ),
        )
    }

    @Test
    fun `a zero anchor is no different from any other - the cursor still wins`() {
        assertEquals(
            IntOffset(100, 200),
            contentOffsetFor(cursorX = 1300, cursorY = 500, anchor = IntOffset.Zero, windowX = 1200, windowY = 300),
        )
    }

    @Test
    fun `an unresolvable window origin is treated as zero rather than skewing the position`() {
        assertEquals(
            IntOffset(1300, 500),
            contentOffsetFor(cursorX = 1300, cursorY = 500, anchor = IntOffset.Zero, windowX = null, windowY = null),
        )
    }

    @Test
    fun `with no cursor the anchor is used unconverted, as an approximate fallback`() {
        // Keyboard-invoked menu: there is no pointer to read. The anchor is in the wrong space,
        // but it is small and near the widget, so it beats not appearing. Critically it must NOT
        // have the window origin subtracted - that put the menu at -windowX,-windowY, off-screen
        // for any window not at the origin, which is the regression this pins.
        assertEquals(
            IntOffset(120, 48),
            contentOffsetFor(
                cursorX = null,
                cursorY = null,
                anchor = IntOffset(120, 48),
                windowX = 1200,
                windowY = 300,
            ),
        )
        assertEquals(
            IntOffset.Zero,
            contentOffsetFor(cursorX = null, cursorY = null, anchor = IntOffset.Zero, windowX = 1200, windowY = 300),
        )
    }

    // --- browser surface top inset ---

    @Test
    fun `top inset clamps out values that would silently misplace the surface`() {
        // This offset moves a heavyweight native surface inside its slot with no error reporting,
        // so a stray negative shifts the page up under the toolbar and a huge one pushes it off
        // the bottom - both look like a rendering bug, not a mistyped setting.
        assertEquals(24, parseTopInsetDp("24"))
        assertEquals(0, parseTopInsetDp("0"))
        assertEquals(0, parseTopInsetDp("-200"))
        assertEquals(200, parseTopInsetDp("9999"))
        // Unset or unparseable means 0, the correct default on the measured machine.
        for (raw in listOf(null, "", "   ", "24px", "abc")) {
            assertEquals(0, parseTopInsetDp(raw), "expected 0 for '$raw'")
        }
    }

    // --- surface retention ---

    @Test
    fun `surfaces are retained only under HARDWARE_ACCELERATED`() {
        // OFF_SCREEN must keep the original close-on-hide lifecycle: it is the macOS/Linux default
        // and retention there would change behaviour on platforms this work never measured.
        assertTrue(shouldRetainSurface(RenderingMode.HARDWARE_ACCELERATED))
        assertFalse(shouldRetainSurface(RenderingMode.OFF_SCREEN))
    }

    // --- overlay routing ---

    /**
     * [OverlayConfig] is a process-wide singleton, so a test that injects into it would leak into
     * every later test in this module. Reset after each one rather than relying on ordering.
     */
    @AfterTest
    fun resetOverlayConfig() {
        OverlayConfig.useHeavyweightPopups = false
        OverlayConfig.heavyweightPopup = null
        OverlayConfig.heavyweightModal = null
        OverlayConfig.heavyweightTooltip = null
        OverlayConfig.hideHeavyweightTooltip = null
        OverlayConfig.openHeavyweightPopups = 0
    }

    @Test
    fun `overlay renderers default to null so callers fall back to Compose popups`() {
        // useHeavyweightPopups can be true while the renderers are null: any entry point that does
        // not run main.kt's wiring (a test host, a tool) reaches exactly that state. The null
        // checks in ContextMenu / OverlayModal are what stand between that and no menus at all,
        // so the defaults they rely on are pinned here.
        assertFalse(OverlayConfig.useHeavyweightPopups)
        assertNull(OverlayConfig.heavyweightPopup)
        assertNull(OverlayConfig.heavyweightModal)
        assertNull(OverlayConfig.heavyweightTooltip)
        assertNull(OverlayConfig.hideHeavyweightTooltip)
        assertEquals(0, OverlayConfig.openHeavyweightPopups)
    }

    // --- modal dismissal ---

    @Test
    fun `a modal does not dismiss while one of its own popups holds focus`() {
        // The reported dead end: NewTabDialog's folder dropdown is a ContextMenu, which under
        // HARDWARE is its own always-on-top window. Opening it fires the modal's windowLostFocus,
        // and dismissing there closed the entire dialog the dropdown belongs to.
        assertFalse(shouldDismissOnFocusLoss(openHeavyweightPopups = 1, oppositeWindow = null))
        assertFalse(shouldDismissOnFocusLoss(openHeavyweightPopups = 2, oppositeWindow = null))
    }

    @Test
    fun `a modal dismisses when focus leaves the application entirely`() {
        // A null opposite window means focus went somewhere AWT does not own - another app. That
        // is the case the focus-loss dismissal actually exists for.
        assertTrue(shouldDismissOnFocusLoss(openHeavyweightPopups = 0, oppositeWindow = null))
    }
}
