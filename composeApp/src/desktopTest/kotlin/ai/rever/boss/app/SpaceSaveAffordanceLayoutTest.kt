package ai.rever.boss.app

import ai.rever.boss.components.buttons.BossActionButton
import ai.rever.boss.components.icons.SpaceIcon
import ai.rever.boss.window.TabBarVerticalWidthRange
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins that the Space row's save affordance is actually ON SCREEN at the narrowest the vertical
 * tab bar goes, and absent when there is nothing to save.
 *
 * The bar is user-resizable down to [TabBarVerticalWidthRange].start, 120dp, and this row holds a
 * Space button whose label runs to 130dp plus a 24dp save button. A `Row` does not wrap, and what
 * it does with a measure it cannot satisfy is hand its LAST child zero width - `Rect(0, 0, 0, 0)`,
 * which is inside every bounds check that will ever be written. That measured shape is why this
 * asserts the button's SIZE and not only its position, and why the Space button is the weighted
 * child: a `Row` measures its unweighted children first, so the save button's 24dp is taken out
 * before the label gets anything.
 */
class SpaceSaveAffordanceLayoutTest {
    @get:Rule
    val rule = createComposeRule()

    private val narrowest = TabBarVerticalWidthRange.start.dp

    private fun mountRow(
        width: Dp,
        unsaved: Boolean,
    ) {
        rule.setContent {
            // clipToBounds mirrors the bar, which is what turns an overflow into a vanished
            // button rather than one drawn outside its column.
            Column(modifier = Modifier.width(width).clipToBounds().testTag(ROW_TAG)) {
                SpaceRow(unsaved = unsaved, onSave = {}) {
                    // The real button's shape: the Space glyph, a project-length name and the
                    // 130dp label cap the bar applies to it.
                    BossActionButton(
                        leftIcon = SpaceIcon,
                        text = "Claude Code (BossConsole)",
                        maxTextWidth = 130.dp,
                        compact = true,
                        contextMenuItems = null,
                        onClick = {},
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `the save button is on screen at the bar's narrowest width`() {
        mountRow(narrowest, unsaved = true)

        val row = rule.onNodeWithTag(ROW_TAG).fetchSemanticsNode().boundsInRoot
        val save = rule.onNodeWithTag(SPACE_SAVE_TAG).fetchSemanticsNode().boundsInRoot

        assertTrue(save.width > 0f && save.height > 0f, "a zero-sized button is an absent one: $save")
        assertEquals(SAVE_SIZE_PX, save.width.toInt(), "the save target keeps its full width")
        assertEquals(SAVE_SIZE_PX, save.height.toInt(), "and its full height")
        assertTrue(save.left >= row.left && save.right <= row.right, "inside the bar: $save in $row")
    }

    @Test
    fun `the save button is not drawn when there is nothing to save`() {
        mountRow(WIDE, unsaved = false)

        rule.onAllNodesWithTag(SPACE_SAVE_TAG).assertCountEquals(0)
        rule.onAllNodesWithTag(SPACE_UNSAVED_DOT_TAG).assertCountEquals(0)
    }

    /**
     * The state has its own mark. Without it the affordance was a floppy glyph labelled "Save this
     * space", which is what a save button looks like whether or not anything has changed - so the
     * one thing it exists to say was carried only by its presence.
     */
    @Test
    fun `the unsaved state is marked, and the description leads with it`() {
        mountRow(WIDE, unsaved = true)

        rule.onNodeWithTag(SPACE_UNSAVED_DOT_TAG).assertExists()
        assertTrue(
            SPACE_SAVE_DESCRIPTION.startsWith("Unsaved"),
            "the description has to say the STATE, not just name the action: $SPACE_SAVE_DESCRIPTION",
        )
        rule.onNodeWithContentDescription(SPACE_SAVE_DESCRIPTION).assertExists()
    }

    @Test
    fun `the dot and the save button both fit at the bar's narrowest width`() {
        // The dot is another unweighted child, so it comes out of the same width the label gets.
        mountRow(narrowest, unsaved = true)

        val row = rule.onNodeWithTag(ROW_TAG).fetchSemanticsNode().boundsInRoot
        val dot = rule.onNodeWithTag(SPACE_UNSAVED_DOT_TAG).fetchSemanticsNode().boundsInRoot
        val save = rule.onNodeWithTag(SPACE_SAVE_TAG).fetchSemanticsNode().boundsInRoot

        assertTrue(dot.width > 0f && dot.height > 0f, "a zero-sized dot is an absent one: $dot")
        assertTrue(dot.left >= row.left && dot.right <= row.right, "inside the bar: $dot in $row")
        assertTrue(save.left >= dot.right, "the mark reads before the action it offers")
        assertEquals(SAVE_SIZE_PX, save.width.toInt(), "and the save target still keeps its size")
    }

    @Test
    fun `an unsaved Space is one this window has marked, and a window with no Space is quiet`() {
        assertTrue(spaceIsUnsaved("workspace-1", setOf("workspace-1")))
        assertFalse(spaceIsUnsaved("workspace-1", setOf("workspace-2")))
        assertFalse(spaceIsUnsaved("workspace-1", emptySet()))
        // No Space loaded reads as saved: that state lasts about two seconds before the layout
        // watcher writes it out, and a control that appears and vanishes on every new window is
        // worse than one that waits.
        assertFalse(spaceIsUnsaved(null, setOf("workspace-1")))
    }

    private companion object {
        const val ROW_TAG = "space-row-test"

        /** Comfortably wide, so the absence test is about the flag and not about the width. */
        val WIDE = 200.dp

        /** The affordance's 24dp at the 1x density `createComposeRule` mounts with. */
        const val SAVE_SIZE_PX = 24
    }
}
