package ai.rever.boss.app

import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.right
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.Divider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/**
 * Which open plugin panel's column takes the host's actions, or null when none of them does.
 *
 * **The right column, and only the right column.** The floating cluster this displaces sits in
 * the content area's bottom-RIGHT corner, so a right panel is the one an open panel actually
 * collides with. The other two were in an earlier revision of this and are deliberately out:
 *
 * - **Left.** There is no collision to fix. A left panel is the width of the window away from the
 *   cluster, so hosting these there would move Sign Out across the window to dodge an overlap
 *   that is not happening - and move it back, disposing and re-creating the overlay's native
 *   window, when the panel closes.
 * - **Bottom.** The collision is real, but the fix contradicts the reason this placement exists:
 *   a bottom panel spans the whole window, so its foot IS the full-width band this rejected in
 *   favour of chrome at the scale of the thing it belongs to. It is also the one column that
 *   cannot yield - `BossResizablePanel` floors a panel at `max(2% of the axis, 20.dp)`, and the
 *   scarce axis for a bottom panel is the one the row needs about 45dp of, so at its floor the
 *   plugin gets no height and `Modifier.size` shrinks the icons to fit rather than overflowing.
 *   A right panel's scarce axis is width, where the row wraps - which is pinned at 20dp.
 *
 * So a TOP-bar window with only a left or bottom panel open keeps the cluster it has today. That
 * is the behaviour this change found, not one it introduces.
 *
 * Still a `Panel?` rather than a Boolean: `BossWindow` gates three columns on
 * `panelFooterEdge == panel`, so the answer has to name one. One function for both halves of the
 * decision - whether these get a panel foot at all ([FocusQuickActionsPlacement.PANEL_FOOTER]
 * against [FocusQuickActionsPlacement.FLOATING]) and which column draws it. Two expressions could
 * disagree, and the way that fails is a row rendered into a column nothing is composing: Sign Out
 * on screen nowhere.
 *
 * Takes the root's visibility rather than the component, so the table is testable without a plugin
 * host. `isVisible(right)` folds in its own top and bottom halves, so one flag covers both right
 * panels.
 *
 * [needsAHome] is what keeps the cost of this proportional to the feature. Naming a column makes
 * `BossWindow` compose [PanelFooterHostActions], which is a subcomposition that re-measures on
 * every constraint change - so in the default configuration, top bar up and these actions not
 * homeless at all, a right-panel drag-resize would pay for one per frame to answer a question
 * nothing reads. It is decided from settings and the bar alone, never from what that measurement
 * reports, so it is a gate and not a cycle.
 */
internal fun hostActionsPanelEdge(
    rightOpen: Boolean,
    needsAHome: Boolean = true,
): Panel? = if (rightOpen && needsAHome) right else null

