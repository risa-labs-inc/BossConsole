package ai.rever.boss.components.workspaces

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.Circle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins that a Space menu row says TWO things, and that neither answer can eat the other.
 *
 * The menu drew a three-state running dot and nothing else, so the one fact a reader can act on -
 * this Space holds work that is not on disk - was the fact the menu did not carry. The temptation
 * when adding it is to give the existing dot a fourth value, which is why the orthogonality case
 * below is the one to keep: a Space on screen AND unsaved must report both, since collapsing them
 * would silently drop whichever the `when` happened to test second.
 */
class SpaceRowMarksTest {
    private companion object {
        const val CURRENT = "space-current"
        const val ELSEWHERE = "space-elsewhere"
        const val IDLE = "space-idle"
    }

    private fun marks(
        id: String,
        current: String? = CURRENT,
        running: Set<String> = setOf(CURRENT, ELSEWHERE),
        unsaved: Set<String> = emptySet(),
    ) = spaceRowMarks(
        workspaceId = id,
        currentWorkspaceId = current,
        runningWorkspaceIds = running,
        unsavedWorkspaceIds = unsaved,
    )

    @Test
    fun `the three running states are the Space here, one running elsewhere, and one not running`() {
        assertEquals(SpaceRunState.Current, marks(CURRENT).run)
        assertEquals(SpaceRunState.Running, marks(ELSEWHERE).run)
        assertEquals(SpaceRunState.Idle, marks(IDLE).run)
    }

    @Test
    fun `the Space on screen is Current even though it is also in the running set`() {
        // `running` holds the current Space too, so an order-independent reading of the two
        // conditions would call the row on screen merely "running" and lose the strong mark.
        assertEquals(SpaceRunState.Current, marks(CURRENT, running = setOf(CURRENT)).run)
    }

    @Test
    fun `a Space can be on screen and unsaved, and reports both`() {
        val both = marks(CURRENT, unsaved = setOf(CURRENT))
        assertEquals(SpaceRunState.Current, both.run, "the running mark must survive the unsaved one")
        assertTrue(both.unsaved, "and the unsaved mark must survive the running one")
    }

    @Test
    fun `a Space running in another window can be unsaved, and one not running cannot`() {
        assertTrue(marks(ELSEWHERE, unsaved = setOf(ELSEWHERE)).unsaved)
        // Not a rule about idleness - just what the set says. An unopened Space has no live
        // layout to differ from its file, so nothing ever puts it in the set.
        assertFalse(marks(IDLE).unsaved)
    }

    @Test
    fun `unsaved is read per window, so another window's edit does not mark the row here`() {
        // The set handed in is THIS window's. The rule must not consult anything else, or a Space
        // edited in the other window would be marked in both.
        assertFalse(marks(ELSEWHERE, unsaved = emptySet()).unsaved)
    }

    @Test
    fun `Last Session always reads unsaved, by the same rule the vertical bar uses`() {
        // A slot, not a document: whatever is in it has never been saved anywhere. Going through
        // `spaceIsUnsaved` is what makes the menu and the bar agree; a plain `in` check here would
        // leave the row quiet while the bar beside it is lit.
        assertTrue(marks(LAST_SESSION_ID).unsaved)
    }

    @Test
    fun `the dot is filled for this window, outlined for another's, and absent when idle`() {
        assertEquals(Icons.Filled.Circle, SpaceRunState.Current.dotIcon())
        assertEquals(Icons.Outlined.Circle, SpaceRunState.Running.dotIcon())
        assertNull(SpaceRunState.Idle.dotIcon(), "an unopened Space gets no dot at all")
    }
}
