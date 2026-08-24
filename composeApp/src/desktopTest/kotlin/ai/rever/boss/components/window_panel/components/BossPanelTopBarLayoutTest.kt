package ai.rever.boss.components.window_panel.components

import ai.rever.boss.components.plugin.PanelMenuActions
import ai.rever.boss.components.plugin.PluginBuildInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * Where the header's trailing controls actually land.
 *
 * A unit test of this file's model would have passed throughout the bug this pins: every child was
 * present, in the right order, with the right callbacks. The defect was purely in measurement -
 * two weighted children sharing free space 1:1, so the half the title did not use was laid out
 * past the buttons - and only a laid-out screen can see it.
 */
class BossPanelTopBarLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    private companion object {
        val BAR_WIDTH = 420.dp

        /**
         * The bar's trailing padding (2.dp) plus the button's content padding (2.dp), and 2.dp of
         * slack so the assertion does not turn on sub-pixel rounding at fractional densities.
         */
        val EDGE_TOLERANCE = 6.dp

        /**
         * Narrow enough that the title group is offered less than the tag's own width. The panel
         * floor is `2% of parent, min 20.dp` (BossResizablePanel), and the header's fixed cost is
         * already the leading spacer plus both buttons, so this band is reachable in the product.
         */
        val STARVED_WIDTH = 60.dp

        const val SHORT_TITLE = "A"
        const val LONG_TITLE = "A panel title long enough that it has to be ellipsized in here"

        /** The tag's own semantics, which carry the version rather than the four-character pill. */
        const val TAG_DESC = "Local build, not the store version: 1.0.3-debug"
    }

    private var title by mutableStateOf(SHORT_TITLE)
    private var barWidth by mutableStateOf(BAR_WIDTH)

    private fun localBuild() =
        PluginBuildInfo(
            pluginId = "p",
            displayName = "Probe",
            version = "1.0.3",
            signedBytes = false,
            storeSourced = false,
            reloadStamp = null,
        )

    private fun show(buildInfo: PluginBuildInfo? = null) {
        compose.setContent {
            Box(modifier = Modifier.width(barWidth)) {
                BossPanelTopBar(
                    title = title,
                    isHovered = true,
                    actions = PanelMenuActions(buildInfo = buildInfo, installStoreVersion = {}),
                    onMinimize = {},
                )
            }
        }
        compose.waitForIdle()
    }

    /** A header control, found by the content description its icon carries. */
    private fun control(label: String) = compose.onNodeWithContentDescription(label)

    /** Laid-out bounds of a header control, in the root's coordinates. */
    private fun bounds(label: String) = control(label).getUnclippedBoundsInRoot()

    /** Laid-out bounds of a text node - the title, which carries text rather than a description. */
    private fun textBounds(text: String) = compose.onNodeWithText(text).getUnclippedBoundsInRoot()

    @Test
    fun `Minimize sits at the right edge of the bar`() {
        show()

        val gap = BAR_WIDTH - bounds("Minimize").right
        assertTrue(
            gap <= EDGE_TOLERANCE,
            "Minimize should be flush right; it is $gap short of the edge (bar $BAR_WIDTH)",
        )
    }

    @Test
    fun `the More kebab sits directly left of Minimize, not adrift in the middle`() {
        show()

        val more = bounds("More")
        val minimize = bounds("Minimize")

        assertTrue(
            more.right <= minimize.left && (minimize.left - more.right) <= EDGE_TOLERANCE,
            "More should abut Minimize; More ends at ${more.right}, Minimize starts at ${minimize.left}",
        )
    }

    @Test
    fun `the trailing controls do not drift with the length of the title`() {
        // The sharpest form of the bug: the leftover was half the title's *unused* width, so the
        // controls crept further from the edge the shorter the title. A single-title assertion with
        // a generous tolerance could pass while that was still true.
        show()
        val withShortTitle = bounds("Minimize").right

        compose.runOnIdle { title = LONG_TITLE }
        compose.waitForIdle()
        val withLongTitle = bounds("Minimize").right

        assertTrue(
            abs((withShortTitle - withLongTitle).value) < 1f,
            "the right edge moved with the title: $withShortTitle vs $withLongTitle",
        )
    }

    @Test
    fun `a starved header keeps the tag on one line`() {
        // Grouping the tag with the title reversed the measurement priority: the tag used to be
        // measured against nearly the whole bar and now gets what the controls leave. Winning that
        // trade for the controls is deliberate, but it means the tag must degrade by clipping. Left
        // to wrap, four characters break onto a second line inside a hard height(28.dp) row.
        show(buildInfo = localBuild())
        val roomy = bounds(TAG_DESC).height

        compose.runOnIdle { barWidth = STARVED_WIDTH }
        compose.waitForIdle()
        val starved = bounds(TAG_DESC).height

        assertTrue(
            abs((starved - roomy).value) < 1f,
            "the tag grew taller when starved, so it wrapped: $roomy roomy vs $starved starved",
        )
    }

    @Test
    fun `a starved header keeps the controls at the right edge and the tag present`() {
        // The other half of the trade: the title gives way first, the tag survives, and the
        // controls stay put. This is the "narrow sidebar" case the change is riskiest in.
        show(buildInfo = localBuild())
        compose.runOnIdle {
            title = LONG_TITLE
            barWidth = STARVED_WIDTH
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription(TAG_DESC).assertExists()
        val gap = STARVED_WIDTH - bounds("Minimize").right
        assertTrue(
            gap <= EDGE_TOLERANCE,
            "Minimize should stay flush right in a starved header; it is $gap short of the edge",
        )
    }

    @Test
    fun `the title gives way before the tag does`() {
        // The PR claims the title ellipsizes ahead of the tag. Nothing asserted it until now.
        show(buildInfo = localBuild())
        compose.runOnIdle { title = LONG_TITLE }
        compose.waitForIdle()
        val roomyTitle = textBounds(LONG_TITLE).width
        val roomyTag = bounds(TAG_DESC).width

        compose.runOnIdle { barWidth = 200.dp }
        compose.waitForIdle()

        assertTrue(
            textBounds(LONG_TITLE).width < roomyTitle,
            "the title should shrink as the bar narrows",
        )
        assertTrue(
            abs((bounds(TAG_DESC).width - roomyTag).value) < 1f,
            "the tag should keep its width while the title still has room to give",
        )
    }

    @Test
    fun `the build tag does not push the controls off the right edge`() {
        // The tag is laid out inside the title group, so it must consume the group's space and not
        // the trailing controls'.
        show(
            buildInfo =
                PluginBuildInfo(
                    pluginId = "p",
                    displayName = "Probe",
                    version = "1.0.3",
                    signedBytes = false,
                    storeSourced = false,
                    reloadStamp = 1_754_890_231_447L,
                ),
        )

        val gap = BAR_WIDTH - bounds("Minimize").right
        assertTrue(
            gap <= EDGE_TOLERANCE,
            "Minimize should stay flush right with a tag present; it is $gap short of the edge",
        )
    }
}