/**
 * The host's own actions as a row at the foot of the open right panel's column.
 *
 * The [FocusQuickActionsPlacement.PANEL_FOOTER] rendering, for a window in TOP tab-bar position -
 * no rail, no vertical bar, nothing else left to hold these - with the right panel open.
 *
 * **A row inside the panel, not a band across the content area.** The floating cluster it replaces
 * has no click-through on either path, so with a panel open it parks a dead region over the corner
 * of something the user just asked to see. A band spanning the whole window fixes that collision
 * and spends the window's entire width saying four icons; the panel's own foot is chrome at the
 * scale of the thing it belongs to, which is where [hostActionsPanelEdge] puts it.
 *
 * Laid out by [HostActionsFlowRow], the same row the bar's own foot uses, so the two cannot drift
 * in height or spacing.
 *
 * **Only when the column can afford it.** A panel is resizable down to a floor of about 20dp, and
 * a row that wraps rather than clips turns width pressure into height pressure: at that floor the
 * five buttons - the four plus the tools launcher - stack one per line into 188dp of chrome, and
 * the plugin, measured second since [PanelColumn] gives it the weight, gets whatever is left at
 * all. Measured at 20x400 that is 211dp of
 * plugin behind 188dp of icons, each of them 4dp WIDE, because `Modifier.size` coerces to the
 * incoming constraint on that axis too. So this measures its column first and reports the answer
 * back to the scaffold through [onColumnFitsChange]; when the column cannot afford the row the
 * placement falls back to the floating cluster and nothing is drawn here at all.
 *
 * That is the same shape `onBarRailedChange` already has in this window: a fact only layout knows,
 * reported up to the state the placement is decided from. It costs one frame - the report rides a
 * `LaunchedEffect` - and it cannot oscillate, because the constraints this reads do not depend on
 * what it draws.
 *
 * The measuring shell composes whether or not there is a row, which is what makes the fallback
 * reversible: a column that reports "no" renders nothing, and would have no way to report "yes
 * again" once the user widens it if the measurement lived behind the same guard as the row.
 *
 * The scaffold's state starts at "fits", so a panel opened AT a sliver width draws this row for
 * one frame before the report takes it away. That direction is the deliberate one: the other
 * default flickers the cluster's native window open and shut on every right-panel open at the
 * 250dp width almost everyone uses, and a frame of an over-tall row is cheaper than a frame of
 * window churn.
 *
 * Renders nothing at all when [actions] is empty, rule included, so a panel in a window that keeps
 * these somewhere else is exactly the panel that existed before this.
 *
 * @param actionCount how many buttons the row would hold if it drew one, which the measurement
 *   needs while [actions] is empty - the very state a "no" answer puts this in. Counted the way
 *   `focusQuickActionsRailRows` counts the rail's reserve, Toolbox included.
 * @param onColumnFitsChange whether this column can afford the row. Read by the scaffold, which
 *   owns the placement; see `focusQuickActionsPlacement`.
 */
@Composable
internal fun PanelFooterHostActions(
    actionCount: Int,
    actions: List<@Composable () -> Unit>,
    onColumnFitsChange: (Boolean) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val fits =
            panelFooterFitsColumn(
                columnWidth = maxWidth,
                columnHeight = maxHeight,
                actionCount = actionCount,
                sideInset = BossTheme.space.sm,
                gap = BossTheme.space.xs,
            )
        // Through an effect, not straight out of composition: this writes state the scaffold reads
        // to pick the placement, and a write during composition to something a parent has already
        // read is the recomposition-loop trap `drawerVisible` documents on the other slot.
        //
        // The effect keys on the ANSWER, so it fires once per change of it rather than once per
        // recomposition - which means it would otherwise capture whichever callback was current
        // when the answer last changed. `rememberUpdatedState` is what keeps it the current one.
        val report by rememberUpdatedState(onColumnFitsChange)
        LaunchedEffect(fits) { report(fits) }

        if (actions.isEmpty()) return@BoxWithConstraints

        Column(modifier = Modifier.fillMaxWidth()) {
            // Full width, where the rail's rule is short and centred: this row spans its column,
            // and the line is what separates it from the PLUGIN's own content above - which is why
            // this foot has a rule where the bar's has none. See `HostActionsFlowRow`.
            Divider(color = BossTheme.colors.line)
            // PAINTED, before it is padded. This row is a strip of column that nothing else draws
            // - SidePanel fills itself and stops where its content does - and nothing is not the
            // background: the raw native window surface shows through, which is WHITE. It then
            // puts near-white icons on a white band, so the actions come out invisible rather than
            // merely misplaced. Every other host of these gets its background from the bar or
            // Surface it sits in; this one has to paint.
            //
            // `colors.panel`, what SidePanel fills with, not `raised`: with the rule above doing
            // the separating, the row and the plugin read as one column rather than as a shelf
            // stuck to the bottom of it.
            HostActionsFlowRow(
                tag = PANEL_FOOTER_HOST_ACTIONS_TAG,
                actions = actions,
                modifier = Modifier.background(BossTheme.colors.panel),
            )
        }
    }
}

