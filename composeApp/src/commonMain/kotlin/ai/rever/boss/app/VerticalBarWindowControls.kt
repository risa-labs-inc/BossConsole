package ai.rever.boss.app

import ai.rever.boss.components.buttons.BossActionButton
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.WorkspaceButton
import ai.rever.boss.components.workspaces.WorkspaceManager
import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.right
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.window.Project
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Divider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Folder

/**
 * The project and workspace pickers, at the foot of the vertical tab bar.
 *
 * These live in the top bar. With the top bar switched off there is nowhere else for them, and
 * "which project am I in" stops being answerable from the window at all - so the vertical bar,
 * the one piece of window chrome still on screen in that configuration, takes them.
 *
 * Drawn ONLY when the top bar is off. Both at once would be the same two controls twice, and the
 * top bar is where they belong when it is there.
 *
 * They sit above the split map rather than below it. The map is the bar's last row by design -
 * it is a picture of the window, and a picture of the window belongs at the bottom of the thing
 * that lists what is in it.
 */
@Composable
internal fun VerticalBarWindowControls(
    /**
     * Whether the top bar - which owns these two controls - is off screen.
     *
     * Passed as what is DRAWN rather than what is preferred, so a top bar focus mode has cleared
     * counts: the pickers live nowhere else, and focus mode is exactly when a window is at its
     * barest.
     *
     * Deliberately the STANDING focus-mode state, not the reveal flag. Keyed on the reveal, these
     * would appear and disappear on every hover, and this footer sits directly above the split
     * map - so the map would jump up and down the bar each time. Two copies of a picker for the
     * seconds a bar is revealed is the better of the two.
     */
    topBarHidden: Boolean,
    project: Project,
    onOpenProject: () -> Unit,
    workspaceManager: WorkspaceManager,
    onApplyWorkspace: (LayoutWorkspace) -> Unit,
    getCurrentWorkspace: () -> LayoutWorkspace,
    onShowTopOfMind: () -> Unit,
) {
    if (!topBarHidden) return

    Divider(color = BossTheme.colors.line)
    Column(
        // Tight on purpose. These are two rows of a narrow bar, not a toolbar: the padding that
        // reads as breathing room across a 1500dp top bar reads as dead space down a 200dp one,
        // and there is a split map below them competing for the same inches.
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        // Not zero. Two 24dp rows flush against each other put their click targets in direct
        // contact, and a click a pixel off the one you meant activates the other - which for
        // these two means opening the wrong thing entirely, a project dialog or a workspace menu.
        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
    ) {
        BossActionButton(
            // A folder rather than the top bar's project LOGO tile. That tile is 28dp of solid
            // colour built to anchor a wide bar; down a 200dp column it is the loudest thing on
            // screen and it is decoration.
            leftIcon = FeatherIcons.Folder,
            // A project with no path is no project: the button then offers the action rather than
            // naming the empty one, which is what the top bar's copy does too.
            text = if (project.path.isEmpty()) "Open Project" else project.name,
            // The top bar's copy hangs a recent-projects menu off this button. Here it opens the
            // project dialog instead - the same one the File menu and the dashboard open - rather
            // than standing up a second recent-projects menu with its own remove and rename
            // dialogs behind it. One control, one window-level dialog.
            //
            // Null, not emptyList: a non-null list makes the button open a menu on click, and an
            // empty one would open an empty menu on top of the dialog.
            contextMenuItems = null,
            hintText = if (project.path.isEmpty()) "Open a project" else project.path,
            maxTextWidth = LABEL_MAX_WIDTH,
            compact = true,
            onClick = onOpenProject,
        )
        WorkspaceButton(
            onOpenWorkspace = onApplyWorkspace,
            workspaceManager = workspaceManager,
            getCurrentWorkspace = getCurrentWorkspace,
            onShowTopOfMind = onShowTopOfMind,
            compact = true,
        )
    }
}

/**
 * How wide a project or workspace name may get before it truncates.
 *
 * The bar is 200dp and these rows carry an icon and a chevron either side of the label, so
 * without a cap a long project name pushes the chevron off the end of its own button.
 */
private val LABEL_MAX_WIDTH = 130.dp

/** Air between the two rows: enough that a click near the boundary cannot land on the other. */
private val ROW_GAP = 4.dp

/**
 * The host's own actions at the very foot of the vertical tab bar, under the split map.
 *
 * Settings, Search, Sign Out and - when both icon strips are off - the tools launcher. This is
 * the [FocusQuickActionsPlacement.TAB_BAR_FOOTER] rendering, chosen over the floating cluster
 * whenever this bar is on screen: the cluster is a native always-on-top window with no
 * click-through, and the bar is chrome the app already draws.
 *
 * A Row, where the right rail lays the same actions out as a Column, because this bar is wide and
 * short of vertical room rather than the other way round.
 *
 * Renders nothing at all when [actions] is empty, padding included, so a bar whose actions live
 * somewhere else is exactly the bar that existed before this.
 */
