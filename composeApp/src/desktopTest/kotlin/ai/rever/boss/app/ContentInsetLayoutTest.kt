package ai.rever.boss.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Pins [reportContentInset] against the shape `BossAppScaffold` actually builds.
 *
 * This is the arithmetic that decides where the quick actions land on the heavyweight path, and it
 * is the classic silently-wrong kind: measuring against the parent instead of the root, or
 * forgetting the density divide, both compile and both pass every other gate while putting the
 * overlay somewhere visibly wrong on screen. Nothing short of measuring a real layout tree can tell
 * the difference, so the tree here mirrors the scaffold - a `Column` of a content `Row` (left
 * sidebar, main area, right sidebar) over a bottom bar - rather than asserting on the formula.
 */
class ContentInsetLayoutTest {
    @get:Rule
    val rule = createComposeRule()

    /**
     * Build a scaffold-shaped tree with the given chrome present or absent, and return what the
     * main content area reports as its inset.
     */
    private fun insetFor(
        rightSidebar: Boolean,
        bottomBar: Boolean,
        topBar: Boolean = true,
        leftSidebar: Boolean = true,
    ): DpSize {
        var reported = DpSize(-1.dp, -1.dp)
        rule.setContent {
            // A density of exactly 1 is what makes the px-to-Dp conversion untestable: every wrong
            // answer equals the right one. Verified by mutation - dropping the divide passes at 1x
            // and fails here. The scale is arbitrary, only its being non-unit matters.
            CompositionLocalProvider(LocalDensity provides Density(TEST_DENSITY)) {
                ScaffoldShape(rightSidebar, bottomBar, topBar, leftSidebar) { reported = it }
            }
        }
        rule.waitForIdle()
        return reported
    }

    /** The scaffold's chrome layout: a top bar, a content row between two sidebars, a bottom bar. */
    @Composable
    private fun ScaffoldShape(
        rightSidebar: Boolean,
        bottomBar: Boolean,
        topBar: Boolean,
        leftSidebar: Boolean,
        onInset: (DpSize) -> Unit,
    ) {
        val density = LocalDensity.current.density
        Column(modifier = Modifier.size(WINDOW_WIDTH, WINDOW_HEIGHT)) {
            if (topBar) Bar(modifier = Modifier.fillMaxWidth().height(TOP_BAR))
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (leftSidebar) Bar(modifier = Modifier.fillMaxHeight().width(LEFT_SIDEBAR))
                Box(
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .weight(1f)
                            .reportContentInset(density, onInset),
                )
                if (rightSidebar) Bar(modifier = Modifier.fillMaxHeight().width(RIGHT_SIDEBAR))
            }
            if (bottomBar) Bar(modifier = Modifier.fillMaxWidth().height(BOTTOM_BAR))
        }
    }

    @Composable
    private fun Bar(modifier: Modifier) = Box(modifier = modifier)

    @Test
    fun `the inset is the right sidebar's width and the bottom bar's height`() {
        // The Windows focus-mode default: the top bar is cleared while both sidebars and - here,
        // to prove both axes at once - a bottom bar stay up. Anchoring to the window instead of to
        // the content area is what would put the cluster on top of them.
        assertEquals(DpSize(RIGHT_SIDEBAR, BOTTOM_BAR), insetFor(rightSidebar = true, bottomBar = true))
    }

    @Test
    fun `full focus mode leaves no inset at all`() {
        // Every edge cleared, so the content area IS the window and the cluster belongs in its
        // corner. A non-zero answer here would hold it off the corner by a phantom margin.
        assertEquals(DpSize.Zero, insetFor(rightSidebar = false, bottomBar = false))
    }

    @Test
    fun `the left sidebar and top bar do not count`() {
        // Only the END and BOTTOM edges displace a bottom-right anchor. Chrome on the NEAR edges
        // moves the content area's origin, not its far corner, so adding or removing it must not
        // change the answer at all - counting it would drag the cluster inward by the width of a
        // sidebar nowhere near it.
        //
        // Asserted as invariance across the near-chrome variants, not as the same two numbers test
        // 1 already asserts. Re-asserting those would make this test unable to fail on its own:
        // same inputs, same expectations, no independent claim.
        val withNearChrome = insetFor(rightSidebar = true, bottomBar = true, topBar = true, leftSidebar = true)
        val withoutNearChrome = insetFor(rightSidebar = true, bottomBar = true, topBar = false, leftSidebar = false)

        assertEquals(withNearChrome, withoutNearChrome)
    }

    private companion object {
        const val TEST_DENSITY = 2f
        val WINDOW_WIDTH = 1200.dp
        val WINDOW_HEIGHT = 800.dp
        val TOP_BAR = 40.dp
        val LEFT_SIDEBAR = 48.dp
        val RIGHT_SIDEBAR = 56.dp
        val BOTTOM_BAR = 24.dp
    }
}
