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
            contentOffsetFor(cursorX = 1300, cursorY = 500, anchor = parentRelativeAnchor, windowX = 1200, windowY = 300),
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
            contentOffsetFor(cursorX = null, cursorY = null, anchor = IntOffset(120, 48), windowX = 1200, windowY = 300),
        )
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