@Composable
internal fun VerticalBarHostActions(actions: List<@Composable () -> Unit>) {
    if (actions.isEmpty()) return

    // No background of its own: this row sits inside the bar, which fills itself with
    // `colors.panel`. The panel foot, whose column stops where the plugin's content does, has to
    // paint - see PanelFooterHostActions.
    HostActionsFlowRow(tag = VERTICAL_BAR_HOST_ACTIONS_TAG, actions = actions)
}

/** Test tag of the footer row - see `VerticalBarHostActionsLayoutTest`. */
internal const val VERTICAL_BAR_HOST_ACTIONS_TAG = "vertical-bar-host-actions"

/**
 * The host's actions as one wrapping row, shared by the two feet that lay them out horizontally:
 * the vertical bar's, under its split map, and an open plugin panel's.
 *
 * **A FlowRow, not a Row, because these do not fit on one line in a narrow column.** The bar goes
 * down to `TabBarVerticalWidthRange.start`, 120dp, and a panel to a floor of about 20dp. Four 32dp
 * buttons with `space.xs` between them and `space.sm` either side need 156dp, and three need
 * exactly 120 - no margin at all. A Row does not wrap, and what it did instead, measured at 120dp,
 * was give its LAST child zero width: Search came back as a 0x0 rect while the other three kept
 * their full size. Not a clipped icon - an absent one, on a width the user can reach by dragging.
 *
 * Wrapping is the fallback and not the shape: at any comfortable width these still lay out side by
 * side, which `VerticalBarHostActionsLayoutTest` pins along with the narrow case.
 *
 * Shared rather than copied so the two feet cannot drift in height or spacing - they are the same
 * control in two places, and a user moving between them should not be able to tell.
 *
 * **Each host adds its own separator, and the three differ on purpose** - what is directly above
 * the row is different in each, and that is what a rule is for:
 *
 * - The **bar's foot** draws none. Above it is `SplitMap`, which is an inset, rounded, bordered
 *   picture on the bar's own fill, so it delimits itself; a rule under it would be a second edge
 *   a few dp below the first.
 * - The **rail** draws a short centred rule, the one it already uses between panes, because a
 *   full-width one at 36dp reads as the end of the bar rather than a division inside it.
 * - The **panel's foot** draws a full-width one. Above it is a plugin's arbitrary content on a
 *   fill this row has to paint for itself, and without the rule the actions read as the plugin's.
 *
 * So the shared thing is the row - height, spacing, wrap behaviour - and the chrome around it is
 * the host's. Anything the row itself owns belongs in here, where it cannot drift.
 */
@Composable
internal fun HostActionsFlowRow(
    tag: String,
    actions: List<@Composable () -> Unit>,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(tag)
                .padding(horizontal = BossTheme.space.sm, vertical = HOST_ACTIONS_ROW_INSET),
        horizontalArrangement = Arrangement.spacedBy(BossTheme.space.xs, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(BossTheme.space.xs),
    ) {
        // No key: the list is fixed-order for a given placement, so positional identity is what a
        // key would give - the same call SidebarBottomActions makes about the same actions.
        actions.forEach { action -> action() }
    }
}

/**
 * Air above and below the icons.
 *
 * A literal because the scale has no step between `space.xs` (4dp) and `space.md` (12dp): xs makes
 * a 40dp row that reads as cramped against the bar's other chrome, md a 56dp one that reads as a
 * toolbar. Kept in one place so both feet are the same height.
 *
 * `internal` because `panelFooterFitsColumn` computes what this row will COST a panel column
 * before deciding to draw it, and a second copy of the number there is a second copy that can be
 * edited alone.
 */
internal val HOST_ACTIONS_ROW_INSET = 6.dp

/**
 * The actions as a row for the foot of the vertical tab bar, under its split map.
 *
 * Same buttons, third layout. Hints point UP, because this row is the last thing in the bar and a
 * hint below it would be off the bottom of the window - the same call the rail makes pointing them
 * inward. Icons are rail-sized rather than the floating cluster's 28dp, since what they sit under
 * is the bar's own chrome.
 *
 * Empty for every other placement, so the bar can call it unconditionally and render nothing.
 */
// One parameter per action plus the placement and the launcher slot. Folding them into a holder
// would put the actions somewhere a caller has to build before it can name one, for no gain.
@Suppress("LongParameterList")
internal fun focusQuickActionsFooter(
    placement: FocusQuickActionsPlacement,
    onShowSettings: () -> Unit,
    onShowSearch: () -> Unit,
    onSignOut: () -> Unit,
    toolbox: (@Composable (hintDirection: Panel, modifier: Modifier) -> Unit)? = null,
    toolLauncher: (@Composable (hintDirection: Panel, modifier: Modifier) -> Unit)? = null,
): List<@Composable () -> Unit> =
    focusQuickActionsFor(
        owner = FocusQuickActionsPlacement.TAB_BAR_FOOTER,
        hintDirection = top,
        placement = placement,
        onShowSettings = onShowSettings,
        onShowSearch = onShowSearch,
        onSignOut = onSignOut,
        toolbox = toolbox,
        toolLauncher = toolLauncher,
    )