/**
 * Whether a panel column of this size can afford the host's actions at the foot of it.
 *
 * Three questions, and each one is a way the row went wrong when it was drawn unconditionally:
 *
 * 1. **One icon has to fit a line at full size.** [HostActionsFlowRow] pads by [sideInset] either
 *    side, and `Modifier.size` coerces to what is left rather than overflowing it, so a 20dp
 *    column does not clip a 32dp icon - it renders a 4dp one. Measured, not reasoned about.
 * 2. **At most [PANEL_FOOTER_MAX_LINES] lines.** Wrapping is what keeps the row honest in a
 *    narrow column, but five buttons one per line is a 188dp tower, which is not "chrome at the
 *    scale of the thing it sits in" by any reading.
 * 3. **At most [PANEL_FOOTER_MAX_SHARE] of the column.** The plugin is what the user opened; a
 *    foot that can take two thirds of its column is the failure this placement uses to rule the
 *    bottom column out, reached by the other axis.
 *
 * Pure, and takes the two spacing tokens rather than reading `BossTheme` itself, so the table can
 * be tested without a composition. `BossSpacing` is a plain data class whose defaults are the only
 * values ever provided, so 8dp and 4dp in a test are the 8dp and 4dp the row is laid out with.
 */
internal fun panelFooterFitsColumn(
    columnWidth: Dp,
    columnHeight: Dp,
    actionCount: Int,
    sideInset: Dp,
    gap: Dp,
): Boolean {
    val contentWidth = columnWidth - sideInset * 2

    // What FlowRow does: fill a line with `icon + gap` slots, the last one needing no trailing
    // gap. Floored at one so the arithmetic below stays defined for a column too narrow to hold
    // even that - the first condition is what rejects it.
    val perLine = ((contentWidth + gap) / (SIDEBAR_ICON_SIZE + gap)).toInt().coerceAtLeast(1)
    val lines = ceil(actionCount.toFloat() / perLine).toInt()
    val footerHeight =
        HOST_ACTIONS_ROW_INSET * 2 + SIDEBAR_ICON_SIZE * lines + gap * (lines - 1) + PANEL_FOOTER_RULE

    return contentWidth >= SIDEBAR_ICON_SIZE &&
        lines <= PANEL_FOOTER_MAX_LINES &&
        footerHeight <= columnHeight * PANEL_FOOTER_MAX_SHARE
}

/**
 * How many lines of icons a panel foot may wrap to before it stops being a foot.
 *
 * Two, not one: a right panel narrowed to about 150dp still wants these, and two lines there is
 * 80dp of chrome under 519dp of plugin. Three would be 116dp in a column too narrow to have put
 * anything useful in.
 */
private const val PANEL_FOOTER_MAX_LINES = 2

/**
 * The most of its column a foot may take.
 *
 * A third. The plugin keeps the other two, which is the least that can be said for a placement
 * whose whole argument is that it does not cover what the user opened.
 */
private const val PANEL_FOOTER_MAX_SHARE = 1f / 3f

/** The rule above the row, counted because it is part of what the foot costs the column. */
private val PANEL_FOOTER_RULE = 1.dp

/** Test tag of the row - see `QuickActionsPanelFooterTest`. */
internal const val PANEL_FOOTER_HOST_ACTIONS_TAG = "panel-footer-host-actions"

/**
 * The actions as a row for the foot of a plugin panel's column.
 *
 * Same buttons, fifth layout. Hints point UP, because the row is the last thing in its column and
 * a hint below it would be off the bottom of the window - the same call the bar's own foot makes.
 * Icons are panel-chrome sized, matching the two bar hosts rather than the cluster's 28dp.
 *
 * Empty for every other placement, so the panel can call it unconditionally and render nothing.
 */
// One parameter per action plus the placement and the launcher slot - see focusQuickActionsFooter.
@Suppress("LongParameterList")
internal fun focusQuickActionsPanelFooter(
    placement: FocusQuickActionsPlacement,
    onShowSettings: () -> Unit,
    onShowSearch: () -> Unit,
    onSignOut: () -> Unit,
    toolbox: (@Composable (hintDirection: Panel, modifier: Modifier) -> Unit)? = null,
    toolLauncher: (@Composable (hintDirection: Panel, modifier: Modifier) -> Unit)? = null,
): List<@Composable () -> Unit> =
    focusQuickActionsFor(
        owner = FocusQuickActionsPlacement.PANEL_FOOTER,
        hintDirection = top,
        placement = placement,
        onShowSettings = onShowSettings,
        onShowSearch = onShowSearch,
        onSignOut = onSignOut,
        toolbox = toolbox,
        toolLauncher = toolLauncher,
    )
