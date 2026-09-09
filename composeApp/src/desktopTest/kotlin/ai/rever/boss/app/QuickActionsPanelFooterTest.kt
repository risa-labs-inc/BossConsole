package ai.rever.boss.app

import ai.rever.boss.components.buttons.ToolLauncherButton
import ai.rever.boss.components.buttons.ToolboxButton
import ai.rever.boss.components.plugin.PanelIds
import ai.rever.boss.components.window_panel.PanelColumn
import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.right
import ai.rever.boss.plugin.api.SidebarItem
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the two claims [PanelFooterHostActions] makes that the floating cluster could not: that it
 * takes a row out of the panel's column rather than drawing over it, and that the row is the
 * PANEL's width rather than the window's.
 *
 * The second is not cosmetic bookkeeping - it was the first version of this placement. A band
 * across the whole content area also stops the cluster covering an open panel, and it looks like
 * what it is: a strip of dead chrome the width of the screen holding four icons.
 */
class QuickActionsPanelFooterTest {
    @get:Rule
    val rule = createComposeRule()

    /** The last answer [PanelFooterHostActions] reported about the column it was mounted in. */
    private var reportedFits: Boolean? = null

    /**
     * The panel column, mounted through the REAL [PanelColumn] rather than a copy of its layout.
     *
     * That is what makes the carve-out assertions below mean anything: a test that rebuilds the
     * structure it means to pin passes just as happily when the production wrapper loses its
     * `weight(1f)`, draws the footer above the content, or re-implements the gate that decides
     * which column gets one.
     */
    private fun mountPanelColumn(
        placement: FocusQuickActionsPlacement,
        width: Dp = PANEL_WIDTH,
        height: Dp = PANEL_HEIGHT,
        everyButton: Boolean = false,
    ) {
        rule.setContent {
            val toolbox: (@Composable (Panel, Modifier) -> Unit)? =
                if (!everyButton) {
                    null
                } else {
                    { hint, mod ->
                        ToolboxButton(item = toolboxItem(), onClick = {}, hintDirection = hint, modifier = mod)
                    }
                }
            val launcher: (@Composable (Panel, Modifier) -> Unit)? =
                if (!everyButton) {
                    null
                } else {
                    { hint, mod -> ToolLauncherButton(onClick = {}, hintDirection = hint, modifier = mod) }
                }
            // clipToBounds mirrors the panel, which is what turns an overflow into a vanished
            // button rather than one drawn outside its column.
            Box(
                modifier =
                    Modifier
                        .width(width)
                        .height(height)
                        .clipToBounds()
                        .testTag(COLUMN_TAG),
            ) {
                PanelColumn(
                    column = right,
                    footerEdge = right,
                    footer = {
                        PanelFooterHostActions(
                            actionCount = if (everyButton) EVERY_BUTTON else FOCUS_QUICK_ACTION_COUNT,
                            actions =
                                focusQuickActionsPanelFooter(
                                    placement = placement,
                                    onShowSettings = {},
                                    onShowSearch = {},
                                    onSignOut = {},
                                    toolbox = toolbox,
                                    toolLauncher = launcher,
                                ),
                            onColumnFitsChange = { fits -> reportedFits = fits },
                        )
                    },
                ) {
                    // Stands in for SidePanel: the plugin's own content, which is what the
                    // cluster used to be drawn over.
                    Box(modifier = Modifier.fillMaxSize().testTag(PLUGIN_TAG))
                }
            }
        }
        rule.waitForIdle()
    }

    private fun toolboxItem() =
        SidebarItem(
            pluginContentId = PanelIds.PLUGIN_MANAGER,
            icon = Icons.Outlined.Extension,
            label = "Toolbox",
        )

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    @Test
    fun `the row is carved out of the panel, not drawn over it`() {
        mountPanelColumn(FocusQuickActionsPlacement.PANEL_FOOTER)

        val plugin = bounds(PLUGIN_TAG)
        val row = bounds(PANEL_FOOTER_HOST_ACTIONS_TAG)

        assertTrue(row.height > 0f, "the row reserved no height at all")
        assertTrue(
            plugin.bottom <= row.top,
            "the plugin ends at ${plugin.bottom} but the row starts at ${row.top} - they overlap",
        )
        assertEquals(
            bounds(COLUMN_TAG).bottom,
            row.bottom,
            "the row belongs at the very bottom of the column",
        )
    }

