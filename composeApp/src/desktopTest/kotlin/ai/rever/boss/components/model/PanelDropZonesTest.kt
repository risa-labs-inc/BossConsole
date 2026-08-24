package ai.rever.boss.components.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the leading inset a vertical tab bar takes off a panel's split drop zones.
 *
 * The inset exists because `updateDropTarget` tests tab-bar bounds BEFORE these zones: a left
 * zone left under the bar can never be hit, so a highlight drawn from it would advertise a drop
 * that means something else. These assert the two halves partition the panel rather than overlap.
 */
class PanelDropZonesTest {
    private val panel = Rect(left = 0f, top = 0f, right = 1000f, bottom = 800f)
    private val barWidth = 200f
    private val edge = 60f

    private fun zones(inset: Float) = PanelDropZones.fromBounds(panel, edgeSize = edge, leadingInset = inset)

    @Test
    fun `no inset leaves the left zone on the panel's edge`() {
        val z = zones(0f)
        assertEquals(0f, z.leftZone.left)
        assertEquals(edge, z.leftZone.right)
    }

    @Test
    fun `an inset moves the left zone to where the bar ends`() {
        val z = zones(barWidth)
        assertEquals(barWidth, z.leftZone.left)
        assertEquals(barWidth + edge, z.leftZone.right)
    }

    @Test
    fun `nothing under the bar is a drop zone`() {
        val z = zones(barWidth)
        val underTheBar = Offset(barWidth / 2, panel.height / 2)
        assertFalse(z.leftZone.contains(underTheBar))
        assertFalse(z.topZone.contains(underTheBar))
        assertFalse(z.bottomZone.contains(underTheBar))
        assertFalse(z.centerZone.contains(underTheBar))
    }

    @Test
    fun `the top and bottom zones start after the bar too`() {
        // Not just the left one: a full-width top band would reach back over the bar, and the
        // overlay drawn from it paints exactly what the band claims.
        val z = zones(barWidth)
        assertEquals(barWidth + edge, z.topZone.left)
        assertEquals(barWidth + edge, z.bottomZone.left)
        assertEquals(barWidth + edge, z.centerZone.left)
    }

    @Test
    fun `the trailing edge is untouched by the inset`() {
        val plain = zones(0f)
        val inset = zones(barWidth)
        assertEquals(plain.rightZone.right, inset.rightZone.right)
        assertEquals(panel.right, inset.rightZone.right)
    }

    @Test
    fun `panelBounds stays the whole panel`() {
        // It is the panel's identity for anything measuring it, not a droppable region - so it
        // must NOT shrink with the inset.
        assertEquals(panel, zones(barWidth).panelBounds)
    }

    @Test
    fun `just past the bar is a left split`() {
        val z = zones(barWidth)
        assertTrue(z.leftZone.contains(Offset(barWidth + 10f, panel.height / 2)))
    }

    @Test
    fun `a bar wider than the panel offers no zones rather than inverted ones`() {
        val z = zones(2000f)
        val anywhere = Offset(500f, 400f)
        assertFalse(z.leftZone.contains(anywhere))
        assertFalse(z.rightZone.contains(anywhere))
        assertFalse(z.centerZone.contains(anywhere))
    }

    @Test
    fun `the edge size shrinks with the content, not the panel`() {
        // effectiveEdgeSize is capped at a quarter of the droppable area. Measuring it against
        // the full panel would let a zone reach back over the bar on a narrow split.
        val narrow = Rect(left = 0f, top = 0f, right = 300f, bottom = 800f)
        val z = PanelDropZones.fromBounds(narrow, edgeSize = edge, leadingInset = 200f)
        // Content is 100 wide, so the edge is capped to 25, not the requested 60.
        assertEquals(200f, z.leftZone.left)
        assertEquals(225f, z.leftZone.right)
        assertEquals(narrow.right, z.rightZone.right)
    }

    /**
     * The regression the window-level tab bar would otherwise have shipped.
     *
     * `registerPanelDropZones` used to take the inset from the bar's WIDTH. One bar for the whole
     * window is registered against every panel and covers none of them, so every panel would have
     * lost 200px of its left edge to a bar sitting outside the split tree - and with it the
     * left-split-by-drag gesture on all of them at once.
     */
    @Test
    fun `a bar outside the panel takes nothing off it`() {
        val drag = TabDraggableComponent()
        // The window bar: to the LEFT of the panel, ending where the panel begins.
        drag.registerTabBarBounds(
            panelId = "p1",
            bounds = Rect(left = -200f, top = 0f, right = 0f, bottom = 800f),
            vertical = true,
        )
        drag.registerPanelDropZones("p1", panel)

        val z = drag.panelDropZones.getValue("p1")
        assertEquals(0f, z.leftZone.left)
        assertTrue(z.leftZone.contains(Offset(10f, panel.height / 2)))
    }

    @Test
    fun `a bar inside the panel still pushes its left zone past itself`() {
        val drag = TabDraggableComponent()
        drag.registerTabBarBounds(
            panelId = "p1",
            bounds = Rect(left = 0f, top = 0f, right = barWidth, bottom = 800f),
            vertical = true,
        )
        drag.registerPanelDropZones("p1", panel)

        val z = drag.panelDropZones.getValue("p1")
        assertEquals(barWidth, z.leftZone.left)
        assertFalse(z.leftZone.contains(Offset(10f, panel.height / 2)))
    }

    @Test
    fun `a top strip takes nothing off the sides whatever it overlaps`() {
        val drag = TabDraggableComponent()
        drag.registerTabBarBounds(
            panelId = "p1",
            bounds = Rect(left = 0f, top = 0f, right = 1000f, bottom = 36f),
            vertical = false,
        )
        drag.registerPanelDropZones("p1", panel)

        assertEquals(
            0f,
            drag.panelDropZones
                .getValue("p1")
                .leftZone.left,
        )
    }

    /**
     * The Favorites shelf is a drop target, and it is tested before the tab bars.
     *
     * It sits above the tab list rather than inside it, so today it overlaps nothing - but the
     * precedence is what makes that a property of the drop logic rather than of a layout that is
     * free to change.
     */
    @Test
    fun `the favorites shelf claims a drop over it`() {
        val drag = TabDraggableComponent()
        val shelf = Rect(left = 0f, top = 0f, right = 200f, bottom = 120f)
        drag.registerFavoritesBounds(shelf)
        drag.registerTabBarBounds("p1", Rect(0f, 120f, 200f, 900f), vertical = true)

        assertEquals(shelf, drag.favoritesBounds)
    }

    @Test
    fun `clearing the shelf stops it claiming anything`() {
        // The shelf is not drawn on the collapsed rail or in the top strip. A rectangle left
        // behind from an earlier layout would claim an area now showing tabs - and it is tested
        // before them, so it would win.
        val drag = TabDraggableComponent()
        drag.registerFavoritesBounds(Rect(0f, 0f, 200f, 120f))
        drag.registerFavoritesBounds(null)

        assertNull(drag.favoritesBounds)
    }

    @Test
    fun `clearing all bounds clears the shelf too`() {
        val drag = TabDraggableComponent()
        drag.registerFavoritesBounds(Rect(0f, 0f, 200f, 120f))
        drag.clearBounds()

        assertNull(drag.favoritesBounds)
    }
}
