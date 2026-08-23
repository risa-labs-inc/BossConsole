package ai.rever.boss.components.window_panel.components.main_window_panels

import ai.rever.boss.window.TabBarPosition
import ai.rever.boss.window.TabBarVerticalWidthRange
import ai.rever.boss.window.WindowAppearanceSettingsManager
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * How a panel's tab bar is laid out right now: which edge, how wide, and whether it is down to
 * the rail.
 *
 * The four values are derived from two independent inputs - the global appearance settings and
 * this panel's own measured width - and every one of them is read in more than one place in
 * `BossMainPanel`. Resolving them once, together, is what keeps the in-flow bar, the drawer and
 * the reveal state machine from disagreeing about which mode the bar is in.
 */
data class TabBarLayout(
    /** The bar is a column on the panel's leading edge rather than a strip across its top. */
    val vertical: Boolean,
    /** Width of the full vertical bar, already clamped to [TabBarVerticalWidthRange]. */
    val width: Dp,
    /** The panel is too narrow for a full bar, so the rail is forced and the chevron opens a drawer. */
    val narrow: Boolean,
    /** The slim icon rail, not the full column, is what is in the layout. */
    val railShown: Boolean,
    /** The `tabBarHoverExpand` setting. */
    val hoverExpand: Boolean,
)

/**
 * [TabBarLayout] for a panel [panelWidthPx] wide.
 *
 * @param panelWidthPx measured, and 0 until the first layout pass. `narrow` stays false at 0
 *   rather than defaulting to true, so an unmeasured panel shows the full bar for one frame
 *   instead of flashing the rail on every mount.
 */
@Composable
fun rememberTabBarLayout(panelWidthPx: Int): TabBarLayout {
    val settings by WindowAppearanceSettingsManager.currentSettings.collectAsState()
    val density = LocalDensity.current
    val vertical = settings.tabBarPosition == TabBarPosition.LEFT
    val narrow = panelWidthPx > 0 && with(density) { panelWidthPx.toDp() } < TAB_BAR_AUTO_COLLAPSE_WIDTH
    return TabBarLayout(
        vertical = vertical,
        width =
            settings.tabBarVerticalWidth
                .coerceIn(TabBarVerticalWidthRange.start, TabBarVerticalWidthRange.endInclusive)
                .dp,
        narrow = narrow,
        // Two ways to reach the rail. `narrow` is the panel saying there is no room;
        // tabBarCollapsed is the user saying they would rather have the width. Either forces it.
        railShown = vertical && (narrow || settings.tabBarCollapsed),
        hoverExpand = settings.tabBarHoverExpand,
    )
}

/**
 * Everything a panel needs to run the vertical tab bar's collapse rail and its hover-reveal
 * drawer.
 *
 * Extracted from `BossMainPanel` because it is a self-contained state machine with six pieces of
 * state and four effects keeping them consistent, and none of it is about laying a panel out. The
 * decision itself lives in the pure `hoverRevealTarget`, which is where it is unit-tested; this is
 * the timing and the latches around it, ported from BossTerm's `TabbedTerminal`.
 *
 * Two independent reasons the drawer can be open, kept apart on purpose. [drawerOpen] is the
 * chevron on a panel too narrow to hold a full bar, and it installs a click-catcher; [revealed] is
 * the pointer resting on the rail, and it must NOT swallow the click that focuses the content
 * behind it, because the pointer leaving is what closes it.
 */
@Stable
class TabBarRevealState internal constructor(
    /** Hover source to attach to the in-flow rail. */
    val railHover: MutableInteractionSource,
    /** Hover source to attach to the revealed drawer. */
    val drawerHover: MutableInteractionSource,
) {
    /** Chevron-opened drawer on a narrow panel. */
    internal var drawerOpen by mutableStateOf(false)

    /** Hover-revealed drawer. */
    internal var revealed by mutableStateOf(false)

    /**
     * A dismissal sticks until the pointer leaves the sidebar, or the reveal slides straight back
     * in under the very cursor that just dismissed it.
     */
    internal var suppressed by mutableStateOf(false)

    /** The revealed bar has a context menu open; see `hoverRevealTarget`'s `drawerBusy`. */
    internal var busy by mutableStateOf(false)

    /** Whether a drawer should be on screen at all. */
    val drawerVisible: Boolean get() = drawerOpen || revealed

    /** Non-null only for the chevron-opened drawer, which is the one that gets a click-catcher. */
    val dismissOutside: (() -> Unit)? get() = if (drawerOpen) ({ drawerOpen = false }) else null

    /** Report an interaction that must keep the drawer composed past the pointer leaving. */
    fun setBusy(value: Boolean) {
        busy = value
    }

    /** Put the drawer away, whichever opened it, and latch against it sliding straight back. */
    fun dismiss(pointerInSidebar: Boolean) {
        drawerOpen = false
        revealed = false
        if (pointerInSidebar) suppressed = true
    }

    /** Open the drawer, for a panel too narrow for the in-flow bar to be anything but the rail. */
    fun openDrawer() {
        drawerOpen = true
    }
}

/**
 * [TabBarRevealState] wired to the settings and panel width that drive it.
 *
 * @param railShown the slim rail (not the full bar) is what is in the layout right now.
 * @param narrow the panel is below [TAB_BAR_AUTO_COLLAPSE_WIDTH], so the rail is forced and the
 *   chevron has nothing to give back except a drawer.
 * @param hoverExpand the `tabBarHoverExpand` setting.
 */
@Composable
fun rememberTabBarRevealState(
    railShown: Boolean,
    narrow: Boolean,
    hoverExpand: Boolean,
): TabBarRevealState {
    val state =
        remember {
            TabBarRevealState(
                railHover = MutableInteractionSource(),
                drawerHover = MutableInteractionSource(),
            )
        }

    val pointerOnRail by state.railHover.collectIsHoveredAsState()
    val pointerOnDrawer by state.drawerHover.collectIsHoveredAsState()
    val target =
        hoverRevealTarget(
            enabled = hoverExpand,
            railShown = railShown,
            pointerOnRail = pointerOnRail,
            pointerOnDrawer = pointerOnDrawer,
            drawerBusy = state.busy,
        )

    LaunchedEffect(target, state.suppressed) {
        val reveal = target && !state.suppressed
        if (reveal == state.revealed) return@LaunchedEffect
        delay(if (reveal) SIDEBAR_REVEAL_OPEN_DELAY_MS else SIDEBAR_REVEAL_CLOSE_DELAY_MS)
        state.revealed = reveal
    }

    // Re-arm on the pointer-LEFT edge, so a dismissal cannot be undone by the very hover that is
    // still sitting on the rail.
    val pointerInSidebar = pointerOnRail || pointerOnDrawer
    LaunchedEffect(pointerInSidebar) { if (!pointerInSidebar) state.suppressed = false }
    LaunchedEffect(narrow) { if (!narrow) state.drawerOpen = false }
    LaunchedEffect(railShown, hoverExpand) {
        if (!railShown || !hoverExpand) {
            state.revealed = false
            state.suppressed = false
            state.busy = false
        }
    }

    return state
}

/**
 * Whether the pointer is anywhere in the sidebar - the rail or the drawer over it.
 *
 * Read at the moment of a dismissal to decide whether the suppression latch is needed, which is
 * why it is a function on the state rather than a value the caller has to keep in step.
 */
@Composable
fun TabBarRevealState.pointerInSidebar(): Boolean {
    val onRail by railHover.collectIsHoveredAsState()
    val onDrawer by drawerHover.collectIsHoveredAsState()
    return onRail || onDrawer
}
