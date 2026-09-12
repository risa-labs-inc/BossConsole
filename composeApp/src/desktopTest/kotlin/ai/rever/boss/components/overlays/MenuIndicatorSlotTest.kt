package ai.rever.boss.components.overlays

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Pins that a menu row can carry TWO passive marks at once, through the real drawn menu.
 *
 * The secondary trailing slot used to render only when it also had a click handler, so a row that
 * already marked one state could not mark a second one - which is why the Space menu had a
 * running dot and no way to say "unsaved". The two marks are separate slots rather than one
 * four-valued glyph, and the thing to protect is that BOTH survive on the same row: a regression
 * here does not throw, it quietly draws one dot and drops the other.
 *
 * Node-level rather than pixel-level on purpose - `SpaceSaveAffordanceLayoutTest` measures the
 * bar's geometry, while what can silently vanish here is a whole slot.
 */
class MenuIndicatorSlotTest {
    @get:Rule
    val rule = createComposeRule()

    private companion object {
        const val TARGET_TAG = "menu-indicator-target"
        const val RUNNING_DESCRIPTION = "Action"
        const val UNSAVED_DESCRIPTION = "Unsaved changes"
    }

    /**
     * The drawn menu, not the OS one: a native menu is a window with no Compose tree, so these
     * assertions would fail on macOS and pass on CI for a reason unconnected to what they check.
     * The native path declines any menu carrying a trailing mark anyway - see
     * `isNativeRepresentable` - which is what makes forcing the drawn path here faithful rather
     * than a convenience.
     */
    @Before
    fun useDrawnMenu() {
        NativeContextMenuTestOverride.enabled = false
    }

    @After
    fun tearDown() {
        NativeContextMenuTestOverride.enabled = null
    }

    private fun openMenu(items: List<ContextMenuItem>) {
        rule.setContent {
            Box(
                modifier =
                    Modifier
                        .size(400.dp)
                        .testTag(TARGET_TAG)
                        .contextMenu(items = items),
            )
        }
        rule.onNodeWithTag(TARGET_TAG).performMouseInput { rightClick() }
        rule.waitForIdle()
    }

    /**
     * How many marks with this description are drawn.
     *
     * Unmerged, so this counts ICONS. Each row is `clickable` and therefore merges its
     * descendants' semantics, so the merged tree answers with rows instead - which happens to give
     * the same numbers here and would stop doing so the moment a row carried two of one mark.
     */
    private fun countOf(description: String) =
        rule
            .onAllNodesWithContentDescription(description, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size

    @Test
    fun `a mark with no action still renders, and says what it means`() {
        // Failing-before: the slot required `onSecondaryTrailingClick` to render at all, so this
        // row drew nothing and the description was a hardcoded "Delete".
        openMenu(
            listOf(
                ContextMenuItem(
                    text = "Code Review",
                    secondaryTrailingIcon = Icons.Filled.Circle,
                    secondaryTrailingDescription = UNSAVED_DESCRIPTION,
                ),
            ),
        )

        assertEquals(1, countOf(UNSAVED_DESCRIPTION), "a passive secondary mark must be on screen")
    }

    @Test
    fun `both marks appear on one row, so neither state hides the other`() {
        openMenu(
            listOf(
                ContextMenuItem(
                    text = "Code Review",
                    trailingIcon = Icons.Filled.Circle,
                    secondaryTrailingIcon = Icons.Filled.Circle,
                    secondaryTrailingDescription = UNSAVED_DESCRIPTION,
                ),
            ),
        )

        assertEquals(1, countOf(RUNNING_DESCRIPTION), "the running dot must survive the unsaved one")
        assertEquals(1, countOf(UNSAVED_DESCRIPTION), "and the unsaved dot must survive the running one")
    }

    @Test
    fun `the first mark keeps its column on a row that has no second mark`() {
        // Rows are flush right, so a row with one mark used to slide that mark into the other
        // mark's position: "running elsewhere" was drawn where "unsaved" is drawn, one hollow and
        // one filled, in two columns that did not line up. Every arithmetic check passed while
        // the screenshot was wrong, which is why this measures the two positions.
        openMenu(
            listOf(
                ContextMenuItem(
                    text = "Code Review",
                    trailingIcon = Icons.Filled.Circle,
                    secondaryTrailingIcon = Icons.Filled.Circle,
                    secondaryTrailingDescription = UNSAVED_DESCRIPTION,
                ),
                ContextMenuItem(text = "Browser Only", trailingIcon = Icons.Outlined.Circle),
            ),
        )

        // useUnmergedTree, and that is not a detail: each row is `clickable`, so it MERGES its
        // descendants' semantics and a merged query returns the ROW - whose left edge is the same
        // on every row whatever its marks do. Measured against the mutation that removes the
        // reservation; the merged version of this query passed it happily.
        val dots =
            rule
                .onAllNodesWithContentDescription(RUNNING_DESCRIPTION, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .map { it.boundsInRoot.left }
        assertEquals(2, dots.size, "both rows carry a running dot")
        assertEquals(
            dots[0],
            dots[1],
            "the running dot must sit in the same column whether or not the row is also unsaved",
        )
    }

    @Test
    fun `only the rows that hold unsaved work are marked`() {
        openMenu(
            listOf(
                ContextMenuItem(
                    text = "Code Review",
                    trailingIcon = Icons.Filled.Circle,
                    secondaryTrailingIcon = Icons.Filled.Circle,
                    secondaryTrailingDescription = UNSAVED_DESCRIPTION,
                ),
                ContextMenuItem(
                    text = "Gemini",
                    trailingIcon = Icons.Outlined.Circle,
                ),
                ContextMenuItem(text = "Claude Code"),
            ),
        )

        assertEquals(1, countOf(UNSAVED_DESCRIPTION), "exactly the one edited row carries the mark")
        assertEquals(2, countOf(RUNNING_DESCRIPTION), "both running rows keep their own dot")
    }
}
