package ai.rever.boss.app

import ai.rever.boss.components.buttons.TOOL_LAUNCHER_TAG
import ai.rever.boss.components.buttons.ToolLauncherButton
import ai.rever.boss.components.buttons.ToolboxButton
import ai.rever.boss.components.plugin.PanelIds
import ai.rever.boss.layout.ChromeDimens
import ai.rever.boss.plugin.api.SidebarItem
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins that the host's actions fit the COLLAPSED rail, which is the narrowest thing that has ever
 * had to hold them.
 *
 * The rail is [ChromeDimens.MIN_STRIP_WIDTH] at the tightest density - 36dp for a 32dp button, so
 * there is 2dp either side and no room for a second column. The expanded bar's foot learned this
 * the hard way (see `VerticalBarHostActionsLayoutTest`): a `Row` that cannot fit its children does
 * not clip them, it hands the last one ZERO width, which renders as an action that is simply not
 * there. So these assertions check each icon's SIZE and not merely that it sits between the rail's
 * edges - a zero-width rect at the origin passes every bounds check ever written.
 */
class QuickActionsRailLayoutTest {
    @get:Rule
    val rule = createComposeRule()

    private fun mountRail() {
        val actions =
            focusQuickActionsTabRail(
                placement = FocusQuickActionsPlacement.TAB_BAR_RAIL,
                onShowSettings = {},
                onShowSearch = {},
                onSignOut = {},
                toolLauncher = { hintDirection, modifier ->
                    ToolLauncherButton(onClick = {}, hintDirection = hintDirection, modifier = modifier)
                },
            )
        rule.setContent {
            // clipToBounds mirrors the rail, which is what turns an overflow into a vanished
            // button rather than one drawn outside its column.
            Column(
                modifier =
                    Modifier
                        .width(ChromeDimens.MIN_STRIP_WIDTH)
                        .clipToBounds()
                        .testTag(RAIL_TAG),
            ) {
                VerticalBarRailActions(actions)
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

    private fun railBounds(): Rect = rule.onNodeWithTag(RAIL_TAG).fetchSemanticsNode().boundsInRoot

    private fun iconBounds(contentDescription: String): Rect =
        rule.onNodeWithContentDescription(contentDescription).fetchSemanticsNode().boundsInRoot

    /** Present at its full size AND within the rail's edges. Either alone passes the bug. */
    private fun assertShown(contentDescription: String) {
        val rail = railBounds()
        val icon = iconBounds(contentDescription)
        val expected = with(rule.density) { SIDEBAR_ICON_SIZE.toPx() }

        assertEquals(expected, icon.width, "$contentDescription is ${icon.width}px wide, expected $expected")
        assertEquals(expected, icon.height, "$contentDescription is ${icon.height}px tall, expected $expected")
        assertTrue(
            icon.left >= rail.left && icon.right <= rail.right,
            "$contentDescription spans ${icon.left}..${icon.right}, outside the rail's ${rail.left}..${rail.right}",
        )
    }

    @Test
    fun `every action fits the narrowest rail`() {
        mountRail()

        assertShown("Sign Out")
        assertShown("Settings")
        assertShown("Tools")
        assertShown("Search")

        // By tag as well as by label, for the same reason the bar's own test does it: a tag
        // nothing reads is a tag that can be renamed without a failure.
        val launcher = rule.onNodeWithTag(TOOL_LAUNCHER_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(
            with(rule.density) { SIDEBAR_ICON_SIZE.toPx() },
            launcher.width,
            "the launcher's own tag must find it at full size",
        )
    }

    @Test
    fun `they stack one per line, in the order the other hosts use`() {
        // A column, not a wrapping row: at 36dp there is room for exactly one, and the order is
        // Sign Out first so the destructive action is furthest from the window corner.
        mountRail()

        val signOut = iconBounds("Sign Out")
        val settings = iconBounds("Settings")
        val search = iconBounds("Search")

        assertTrue(signOut.bottom <= settings.top, "Sign Out ($signOut) must sit above Settings ($settings)")
        assertTrue(settings.bottom <= search.top, "Settings ($settings) must sit above Search ($search)")
    }

    @Test
    fun `every action survives the shortest rail the window can make`() {
        // The rail's SCARCE dimension once four or five stacked 32dp icons are added to it is
        // height, not width - and this column is the LAST thing in it, under a chevron, two
        // rules, the scrolling tab list and the "+". A Column measures its unweighted children in
        // order and gives the last ones nothing when it runs out, so these are the first casualty,
        // not the last: the same failure the bar's foot had on the other axis, an absent icon
        // rather than a clipped one.
        //
        // The stand-in carries `RAIL_FIXED_CHROME`, which is BossTabRail's real fixed budget added
        // up rather than estimated, and a weighted Box for the tab list - the one thing in that
        // bar with `weight(1f)`, and so the only thing that can yield. That is a real trade this
        // change makes and not a free win: the rail's own list of tabs is what pays for these.
        rule.setContent {
            Column(
                modifier =
                    Modifier
                        .width(ChromeDimens.MIN_STRIP_WIDTH)
                        .height(SHORT_RAIL_HEIGHT)
                        .testTag(RAIL_TAG),
            ) {
                Spacer(modifier = Modifier.height(RAIL_FIXED_CHROME))
                Box(modifier = Modifier.fillMaxWidth().weight(1f).testTag(TAB_LIST_TAG))
                VerticalBarRailActions(
                    focusQuickActionsTabRail(
                        placement = FocusQuickActionsPlacement.TAB_BAR_RAIL,
                        onShowSettings = {},
                        onShowSearch = {},
                        onSignOut = {},
                        // The REAL Toolbox button, not a second launcher. A copy of the slot
                        // below it mounts two nodes carrying "Tools" and TOOL_LAUNCHER_TAG, and
                        // leaves the one button this slot exists to cover untested in a rail.
                        toolbox = { hintDirection, modifier ->
                            ToolboxButton(
                                item = toolboxItem(),
                                onClick = {},
                                hintDirection = hintDirection,
                                modifier = modifier,
                            )
                        },
                        toolLauncher = { hintDirection, modifier ->
                            ToolLauncherButton(onClick = {}, hintDirection = hintDirection, modifier = modifier)
                        },
                    ),
                )
            }
        }
        rule.waitForIdle()

        val rail = railBounds()
        val expected = with(rule.density) { SIDEBAR_ICON_SIZE.toPx() }
        // All five, Toolbox and Tools included: they are the two that only appear in this
        // configuration, and a list that names only the other three is a list that would pass
        // with either of them missing.
        listOf("Sign Out", "Settings", "Toolbox", "Tools", "Search").forEach { label ->
            val icon = iconBounds(label)
            assertEquals(expected, icon.height, "$label is ${icon.height}px tall, expected $expected")
            assertTrue(icon.bottom <= rail.bottom, "$label ends at ${icon.bottom}, past the rail's ${rail.bottom}")
        }

        // And the tab list is what gave way, which is the claim rather than a side effect. No
        // clipToBounds here: BossTabRail has none either, so overflow in the real rail SPILLS,
        // and a harness that clipped would be describing a bar that does not exist.
        val tabList = rule.onNodeWithTag(TAB_LIST_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(
            tabList.height >= 0f && tabList.top >= rail.top,
            "the tab list should have absorbed the squeeze, not overflowed the rail",
        )
    }

    @Test
    fun `an empty list draws nothing, rule included`() {
        // What lets the rail hand this slot every placement's list: a rail whose actions live in
        // the top bar has to be exactly the rail that existed before this.
        rule.setContent {
            Column(modifier = Modifier.width(ChromeDimens.MIN_STRIP_WIDTH).testTag(RAIL_TAG)) {
                VerticalBarRailActions(
                    focusQuickActionsTabRail(
                        placement = FocusQuickActionsPlacement.TAB_BAR_FOOTER,
                        onShowSettings = {},
                        onShowSearch = {},
                        onSignOut = {},
                    ),
                )
            }
        }
        rule.waitForIdle()

        rule.onAllNodesWithTag(VERTICAL_BAR_RAIL_ACTIONS_TAG).assertCountEquals(0)
        assertEquals(0f, railBounds().height, "the empty slot must take no height at all")
    }
}

private const val RAIL_TAG = "quick-actions-rail-layout-test-rail"

/**
 * `BossTabRail`'s fixed budget, everything in it that is not the weighted tab list.
 *
 * 4 + 4 (the Column's vertical padding), 32 (the chevron), 4, 1 (rule), 6, then below the list
 * 6, 1 (rule), 4, 32 (the "+"). Added up rather than estimated, because the point of putting it
 * in the harness is that the harness stops being more generous than the bar.
 */
private val RAIL_FIXED_CHROME = 94.dp

/**
 * The shortest rail this window can produce, near enough.
 *
 * The rail is as tall as the content area. `DisplayUtils.calculateMainWindowSize` floors a new
 * main window at 600dp of height, and 300 is half of that - well under anything the app opens
 * itself, and still 17dp clear of the 283dp that [RAIL_FIXED_CHROME] plus five actions costs.
 *
 * Deliberately NOT a height where the actions lose: below about 283dp they do, because they are
 * measured last, and they vanish rather than clip. That is a real edge, reachable by dragging a
 * window short - the 600dp floor in `DisplayUtils.calculateMainWindowSize` is an initial size and
 * not a constraint. Tracked in issue #320, with the numbers and the shape of the fix; the panel
 * foot's `panelFooterFitsColumn` + `onColumnFitsChange` is the machinery it should reuse.
 */
private val SHORT_RAIL_HEIGHT = 300.dp

/** The rail's scrolling tab list, the one thing in it that can yield. */
private const val TAB_LIST_TAG = "quick-actions-rail-layout-test-tab-list"
