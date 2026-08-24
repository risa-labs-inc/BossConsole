package ai.rever.boss.components.window_panel

import ai.rever.boss.plugin.api.TabRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins which half a new pane takes.
 *
 * Every split used to put the new pane second - right, or below - and callers said only which way
 * the divider ran. Two of the four map targets mean the opposite, so the side is now carried by
 * [SplitDirection]. Getting [SplitDirection.placeBefore] backwards is invisible to the type
 * checker and produces a split that works perfectly, on the wrong side.
 */
class SplitDirectionTest {
    @Test
    fun `left and up put the new pane first`() {
        assertTrue(SplitDirection.LEFT.placeBefore)
        assertTrue(SplitDirection.UP.placeBefore)
    }

    @Test
    fun `right and down keep the behaviour every caller had before`() {
        assertFalse(SplitDirection.RIGHT.placeBefore, "Split Right must still append")
        assertFalse(SplitDirection.DOWN.placeBefore, "Split Down must still append")
    }

    @Test
    fun `each direction runs the divider the way its name implies`() {
        assertEquals(SplitOrientation.VERTICAL, SplitDirection.LEFT.orientation)
        assertEquals(SplitOrientation.VERTICAL, SplitDirection.RIGHT.orientation)
        assertEquals(SplitOrientation.HORIZONTAL, SplitDirection.UP.orientation)
        assertEquals(SplitOrientation.HORIZONTAL, SplitDirection.DOWN.orientation)
    }

    @Test
    fun `the two vertical directions are opposite, and so are the two horizontal ones`() {
        // The pairing is the point: a direction and its opposite share an orientation and differ
        // only in placeBefore. Anything else means one of the four is drawn or placed wrong.
        SplitOrientation.entries.forEach { orientation ->
            val pair = SplitDirection.entries.filter { it.orientation == orientation }
            assertEquals(2, pair.size, "$orientation should have exactly two directions")
            assertEquals(setOf(true, false), pair.map { it.placeBefore }.toSet())
        }
    }

    @Test
    fun `every direction is worded for a person`() {
        assertEquals("Above", SplitDirection.UP.displayName, "'Up' is a keystroke, not a place")
        assertEquals("Below", SplitDirection.DOWN.displayName)
        assertEquals(
            SplitDirection.entries.size,
            SplitDirection.entries
                .map { it.displayName }
                .toSet()
                .size,
            "two directions with the same label make the picker's tooltips ambiguous",
        )
    }
}

/**
 * Pins [SplitViewState.splitPanel]'s `placeBefore` against the tree it builds.
 *
 * [SplitDirectionTest] pins what each direction MEANS; this pins that the tree honours it.
 * `getAllPanels()` returns panes in visual order, so the new pane's position in that list is
 * exactly the question - and the original keeping its tabs either way is the other half, because
 * swapping the wrong two nodes would move the tabs instead of the pane.
 */
class SplitPlacementTest {
    private fun state(): SplitViewState = SplitViewState(TabRegistry(), windowId = "w1")

    @Test
    fun `by default the new pane goes second, as every caller before this expected`() {
        val s = state()
        val created = s.splitPanel("main", SplitOrientation.VERTICAL)

        assertEquals(listOf("main", created), s.getAllPanels().map { it.id })
    }

    @Test
    fun `placeBefore puts the new pane first and moves the original over`() {
        val s = state()
        val created = s.splitPanel("main", SplitOrientation.VERTICAL, placeBefore = true)

        assertEquals(listOf(created, "main"), s.getAllPanels().map { it.id })
    }

    @Test
    fun `it works the same way for a horizontal split`() {
        val s = state()
        val created = s.splitPanel("main", SplitOrientation.HORIZONTAL, placeBefore = true)

        assertEquals(listOf(created, "main"), s.getAllPanels().map { it.id })
    }

    @Test
    fun `splitting a nested pane leaves its siblings where they were`() {
        // The recursion carries placeBefore down every branch, so the flag has to apply at the
        // pane it names and nowhere else. A version that swapped at each level would reverse the
        // panes it merely walked past.
        val s = state()
        val second = s.splitPanel("main", SplitOrientation.VERTICAL)
        val third = s.splitPanel(second, SplitOrientation.HORIZONTAL, placeBefore = true)

        assertEquals(listOf("main", third, second), s.getAllPanels().map { it.id })
    }
}
