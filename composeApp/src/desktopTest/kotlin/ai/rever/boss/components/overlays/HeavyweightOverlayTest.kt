package ai.rever.boss.components.overlays

import ai.rever.boss.plugin.browser.shouldRetainSurface
import androidx.compose.ui.unit.IntOffset
import com.teamdev.jxbrowser.engine.RenderingMode
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
    fun `a caller-supplied anchor is used as-is, because it is already window-local`() {
        // BossTabButton and BossActionButton compute an anchor from the widget's own position so
        // the menu opens UNDER the widget. Subtracting the window origin from it - as the cursor
        // branch must - would throw it off-screen on any window not at x=0.
        val anchor = IntOffset(120, 48)
        assertEquals(anchor, contentOffsetFor(cursorX = 900, cursorY = 700, anchor = anchor, windowX = 1200, windowY = 300))
        assertEquals(anchor, contentOffsetFor(cursorX = null, cursorY = null, anchor = anchor, windowX = 1200, windowY = 300))
    }

    @Test
    fun `with no anchor the cursor is converted from screen space to window-local`() {
        // A right-click context menu passes IntOffset.Zero: there the pointer IS the location.
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
    fun `no anchor and no cursor yields no offset instead of a negative one`() {
        // The regression this pins: subtracting the window origin from a zero anchor put the menu
        // at -windowX,-windowY, i.e. off-screen for any window not at the origin.
        assertEquals(
            IntOffset.Zero,
            contentOffsetFor(cursorX = null, cursorY = null, anchor = IntOffset.Zero, windowX = 1200, windowY = 300),
        )
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
    }
}