    @Test
    fun `the row is the panel's width, not the window's`() {
        // The whole point of moving it here from a content-area band. Asserted against the
        // column it was given rather than a number, so a different panel width still pins it.
        mountPanelColumn(FocusQuickActionsPlacement.PANEL_FOOTER)

        val column = bounds(COLUMN_TAG)
        val row = bounds(PANEL_FOOTER_HOST_ACTIONS_TAG)

        assertEquals(column.left, row.left, "the row starts where its column does")
        assertEquals(column.right, row.right, "and ends where its column does")
    }

    @Test
    fun `every button sits inside the row it reserved`() {
        mountPanelColumn(FocusQuickActionsPlacement.PANEL_FOOTER)

        val row = bounds(PANEL_FOOTER_HOST_ACTIONS_TAG)
        val expected = with(rule.density) { SIDEBAR_ICON_SIZE.toPx() }

        // The Toolbox slot is null here, so it draws no icon of its own - see
        // HostActionsContentTest, which mounts the real button.
        listOf("Sign Out", "Settings", "Search").forEach { label ->
            val icon = rule.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
            assertEquals(expected, icon.width, "$label is ${icon.width}px wide, expected $expected")
            assertTrue(
                icon.top >= row.top && icon.bottom <= row.bottom,
                "$label spans ${icon.top}..${icon.bottom}, outside the row's ${row.top}..${row.bottom}",
            )
            assertTrue(
                icon.left >= row.left && icon.right <= row.right,
                "$label spans ${icon.left}..${icon.right}, outside the row's ${row.left}..${row.right}",
            )
        }
    }

    @Test
    fun `every action survives the narrowest column that still hosts them`() {
        // The row wraps rather than clipping, and that claim is only worth making at the
        // narrowest width it is ever asked to hold - carrying the widest content it ever has:
        // five buttons, which is a TOP tab bar with both icon strips switched off.
        //
        // WRAP_WIDTH is two lines, the most `panelFooterFitsColumn` allows. Below it the column
        // stops hosting these at all rather than growing a tower of them - the test below.
        mountPanelColumn(FocusQuickActionsPlacement.PANEL_FOOTER, width = WRAP_WIDTH, everyButton = true)

        val column = bounds(COLUMN_TAG)
        val row = bounds(PANEL_FOOTER_HOST_ACTIONS_TAG)
        val expected = with(rule.density) { SIDEBAR_ICON_SIZE.toPx() }
        listOf("Sign Out", "Settings", "Toolbox", "Tools", "Search").forEach { label ->
            val icon = rule.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
            // BOTH axes. A row that cannot fit its children hands the last one a 0x0 rect at the
            // origin, and a column narrower than an icon renders a 4dp-WIDE one - `Modifier.size`
            // coerces to the incoming constraint rather than overflowing it. Height alone passed
            // while the icons were slivers.
            assertEquals(expected, icon.height, "$label is ${icon.height}px tall, expected $expected")
            assertEquals(expected, icon.width, "$label is ${icon.width}px wide, expected $expected")
            assertTrue(
                icon.top >= column.top && icon.bottom <= column.bottom,
                "$label spans ${icon.top}..${icon.bottom}, outside the column's ${column.top}..${column.bottom}",
            )
        }

        // And the plugin still has the column. This is the assertion the old floor test was
        // missing: the icons surviving is only good news if they did not take the panel to do it.
        val plugin = bounds(PLUGIN_TAG)
        assertTrue(
            plugin.height > row.height * 2,
            "the plugin kept ${plugin.height} against ${row.height} of chrome - the foot is eating its column",
        )
    }

