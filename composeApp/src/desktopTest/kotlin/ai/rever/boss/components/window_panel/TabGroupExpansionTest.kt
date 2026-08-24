package ai.rever.boss.components.window_panel

import ai.rever.boss.components.window_panel.components.main_window_panels.TabGroupExpansion
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which pane in the window bar is showing all its tabs.
 *
 * The sticky-hover rule is the part worth pinning: hovering a header chooses the open pane rather
 * than expanding while hovered, because the obvious version collapses the group the instant the
 * pointer moves down onto the rows it just revealed.
 */
class TabGroupExpansionTest {
    private fun expansion() = TabGroupExpansion()

    @Test
    fun `nothing is open to begin with`() {
        val e = expansion()
        assertFalse(e.isExpanded("p1"))
        assertFalse(e.isExpanded("p2"))
    }

    @Test
    fun `hovering a header opens that pane and only that pane`() {
        val e = expansion()
        e.hover("p1")
        assertTrue(e.isExpanded("p1"))
        assertFalse(e.isExpanded("p2"))
    }

    @Test
    fun `the hover choice survives the pointer moving off the header`() {
        // This is the whole point: the revealed rows are underneath where the pointer was, so an
        // expand-while-hovered rule would close the group on the way to them. Nothing but another
        // header or leaving the bar changes the choice.
        val e = expansion()
        e.hover("p1")
        assertTrue(e.isExpanded("p1"))
        // No "unhover" call exists to make; the state is unchanged by anything else happening.
        assertTrue(e.isExpanded("p1"))
    }

    @Test
    fun `hovering another header moves the opening`() {
        val e = expansion()
        e.hover("p1")
        e.hover("p2")
        assertFalse(e.isExpanded("p1"))
        assertTrue(e.isExpanded("p2"))
    }

    @Test
    fun `leaving the bar closes a hovered pane`() {
        val e = expansion()
        e.hover("p1")
        e.barExited()
        assertFalse(e.isExpanded("p1"))
    }

    @Test
    fun `leaving the bar does not close a pinned pane`() {
        // Pinning is a click. Moving the mouse away must not undo it.
        val e = expansion()
        e.togglePinned("p1")
        e.barExited()
        assertTrue(e.isExpanded("p1"))
    }

    @Test
    fun `pinning twice closes it again`() {
        val e = expansion()
        e.togglePinned("p1")
        e.togglePinned("p1")
        assertFalse(e.isExpanded("p1"))
    }

    @Test
    fun `unpinning the pane the pointer is on actually closes it`() {
        // Pinned and hovered are separate. Without clearing the hover too, clicking to close a
        // pane that is also the hovered one would leave it open and read as a dead click.
        val e = expansion()
        e.hover("p1")
        e.togglePinned("p1")
        e.togglePinned("p1")
        assertFalse(e.isExpanded("p1"))
    }

    @Test
    fun `several panes can be pinned at once`() {
        val e = expansion()
        e.togglePinned("p1")
        e.togglePinned("p2")
        assertTrue(e.isExpanded("p1"))
        assertTrue(e.isExpanded("p2"))
    }

    @Test
    fun `a pinned pane stays open while another is hovered`() {
        val e = expansion()
        e.togglePinned("p1")
        e.hover("p2")
        assertTrue(e.isExpanded("p1"))
        assertTrue(e.isExpanded("p2"))
    }
}