/**
 * The host's own actions down the foot of the bar's COLLAPSED rail, under the "+".
 *
 * The [FocusQuickActionsPlacement.TAB_BAR_RAIL] rendering. A rail is the same bar with its labels
 * taken away, so it is still chrome the app draws - and collapsing the bar is a request for
 * content width, which is the last state in which to answer with a native always-on-top window
 * parked over the content. This is where those four went instead.
 *
 * A Column, where the expanded bar's foot is a wrapping Row: the rail is as narrow as
 * `ChromeDimens.MIN_STRIP_WIDTH` and as tall as the window, so one icon per line is the only
 * layout it has room for. That is also why this is a separate placement rather than a flavour of
 * [VerticalBarHostActions] - nothing about the two layouts is shared, and only one of them is ever
 * on screen.
 *
 * Renders nothing at all when [actions] is empty, rule included, so a rail whose actions live
 * somewhere else is exactly the rail that existed before this.
 */
@Composable
internal fun VerticalBarRailActions(actions: List<@Composable () -> Unit>) {
    if (actions.isEmpty()) return

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(VERTICAL_BAR_RAIL_ACTIONS_TAG)
                .padding(vertical = BossTheme.space.xs),
        verticalArrangement = Arrangement.spacedBy(BossTheme.space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The rail's own kind of separator - a short centred rule, the same one it draws between
        // its panes - rather than a full-width divider, which at this width reads as a bar end.
        Divider(color = BossTheme.colors.line, modifier = Modifier.fillMaxWidth(0.6f))
        // No key: fixed-order for a given placement, so positional identity is what a key gives.
        actions.forEach { action -> action() }
    }
}

/** Test tag of the rail's action column - see `QuickActionsRailLayoutTest`. */
internal const val VERTICAL_BAR_RAIL_ACTIONS_TAG = "vertical-bar-rail-actions"

/**
 * The actions as a column for the foot of the collapsed rail.
 *
 * Same buttons, fourth layout. Hints point RIGHT, into the window: the rail is against the
 * window's start edge, so a hint to its left would be off screen - the mirror of the call the
 * right rail makes pointing its own hints inward.
 *
 * Buttons are the 32dp the other four hosts use, so these read as the same control wherever they
 * land. That is deliberately NOT the rail's own chrome: its chevron and "+" are 16dp glyphs in
 * `textSecondary` on a `raised` chip, so the actions under them are the larger, brighter, chipless
 * group. Belonging to the action family beat matching the two buttons above them.
 *
 * Empty for every other placement, so the rail can call it unconditionally and render nothing.
 */
// One parameter per action plus the placement and the launcher slot - see focusQuickActionsFooter.
@Suppress("LongParameterList")
internal fun focusQuickActionsTabRail(
    placement: FocusQuickActionsPlacement,
    onShowSettings: () -> Unit,
    onShowSearch: () -> Unit,
    onSignOut: () -> Unit,
    toolbox: (@Composable (hintDirection: Panel, modifier: Modifier) -> Unit)? = null,
    toolLauncher: (@Composable (hintDirection: Panel, modifier: Modifier) -> Unit)? = null,
): List<@Composable () -> Unit> =
    focusQuickActionsFor(
        owner = FocusQuickActionsPlacement.TAB_BAR_RAIL,
        hintDirection = right,
        placement = placement,
        onShowSettings = onShowSettings,
        onShowSearch = onShowSearch,
        onSignOut = onSignOut,
        toolbox = toolbox,
        toolLauncher = toolLauncher,
    )

/** What the window's vertical tab bar can offer the host's actions. Mutually exclusive. */
internal enum class VerticalBarHost {
    /** No bar to offer anything: the tab bar is in TOP position. */
    NONE,

    /** A full bar, with a foot under its split map. */
    FOOT,

    /** The collapsed rail, whose one piece of free room is its bottom. */
    RAIL,
}

/**
 * What the vertical tab bar can host right now.
 *
 * Three states, not two, and an enum rather than a pair of booleans because two of the three are
 * the same bar: an EXPANDED left bar has a foot under its split map, a COLLAPSED one is a rail
 * whose bottom is the only room it has, and a collapsed bar whose hover drawer is OPEN has a foot
 * again for as long as the drawer is up, because the drawer is a full bar. Carried as two flags
 * these would admit "a foot AND a rail", which is not a window that exists.
 *
 * Pure and named because it is the one input to [focusQuickActionsPlacement] that is not a
 * standing preference, and because the scaffold that reads it is at detekt's complexity ceiling.
 */
internal fun verticalBarHost(
    tabBarOnLeft: Boolean,
    barCollapsed: Boolean,
    drawerVisible: Boolean,
): VerticalBarHost =
    when {
        !tabBarOnLeft -> VerticalBarHost.NONE
        !barCollapsed || drawerVisible -> VerticalBarHost.FOOT
        else -> VerticalBarHost.RAIL
    }