    @Test
    fun `a column too small for the row reports it and draws nothing`() {
        // The failure this reports out of layout to avoid. At the panel's 20dp floor the row
        // wraps to five lines - measured at 188dp, with 4dp-wide icons - and `PanelColumn` gives
        // the footer its height before the plugin, so the plugin gets what is left.
        //
        // The report is what makes the fallback work: the scaffold hands the actions back to the
        // floating cluster, which is what this configuration had before this change.
        mountPanelColumn(FocusQuickActionsPlacement.PANEL_FOOTER, width = FLOOR_WIDTH, everyButton = true)

        assertEquals(false, reportedFits, "a 20dp column must report that it cannot host the row")

        // Still no row of its own, because the placement is what decides that and it has not been
        // told yet - so the guard has to be the report, not a half-drawn footer.
        assertEquals(
            false,
            panelFooterFitsColumn(FLOOR_WIDTH, PANEL_HEIGHT, EVERY_BUTTON, SPACE_SM, SPACE_XS),
            "the rule itself, so the report and the table cannot drift",
        )
    }

    @Test
    fun `a short column hands them back even at a comfortable width`() {
        // The other axis. A 250dp panel in a 100dp-tall content area fits the row on one line and
        // still should not spend nearly half its height on it.
        mountPanelColumn(FocusQuickActionsPlacement.PANEL_FOOTER, height = SHORT_COLUMN)

        assertEquals(false, reportedFits, "a 100dp column must report that it cannot host the row")
    }

    @Test
    fun `a default panel hosts them and says so`() {
        mountPanelColumn(FocusQuickActionsPlacement.PANEL_FOOTER)

        assertEquals(true, reportedFits, "a 250x400 column is where this placement is aimed")
        rule.onAllNodesWithTag(PANEL_FOOTER_HOST_ACTIONS_TAG).assertCountEquals(1)
    }

    @Test
    fun `the row paints its own background`() {
        // The one regression in this placement that no bounds assertion can see: the row is a
        // strip of column nothing else draws - SidePanel fills itself and stops where its content
        // does - and nothing is not the background. The raw native window surface shows through,
        // which is WHITE, and these are near-white icons.
        //
        // So this reads a PIXEL. Compose's semantics tree carries no colour, and every other test
        // in this file passes just as happily against an unpainted row.
        //
        // Compared against the TOKEN rather than a hardcoded value: `BossColors` comes through a
        // CompositionLocal, so the expectation has to be read from the same place the fill is.
        var expected = Color.Unspecified
        rule.setContent {
            // Published through SideEffect, which is what that hook is for: a plain assignment in
            // the composable body is a write to captured state that every recomposition repeats.
            val panel = BossTheme.colors.panel
            SideEffect { expected = panel }
            Box(modifier = Modifier.width(PANEL_WIDTH).height(PANEL_HEIGHT).testTag(COLUMN_TAG)) {
                PanelColumn(
                    column = right,
                    footerEdge = right,
                    footer = {
                        PanelFooterHostActions(
                            actionCount = FOCUS_QUICK_ACTION_COUNT,
                            actions =
                                focusQuickActionsPanelFooter(
                                    placement = FocusQuickActionsPlacement.PANEL_FOOTER,
                                    onShowSettings = {},
                                    onShowSearch = {},
                                    onSignOut = {},
                                ),
                            onColumnFitsChange = {},
                        )
                    },
                ) {
                    Box(modifier = Modifier.fillMaxSize().testTag(PLUGIN_TAG))
                }
            }
        }
        rule.waitForIdle()

        val row = rule.onNodeWithTag(PANEL_FOOTER_HOST_ACTIONS_TAG).captureToImage().toPixelMap()
        // 2dp in from the row's start edge, halfway down it - converted through the test density
        // rather than passed as a raw pixel index, because the captured image scales with it and
        // an implicit 1x assumption has no business in the one test whose point is to be exact.
        // The buttons are centred in a 250dp column and take about 140dp of it, so this lands on
        // background and not on a glyph, and the fill is flat, so there is nothing to antialias.
        val inset = with(rule.density) { 2.dp.roundToPx() }
        val painted = row[inset, row.height / 2]

        // Compared as ARGB, not as Color: a Color carries its colour space, and toPixelMap hands
        // back whatever space the captured bitmap is in. Comparing the values themselves keeps a
        // difference nobody can see from failing the test.
        assertEquals(
            expected.toArgb(),
            painted.toArgb(),
            "the row came back $painted, not the panel fill - an unpainted strip shows the white window surface",
        )
    }

