package ai.rever.boss.components.window_panel

import ai.rever.boss.components.window_panel.components.main_window_panels.splitTargetAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the four targets of the unsplit map to the regions they are drawn in.
 *
 * The shapes and the hit test are derived separately - the trapezoids are Path corners, the test
 * is a comparison of two offsets from the middle - so nothing but this stops them disagreeing.
 * A drift there is silent and infuriating: the region under the pointer lights up and the click
 * splits the other way.
 *
 * The frame is 3:2, so these use a 3:2 pixel size. That matters: normalising first is what makes
 * the boundaries the RECTANGLE's diagonals, and a version that forgot to would pass every test
 * written on a square.
 */
class SplitCreatorMapTest {
    private val width = 150f
    private val height = 100f

    private fun at(
        x: Float,
        y: Float,
    ) = splitTargetAt(x, y, width, height)

    @Test
    fun `each edge belongs to the direction it points at`() {
        assertEquals(SplitDirection.LEFT, at(2f, height / 2f), "left edge, vertically centred")
        assertEquals(SplitDirection.RIGHT, at(width - 2f, height / 2f), "right edge")
        assertEquals(SplitDirection.UP, at(width / 2f, 2f), "top edge")
        assertEquals(SplitDirection.DOWN, at(width / 2f, height - 2f), "bottom edge")
    }

    @Test
    fun `the centre is the pane you already have, and is not a split`() {
        assertNull(at(width / 2f, height / 2f), "dead centre")
        // Just inside the centre rectangle, which is 44% of each side.
        assertNull(at(width * 0.5f + width * 0.2f, height / 2f), "inside the centre, near its edge")
        assertNull(at(width / 2f, height * 0.5f - height * 0.2f))
    }

    @Test
    fun `just outside the centre is already a split`() {
        // 44% centre means it ends at 0.72 of the width. A hair past that is the trapezoid.
        assertEquals(SplitDirection.RIGHT, at(width * 0.73f, height / 2f))
        assertEquals(SplitDirection.DOWN, at(width / 2f, height * 0.73f))
    }

    @Test
    fun `the corners split along the rectangle's own diagonals`() {
        // A corner is equidistant from two edges only after normalising. On a 3:2 frame the raw
        // pixel offsets at (0,0) are 75 and 50, so a hit test that skipped normalising would call
        // this one LEFT rather than sitting on the boundary the drawing puts there.
        assertEquals(SplitDirection.UP, at(width * 0.3f, height * 0.1f), "above the diagonal")
        assertEquals(SplitDirection.LEFT, at(width * 0.1f, height * 0.3f), "below the diagonal")
    }

    @Test
    fun `an unmeasured frame has no targets`() {
        // One frame before the Canvas reports its size. Answering LEFT for every point of a
        // zero-width box would make the first click after opening a window split the wrong way.
        assertNull(splitTargetAt(0f, 0f, 0f, 0f))
        assertNull(splitTargetAt(10f, 10f, 0f, 100f))
    }
}
