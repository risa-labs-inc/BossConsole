package ai.rever.boss.components.overlays

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Pins [cornerPosition], the only part of the toast overlay reachable without a display.
 *
 * Placement is the part of this that has actually gone wrong before: an overlay measured against
 * the wrong origin, or in the wrong units, compiles and passes every other gate while sitting
 * visibly off-target on screen.
 */
class HeavyweightCornerTest {
    private val parent = intArrayOf(100, 50, 1000, 800)
    private val size = DpSize(432.dp, 200.dp)

    @Test
    fun `top end sits at the parent's right edge, not the screen's`() {
        // 100 + (1000 - 432) = 668, i.e. offset from the PARENT origin. Using the screen origin
        // instead is the classic version of this bug and lands the toast on the wrong monitor.
        assertEquals(668 to 50, cornerPosition(parent, size, Alignment.TopEnd))
    }

    @Test
    fun `top start sits at the parent origin`() {
        assertEquals(100 to 50, cornerPosition(parent, size, Alignment.TopStart))
    }

    @Test
    fun `bottom end offsets by both slacks`() {
        assertEquals(668 to 650, cornerPosition(parent, size, Alignment.BottomEnd))
    }

    @Test
    fun `center centres on both axes`() {
        assertEquals(384 to 350, cornerPosition(parent, size, Alignment.Center))
    }

    @Test
    fun `content larger than the parent overhangs bottom-right rather than escaping top-left`() {
        // A negative slack would put the toast above and left of the window, where it is off screen
        // and its dismiss button is unreachable. Floor at the parent origin instead.
        val huge = DpSize(2000.dp, 2000.dp)
        assertEquals(100 to 50, cornerPosition(parent, huge, Alignment.TopEnd))
        assertEquals(100 to 50, cornerPosition(parent, huge, Alignment.BottomEnd))
    }

    @Test
    fun `unmeasured parent falls back to the origin`() {
        assertEquals(0 to 0, cornerPosition(null, size, Alignment.TopEnd))
    }

    // --- insetBounds: anchoring to a sub-region of the window ---

    @Test
    fun `a zero inset is the parent itself`() {
        // Identity, not merely equal contents: every existing caller passes zero, and returning a
        // fresh array would make the placement effect's key change on every recomposition - one
        // native setLocation per frame, for nothing.
        assertSame(parent, insetBounds(parent, DpSize.Zero))
    }

    @Test
    fun `an inset shrinks the far edges and leaves the origin alone`() {
        val region = insetBounds(parent, DpSize(48.dp, 24.dp))
        assertEquals(listOf(100, 50, 952, 776), region?.toList())
    }

    @Test
    fun `bottom end moves in by exactly the inset while top start does not move at all`() {
        // The whole point of expressing this as a smaller rectangle: a caller inset from the right
        // and the bottom has not moved its top-left corner, so a near-corner anchor must not move.
        val region = insetBounds(parent, DpSize(48.dp, 24.dp))

        assertEquals(620 to 626, cornerPosition(region, size, Alignment.BottomEnd))
        assertEquals(
            cornerPosition(parent, size, Alignment.TopStart),
            cornerPosition(region, size, Alignment.TopStart),
        )
    }

    @Test
    fun `an inset wider than the parent floors at zero rather than going negative`() {
        // A negative extent reads as slack in cornerPosition, which would place the overlay outside
        // the parent entirely - the failure mode the floor in cornerPosition exists to prevent,
        // reintroduced one layer up.
        val region = insetBounds(parent, DpSize(4000.dp, 4000.dp))

        assertEquals(listOf(100, 50, 0, 0), region?.toList())
        assertEquals(100 to 50, cornerPosition(region, size, Alignment.BottomEnd))
    }

    @Test
    fun `an unmeasured parent stays unmeasured through an inset`() {
        assertNull(insetBounds(null, DpSize(48.dp, 24.dp)))
    }
}