    @Test
    fun `any other placement leaves the panel exactly as it was`() {
        // The zero-cost half of the design: a window with nothing open gets the overlay, and no
        // panel anywhere should quietly grow a row of chrome in the meantime.
        mountPanelColumn(FocusQuickActionsPlacement.FLOATING)

        rule.onAllNodesWithTag(PANEL_FOOTER_HOST_ACTIONS_TAG).assertCountEquals(0)
        assertEquals(
            bounds(COLUMN_TAG).height,
            bounds(PLUGIN_TAG).height,
            "the plugin must still fill the whole column",
        )
    }
}

/**
 * Pins which column hosts the row, which is also the answer to "is there a panel foot at all".
 *
 * One function for both, because two expressions could disagree - and the way that fails is the
 * row rendered into a column nothing is composing: Sign Out on screen nowhere.
 *
 * The left and bottom columns are the interesting half now: both were candidates in an earlier
 * revision, and both were dropped on purpose - left because the cluster is nowhere near it, bottom
 * because its foot is the full-window band this placement exists to avoid AND because it is the
 * one column with no axis that can yield at its floor. A test rather than a comment, so growing
 * the table back is a decision someone makes rather than one that lands quietly.
 */
class HostActionsPanelEdgeTest {
    @Test
    fun `the right column takes them, being the one the cluster sat on`() {
        assertEquals(right, hostActionsPanelEdge(rightOpen = true))
    }

    @Test
    fun `a shut right panel means no column, which is what sends them back to the corner`() {
        // Whatever else is open. A left or bottom panel does not host these - see the KDoc.
        assertNull(hostActionsPanelEdge(rightOpen = false))
    }

    @Test
    fun `actions that are not homeless name no column at all`() {
        // The gate that keeps the measurement's cost proportional to the feature: naming a column
        // is what makes BossWindow compose the subcomposition that measures it, so a window whose
        // top bar still owns these must not name one however many panels are open.
        assertNull(hostActionsPanelEdge(rightOpen = true, needsAHome = false))
        assertEquals(right, hostActionsPanelEdge(rightOpen = true, needsAHome = true))
    }
}

private val PANEL_WIDTH = 250.dp

/** A content area tall enough that height is never the binding constraint. */
private val PANEL_HEIGHT = 400.dp

/**
 * The narrowest column that still carries the row: two lines of five buttons.
 *
 * `panelFooterFitsColumn` allows two, so three per line is the floor - 3 * 32dp + 2 * 4dp of gap
 * plus 8dp either side is 120dp, which is also where the vertical bar's own foot bottoms out.
 */
private val WRAP_WIDTH = 120.dp

/** Every button the row can hold: the four, plus the tools launcher with both strips off. */
private const val EVERY_BUTTON = FOCUS_QUICK_ACTION_COUNT + 1

/** A content area too short to spend a third of on four icons. */
private val SHORT_COLUMN = 100.dp

/** `BossSpacing`'s defaults, which are the only values ever provided - see BossTheme.kt. */
private val SPACE_SM = 8.dp
private val SPACE_XS = 4.dp

/** `BossResizablePanel`'s floor for a side panel - narrower than one 32dp icon. */
private val FLOOR_WIDTH = 20.dp
private const val COLUMN_TAG = "panel-footer-test-column"
private const val PLUGIN_TAG = "panel-footer-test-plugin"
