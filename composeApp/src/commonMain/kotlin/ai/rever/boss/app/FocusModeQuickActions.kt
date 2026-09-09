package ai.rever.boss.app

import ai.rever.boss.components.buttons.BossActionButton
import ai.rever.boss.components.buttons.QuickActionHints
import ai.rever.boss.components.buttons.ToolboxButton
import ai.rever.boss.components.overlays.OverlayCorner
import ai.rever.boss.components.overlays.overlayCornerIsHeavyweight
import ai.rever.boss.focusmode.FocusModeEdge
import ai.rever.boss.focusmode.FocusModeSettings
import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.services.supabase.AuthService
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Surface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * Hard upper bound on the quick-actions overlay: its size before measurement, and the ceiling every
 * later measurement is taken against.
 *
 * A bound, not an estimate - content that would exceed it is CLIPPED. Three `BossActionButton`s at
 * 28.dp square in `imageVector` mode come to ~94x30dp with `space.xs` on each end of the row and
 * the 1.dp border. Kept close to that rather than round, because until measurement lands this is
 * also the region the overlay swallows clicks in - the same reason `TOAST_OVERLAY_INITIAL_SIZE`
 * gives for keeping itself no larger than it needs to be.
 *
 * Three actions is the common case, which is why it is this constant that carries the plain name:
 * the fourth button's width is asked for only when there IS a fourth button
 * ([QUICK_ACTIONS_OVERLAY_SIZE_WITH_LAUNCHER]), rather than reserved always, which would spend a
 * frame swallowing clicks 32dp wider than the cluster draws.
 *
 * The margin is deliberately NOT in here; it rides in the inset (see [QUICK_ACTIONS_MARGIN]).
 */
internal val QUICK_ACTIONS_OVERLAY_SIZE = DpSize(132.dp, 34.dp)

/**
 * The same bound with the tools launcher in the row, when both icon strips are gone.
 *
 * See `toolLauncherPlacement` for when that happens, and [QUICK_ACTIONS_OVERLAY_SIZE] for why the
 * two are separate rather than one number wide enough for both.
 */
internal val QUICK_ACTIONS_OVERLAY_SIZE_WITH_LAUNCHER = DpSize(164.dp, 34.dp)

/**
 * Gap between the cluster and the corner it sits in.
 *
 * Applied as *padding* on the lightweight path and as extra *inset* on the heavyweight one, and
 * that asymmetry is the whole point rather than an inconsistency. Padding inside the overlay window
 * is transparent but still eats clicks - there is no portable click-through - so a margin drawn
 * that way would put a dead band across the content area's bottom-right corner, which on some
 * platforms is the window's own resize hit area and is where a scrollbar's corner lands. Adding it
 * to the inset instead moves the whole window inward, so the region it swallows is exactly the
 * surface you can see.
 */
internal val QUICK_ACTIONS_MARGIN = 8.dp

/**
 * Whether the cluster belongs on screen, i.e. whether the top bar that owns Settings, Search and
 * Sign Out is absent.
 *
 * The two ways it can be absent are **not symmetric**, which is the whole shape of this predicate:
 *
 * - **[topBarHidden]** - switched off in `WindowAppearanceSettings`. Standing and unconditional, so
 *   it alone is enough. There is no hover that brings the bar back, so the cluster is needed for as
 *   long as the flag is set.
 * - **`settings.hides(TOP)`** - focus mode is clearing that edge. Transient, so it has to be paired
 *   with `!showTopBar`: focus mode hands the bar back on hover, and while it is up it owns its own
 *   three actions and the cluster must stand down.
 *
 * **Do not fold these into `topBarGone && !showTopBar`.** That reads well and is wrong, because
 * `showTopBar` is not "the bar is on screen" - it is `FocusModeEdgeRevealState.shown`, which
 * `EdgeRevealEffects` sets to `true` whenever `!settings.hides(edge)`, i.e. permanently whenever
 * focus mode is off. In the default configuration (focus mode off, user picks "Hide Top Bar") that
 * conjunction evaluates `true && !true` and yields false, so the bar is gone and nothing replaces
 * it - Sign Out is raised only from the top bar and from this cluster, and the native View menu has
 * no Sign Out item, so it becomes unreachable. Caught in review on #187 after tests asserted a
 * `showTopBar = false` the scaffold cannot produce in that configuration.
 *
 * The `!showTopBar` conjunct keeps its original job for the focus-mode half. `shown` starts false,
 * so on the FIRST composition of every window it is true whether or not focus mode is enabled;
 * pairing it with `hides(TOP)` is what stops the heavyweight always-on-top window being created and
 * immediately disposed on every window open for users who never turn focus mode on. Reaching this
 * through [topBarHidden] is not that flash: there the cluster is wanted from the first frame and
 * stays, so there is nothing to dispose.
 *
 * Pure and named because the alternative is a conjunction inlined in the scaffold that no test can
 * see, whose failure mode is a corner flash nothing else would notice. Same reason [signOutHint] is
 * out here.
 */
internal fun focusQuickActionsVisible(
    settings: FocusModeSettings,
    topBarHidden: Boolean,
    showTopBar: Boolean,
): Boolean = topBarHidden || (settings.hides(FocusModeEdge.TOP) && !showTopBar)

/** Where the host's own actions belong right now. Mutually exclusive by construction. */
internal enum class FocusQuickActionsPlacement {
    /** Nowhere: the top bar is up and still owns them. */
    NONE,

    /** The bottom of the right icon rail, as ordinary sidebar chrome. */
    RIGHT_RAIL,

    /**
     * A row at the foot of the vertical tab bar, under the split map.
     *
     * Preferred over [FLOATING] whenever that bar is on screen, for the reason the rail is: the
     * floating cluster is an always-on-top native window with no click-through, and the tab bar
     * already ends in host chrome with room under it.
     */
    TAB_BAR_FOOTER,

    /**
     * A column at the foot of the vertical tab bar's COLLAPSED rail, under the "+".
     *
     * Preferred over [FLOATING] for the reason [TAB_BAR_FOOTER] is: a rail is the same bar with
     * its labels taken away, so it is chrome the app draws anyway, and its bottom third was empty.
     * Collapsing the bar is a request for content width, and answering that with a native
     * always-on-top window parked in the content is the opposite of granting it.
     *
     * Its own placement rather than a flavour of [TAB_BAR_FOOTER] because the two share no layout
     * - the foot is a wrapping row at least 120dp wide, this is one icon per line down a strip as
     * narrow as 36dp - and because only ever one of them is on screen.
     */
    TAB_BAR_RAIL,

    /**
     * A row at the foot of the open RIGHT plugin panel's own column - see [hostActionsPanelEdge],
     * which names that column and rules the other two out.
     *
     * Reached only in TOP tab-bar position, which has no vertical bar to put anything in, and only
     * while the right panel is open and big enough to carry the row - see [panelFooterFitsColumn],
     * which is what keeps a sliver of a panel from growing 188dp of icons. The cluster is an
     * overlay that swallows clicks in the region
     * it covers, it sits in the content area's bottom-right corner, and that corner belongs to the
     * right panel whenever there is one - so in that configuration the actions move into that
     * panel's chrome and cover nothing.
     *
     * **The panel's foot, not a band across the content area.** A full-width row also fixes the
     * collision, and it looks like one: the window grows a strip of dead chrome the width of the
     * screen to hold four icons. Inside the panel they read as what they are, at the scale of the
     * thing they sit in - the same shape the vertical bar's foot has. That is also why a BOTTOM
     * panel does not host these: its column is the whole window's width, so its foot is that band.
     *
     * Deliberately NOT the answer for a bare TOP window as well. With no panel open the corner is
     * empty content, where the overlay costs nothing, and there is no panel to put a foot on.
     */
    PANEL_FOOTER,

    /** A floating cluster in the content area's bottom-right corner. */
    FLOATING,
}

/**
 * Which of the five renderings the actions get, once [focusQuickActionsVisible] says they are
 * needed at all.
 *
 * **Every piece of chrome the window already draws - or layout it can carve out of a panel -
 * is tried before the overlay is.** In order:
 * the right rail, the vertical tab bar's foot, that bar's collapsed rail, the open right panel's
 * foot, and only then the floating cluster. The cluster is an overlay over
 * live content - on the heavyweight path a native always-on-top window with no click-through - so
 * it is by some distance the most intrusive of them, and it is now reached only by a window with
 * no rail, no vertical bar and nothing in the corner for it to cover. Where the right sidebar is
 * on screen there is already a strip of icon chrome at the window's end edge with empty space at
 * the bottom of it, and four more icons there cost nothing and look like what they are.
 *
 * That is not a rare case, it is the **default one on Windows**: `defaultHidesSidebars` leaves both
 * sidebars up there precisely because hover-reveal cannot fire over a browser tab, while the top
 * bar is still cleared. Windows is also the platform the floating cluster was built for, so the
 * common configuration it was meant to serve is exactly the one that now gets the rail instead.
 *
 * **Decided from the settings, not from `showRightSidebar`.** The reveal flag starts false on every
 * window's first composition (see [focusQuickActionsVisible] for the same trap), so keying off it
 * would put one native always-on-top window on screen per window open before the effect flips it -
 * the flash the settings half of that predicate exists to prevent. It also keeps the answer stable:
 * a rail focus mode *does* clear is transient chrome the user hover-revealed, and moving the
 * cluster into it for two seconds and back out again would be worse than leaving it in the corner.
 * `hides(RIGHT)` is false exactly when the rail is permanent, which is the question being asked.
 *
 * [rightStripHidden] is the other way there can be no rail - switched off for good through
 * `WindowAppearanceSettings.showRightStrip`. Without it this would answer `RIGHT_RAIL` and hand the
 * three icons to a bar that is not composed, which is not a cosmetic slip: it is Sign Out rendered
 * nowhere. It reads as "permanent" for the same reason `hides(RIGHT)` does, so it belongs in the
 * same test rather than in the reveal flags.
 */
// Six inputs, and each one is a different way the answer changes: whether these are wanted at
// all, then each host that could take them in turn. Folding them into a holder would put every
// caller and every test through a builder to ask one question.
@Suppress("LongParameterList")
internal fun focusQuickActionsPlacement(
    settings: FocusModeSettings,
    topBarHidden: Boolean,
    rightStripHidden: Boolean,
    showTopBar: Boolean,
    /**
     * What the window's vertical tab bar can offer these actions: a foot under its split map, the
     * bottom of its collapsed rail, or nothing at all in TOP position. See [verticalBarHost],
     * which is where the three are told apart.
     *
     * Read from `WindowAppearanceSettings` and the bar's MEASURED width, so it needs none of the
     * care the reveal flags do - with the same known gap the traffic-light rule has, that a bar
     * which rails itself on a narrow window is decided during layout rather than in settings.
     */
    verticalBar: VerticalBarHost = VerticalBarHost.NONE,
    /**
     * Whether the RIGHT plugin panel is open AND its column can afford the row.
     *
     * The one input that is about the window's CONTENT rather than its chrome, and it is here for
     * one reason: the floating cluster has no click-through, so wherever it sits it takes that
     * corner away from whatever is under it. A right panel is what the bottom-right corner sits
     * on top of, it is something the user asked for and is looking at, and it comes with a foot
     * to put these in; empty content in the corner of a bare window is none of the three.
     *
     * The right panel and not "any panel" - see [hostActionsPanelEdge], which is where the left
     * and bottom columns are ruled out and why, and which also picks the column.
     *
     * **Both halves, because a panel narrow enough is not a home either.** A panel drags down to
     * about 20dp, where a row that wraps rather than clips stacks five buttons into 188dp and
     * takes it out of the plugin. [panelFooterFitsColumn] is that test, and
     * [PanelFooterHostActions] reports its answer back here out of layout, the same way
     * `onBarRailedChange` reports a bar that railed itself on a narrow window. A window whose
     * panel is too small keeps the cluster, which is what it had before this.
     */
    panelFootAvailable: Boolean = false,
): FocusQuickActionsPlacement =
    when {
        !focusQuickActionsVisible(settings, topBarHidden, showTopBar) -> FocusQuickActionsPlacement.NONE

        // Rail first, unchanged: where there is a right rail these have always gone in it, and it
        // is the least intrusive of the three.
        railExists(settings, rightStripHidden) -> FocusQuickActionsPlacement.RIGHT_RAIL

        // Then the tab bar's foot, which displaces only the floating cluster - it is chrome the app
        // already draws, where the cluster is a native always-on-top window with no click-through.
        verticalBar == VerticalBarHost.FOOT -> FocusQuickActionsPlacement.TAB_BAR_FOOTER

        // Then that same bar collapsed, whose rail has room at the bottom. This case used to fall
        // through to the cluster, which is how asking for MORE content width ended up putting an
        // overlay in the content.
        verticalBar == VerticalBarHost.RAIL -> FocusQuickActionsPlacement.TAB_BAR_RAIL

        // No vertical bar at all, so nothing above can host them. The right panel's foot while
        // that panel is open and big enough to hold one, the corner of the content otherwise. See
        // the two enum entries for why that split rather than one answer for both.
        panelFootAvailable -> FocusQuickActionsPlacement.PANEL_FOOTER

        else -> FocusQuickActionsPlacement.FLOATING
    }

/** Test tag of the cluster - see `FocusModeQuickActionsTest`. */
internal const val FOCUS_QUICK_ACTIONS_TAG = "focus-quick-actions"

/**
 * The three actions as composables the caller lays out, [FocusQuickActionsPlacement.RIGHT_RAIL]
 * flavour: sidebar-sized icons whose hints point into the window.
 *
 * Empty for every other placement, which is what makes this safe to call unconditionally - the list
 * is also what `BossRightSideBar` reserves rail height from, so an empty one reserves nothing and a
 * bar that is not hosting the actions is left exactly as it was.
 */
internal fun focusQuickActionsRail(
    placement: FocusQuickActionsPlacement,
    onShowSettings: () -> Unit,
    onShowSearch: () -> Unit,
    onSignOut: () -> Unit,
    toolbox: (@Composable (hintDirection: Panel, modifier: Modifier) -> Unit)? = null,
): List<@Composable () -> Unit> =
    focusQuickActionsFor(
        owner = FocusQuickActionsPlacement.RIGHT_RAIL,
        // Hints point INTO the window. The rail is against the window's end edge, so a hint laid
        // out to its right would be off screen - the same call the rail's own icons make through
        // `slot.opposite`.
        hintDirection = left,
        placement = placement,
        onShowSettings = onShowSettings,
        onShowSearch = onShowSearch,
        onSignOut = onSignOut,
        toolbox = toolbox,
    )

/** One rail icon, matching what `DraggableSidebarSection` gives the plugin icons above it. */
internal val SIDEBAR_ICON_SIZE = 32.dp

/**
 * How many rail rows to keep reserved for the quick actions, whether or not they are on screen at
 * this instant.
 *
 * Deliberately NOT `focusQuickActionsRail(...).size`, and the difference is the whole point. The
 * rendered list empties the moment the top bar is hover-revealed, and the rail's reserve feeds
 * `computeSlotIconLimits`, whose ADAPTIVE mode is the default - so a reserve that tracked the
 * rendered list would hand about three rows back to the plugin slots on every hover and take them
 * away again on every un-hover. On a crowded rail that pops icons out of the More menu and back in
 * each time the user reaches for the top bar, which is the common case on the Windows defaults this
 * placement is aimed at.
 *
 * So the budget is held for as long as focus mode *owns* the top bar, and only the icons come
 * and go. The cost is up to 145dp of rail that is briefly reserved and empty, which is invisible:
 * the slack lands in the weighted spacer, and the icons above it do not move.
 *
 * The two appearance flags enter for the same reason they enter [focusQuickActionsPlacement]: a top
 * bar hidden for good also hands these to the rail, and a right strip hidden for good means there
 * is no rail to reserve on. This has to stay in step with that function - `FocusQuickActionsPlacementTest`
 * pins the two against each other, because under-reserving pushes an icon off the bottom of the window.
 */
internal fun focusQuickActionsRailRows(
    settings: FocusModeSettings,
    topBarHidden: Boolean,
    rightStripHidden: Boolean,
): Int =
    // Deliberately does NOT take showTopBar, which is what holds the reserve steady across a
    // momentary hover-reveal - see above. It must still agree with focusQuickActionsPlacement about
    // everything else, or the rail reserves rows it never fills; `the reserve follows the appearance
    // flags exactly as the placement does` pins that.
    if ((topBarHidden || settings.hides(FocusModeEdge.TOP)) && railExists(settings, rightStripHidden)) {
        FOCUS_QUICK_ACTION_COUNT
    } else {
        0
    }

/**
 * Whether there is a right rail to put the actions on: focus mode is not clearing it and it is not
 * switched off in settings.
 *
 * Shared by [focusQuickActionsPlacement] and [focusQuickActionsRailRows], which must agree about it
 * or the rail reserves rows it never fills.
 */
private fun railExists(
    settings: FocusModeSettings,
    rightStripHidden: Boolean,
): Boolean = !settings.hides(FocusModeEdge.RIGHT) && !rightStripHidden

/**
 * How many actions the cluster has.
 *
 * A constant because [focusQuickActionsRailRows] has to answer without the callbacks needed to
 * build the buttons. `FocusQuickActionsPlacementTest` pins it against the rendered list, so the
 * reserve and the render cannot drift apart.
 */
internal const val FOCUS_QUICK_ACTION_COUNT = 4

/**
 * The host's own actions as separate composables, in the order every host wants them.
 *
 * One definition, five layouts, in the order Sign Out, Settings, Tools, Search.
 *
 * The **order carries the same intent on every axis**: Sign Out first, so the destructive action
 * is the one furthest from the window corner - leftmost in the floating row and in the tab bar's
 * footer, topmost in the bottom-anchored rail column - rather than the one sitting in it. Settings
 * and Tools sit together after it because both are "go and configure or open something", and
 * Search ends the row as the one that opens a field rather than a place. `BossTopRightBar` uses
 * the same order, which is where these live when the top bar is up.
 *
 * A list rather than a composable that lays them out, because the hosts disagree about more
 * than the axis: the rail has to reserve its own height from the *count* before it renders anything
 * (see `SidebarIconRail.bottomSectionHeight`), and a list is the only shape where the count and the
 * content cannot drift apart.
 */
@Suppress("LongParameterList")
internal fun focusQuickActionButtons(
    hintDirection: Panel,
    modifier: Modifier = Modifier,
    onShowSettings: () -> Unit,
    onShowSearch: () -> Unit,
    onSignOut: () -> Unit,
    toolbox: (@Composable (hintDirection: Panel, modifier: Modifier) -> Unit)? = null,
    /**
     * The tools launcher, when both icon strips are switched off and there is no strip to put it
     * in - see `toolLauncherPlacement`. Rendered between Settings and Search, so the two things
     * that open a place sit together and Sign Out stays furthest from the corner.
     *
     * It can never be non-null in the [FocusQuickActionsPlacement.RIGHT_RAIL] flavour: that
     * placement needs a right strip, and the launcher only reaches this group when BOTH strips are
     * gone. `FocusQuickActionsPlacementTest` pins that, because it is what keeps
     * [FOCUS_QUICK_ACTION_COUNT] - and so the rail's reserve - correct.
     */
    toolLauncher: (@Composable (hintDirection: Panel, modifier: Modifier) -> Unit)? = null,
): List<@Composable () -> Unit> =
    listOfNotNull(
        {
            // The only one of the three that reads auth state, and it reads it here rather than in
            // either host so that neither recomposes when the signed-in address changes.
            val currentUser by AuthService.currentUser.collectAsState()
            BossActionButton(
                imageVector = Icons.AutoMirrored.Outlined.Logout,
                text = "Sign Out",
                modifier = modifier,
                hintText = signOutHint(currentUser?.email),
                hintDirection = hintDirection,
                onClick = onSignOut,
            )
        },
        {
            BossActionButton(
                imageVector = Icons.Outlined.Settings,
                text = "Settings",
                modifier = modifier,
                hintText = QuickActionHints.SETTINGS,
                hintDirection = hintDirection,
                onClick = onShowSettings,
            )
        },
        // Directly after Settings: both are "go and configure the app", where Search and the tools
        // launcher open something.
        //
        // Wrapped so it takes this group's direction and modifier like every other button in it.
        // Null only if the Toolbox plugin is not registered at all, which a bundled system plugin
        // normally is - FOCUS_QUICK_ACTION_COUNT counts it, so the rail reserves a row for it.
        toolbox?.let { button -> { button(hintDirection, modifier) } },
        // Wrapped rather than passed straight through: the launcher takes this group's hint
        // direction and modifier like every other button in it. Handed a ready-made composable,
        // it kept whatever the scaffold had baked in - which pointed its hint off the bottom of
        // the window in both of the groups that hint upwards.
        toolLauncher?.let { launcher -> { launcher(hintDirection, modifier) } },
        {
            BossActionButton(
                imageVector = Icons.Outlined.Search,
                text = "Search",
                modifier = modifier,
                hintText = QuickActionHints.SEARCH,
                hintDirection = hintDirection,
                onClick = onShowSearch,
            )
        },
    )

/**
 * Settings, Search and Sign Out as a floating cluster pinned to the bottom-right of the main
 * content area, for when focus mode has cleared the top bar **and** the right sidebar with it.
 *
 * The fallback rendering, not the usual one: with the rail on screen the same three actions go at
 * the bottom of it instead, as ordinary sidebar chrome and with none of the machinery below. See
 * [focusQuickActionsPlacement] for which is chosen and why.
 *
 * These three live in `BossTopRightBar` and nowhere else, so clearing the top bar takes them with
 * it. The documented way back is to hover the top edge - and that is driven by Compose
 * `onPointerEvent` on an edge strip, which **cannot fire over a browser tab**: in
 * `HARDWARE_ACCELERATED` mode Chromium owns a foreign native window that composites over the
 * Compose scene rather than inside it, so the pointer never crosses the strip as far as Compose is
 * concerned. That is why `FocusModeSettings.defaultAutoReveal` turns hover-reveal off on Windows
 * out of the box, and it is the state this exists for: focus mode plus a browser tab, where these
 * actions are otherwise reachable only from the menu bar.
 *
 * Three things about the shape, all consequences of that same case:
 *
 *  - **It draws through [OverlayCorner], not in place.** A plain Compose overlay is behind the
 *    browser surface for exactly the same reason the hover strip is under it - present, invisible,
 *    unclickable. Drawing in place would leave this working everywhere except where it is needed.
 *  - **Nothing is composed when [visible] is false.** Load-bearing rather than an optimisation: the
 *    heavyweight overlay is a non-focusable always-on-top AWT window, the JVM has no portable
 *    click-through, so one composed unconditionally is a permanently dead region of this app and of
 *    whatever is in front of it. `ToastOverlay` guards the same way for the same reason.
 *  - **The buttons only raise callbacks.** The sign-out confirmation is a dialog, and a dialog
 *    composed inside a content-sized overlay window has nowhere to go; `BossAppScaffold` owns that
 *    state and draws the dialog in the main composition.
 *
 * **An unfocused window draws in place**, exactly as `ToastOverlay` does. The escape is only worth
 * anything while the user is looking at this window, and the overlay is always-on-top over every
 * other application too - so leaving one up while BOSS is in the background would put a dead click
 * region over whatever the user switched to. In place it may sit behind the browser surface, which
 * costs nothing: reaching it means focusing this window first, and by then it is a real overlay
 * again.
 *
 * That guard is load-bearing for a second reason, which is easy to miss when deciding it is too
 * cautious: `SettingsWindow` is a separate window this cluster's own Settings button opens, and the
 * guard is the only thing that stops an always-on-top overlay of the MAIN window sitting on top of
 * it. The same holds for a heavyweight modal, which is also a window and also takes focus.
 *
 * In-window dialogs need nothing extra, and deliberately get nothing: a lightweight `BossDialog`
 * falls back to Compose's own `Dialog`, a platform window ABOVE the composition, so the in-place
 * cluster is underneath it. An earlier revision listed the dialogs this cluster opens in [visible];
 * that enumeration would have had to grow with every dialog anyone added, and it was guarding
 * against something neither path does.
 *
 * [inset] is the content area's distance from the window's end and bottom edges. The lightweight
 * path aligns inside this `BoxScope` and needs nothing, but the heavyweight path places a window
 * against the whole content pane - so without it the cluster sits over the status bar, and over a
 * right sidebar the user has hover-revealed. A sidebar focus mode leaves up permanently no longer
 * reaches this composable at all (that is [FocusQuickActionsPlacement.RIGHT_RAIL]), but a hidden
 * one still animates in and out under the cluster, which is the case the measurement follows.
 *
 * It is a **lambda, not a value**, so the state read lands in this composable's restart scope. The
 * scaffold builds its tree entirely out of `Box`/`Column`/`Row` content lambdas, which are inline
 * and so create no restart scope of their own: reading the inset there subscribes the whole
 * scaffold body to it, and it changes every frame of a 250ms sidebar reveal - the case the measured
 * inset exists to follow. Deferring the read costs nothing here, since this function is
 * non-skippable anyway (fresh lambdas each pass). It is invoked only on the heavyweight branch,
 * which is the only one `OverlayCorner` applies an inset on - reading it above the branch would
 * subscribe the lightweight path to a value it ignores.
 */
@Composable
internal fun BoxScope.FocusModeQuickActions(
    visible: Boolean,
    inset: () -> DpSize,
    onShowSettings: () -> Unit,
    onShowSearch: () -> Unit,
    onSignOut: () -> Unit,
    toolbox: (@Composable (hintDirection: Panel, modifier: Modifier) -> Unit)? = null,
    /** The tools launcher, when both strips are gone - see `toolLauncherPlacement`. */
    toolLauncher: (@Composable (hintDirection: Panel, modifier: Modifier) -> Unit)? = null,
) {
    if (!visible) return

    if (!LocalWindowInfo.current.isWindowFocused) {
        Box(modifier = Modifier.align(Alignment.BottomEnd)) {
            QuickActions(
                QUICK_ACTIONS_MARGIN,
                onShowSettings,
                onShowSearch,
                onSignOut,
                toolbox,
                toolLauncher,
            )
        }
        return
    }
    // Which path OverlayCorner will take, asked before choosing where to put the margin. Its
    // lightweight branch aligns inside this BoxScope and ignores `inset` entirely, so folding the
    // margin into the inset unconditionally loses it there - the cluster ends up flush in the
    // corner, and jumps by 8dp whenever the window loses and regains focus. Reachable today via
    // BOSS_RENDERING_MODE=OFF_SCREEN, which is a supported escape hatch rather than a dead branch.
    val heavyweight = overlayCornerIsHeavyweight()
    OverlayCorner(
        alignment = Alignment.BottomEnd,
        initialSize =
            if (toolLauncher == null) QUICK_ACTIONS_OVERLAY_SIZE else QUICK_ACTIONS_OVERLAY_SIZE_WITH_LAUNCHER,
        // inset() is invoked INSIDE the branch, not above it. Read unconditionally, the lightweight
        // path subscribes to a value it then ignores and recomposes on every frame of a 250ms
        // sidebar animation - the exact cost the lambda parameter exists to avoid.
        inset =
            if (heavyweight) {
                inset().let { DpSize(it.width + QUICK_ACTIONS_MARGIN, it.height + QUICK_ACTIONS_MARGIN) }
            } else {
                DpSize.Zero
            },
    ) {
        // Margin as inset on the heavyweight path so it is not a dead band on the corner, and as
        // ordinary padding on the lightweight one, where nothing is swallowed and the inset would
        // be double-counted. See QUICK_ACTIONS_MARGIN.
        QuickActions(
            margin = if (heavyweight) 0.dp else QUICK_ACTIONS_MARGIN,
            onShowSettings,
            onShowSearch,
            onSignOut,
            toolbox,
            toolLauncher,
        )
    }
}

/**
 * Reports this layout's distance from the window's END and BOTTOM edges, in Dp, whenever it moves.
 *
 * This is the [FocusModeQuickActions] `inset`, measured rather than derived. Deriving it from the
 * reveal flags would need this file to know each bar's thickness and to re-derive it mid-animation;
 * asking the layout is one question with one answer, and it stays right when a sidebar is resized.
 *
 * Two details that are easy to get subtly wrong, and silently:
 *
 *  - **Against the ROOT, not the parent.** The `Row` this sits in already excludes the bottom bar,
 *    so `positionInParent` would report a bottom inset of zero while the bar is right there.
 *  - **px divided by density, not raw px.** `HeavyweightCorner` places its window in AWT logical
 *    units and converts its own measurements the same way. Passing px straight through compiles,
 *    passes every gate, and puts the overlay off by the display's scale factor.
 */
internal fun Modifier.reportContentInset(
    density: Float,
    onInset: (DpSize) -> Unit,
): Modifier =
    onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInRoot()
        val root = coordinates.findRootCoordinates().size
        onInset(
            DpSize(
                // Floored at the source as well as inside `insetBounds`. That one only guards the
                // other direction, so a negative component would GROW the region and place the
                // overlay outside the content pane - the failure `cornerPosition`'s floor prevents,
                // reintroduced a layer up. `boundsInRoot` clips to the root so it cannot go
                // negative today; this costs nothing and stops that being load-bearing.
                ((root.width - bounds.right).coerceAtLeast(0f) / density).dp,
                ((root.height - bounds.bottom).coerceAtLeast(0f) / density).dp,
            ),
        )
    }

/**
 * The cluster itself, identical on both paths apart from [margin]: the heavyweight path passes
 * zero and carries the gap in its inset instead, because padding inside that window would be a
 * transparent strip that still swallows clicks. See [QUICK_ACTIONS_MARGIN].
 */
@Composable
private fun QuickActions(
    margin: Dp,
    onShowSettings: () -> Unit,
    onShowSearch: () -> Unit,
    onSignOut: () -> Unit,
    toolbox: (@Composable (hintDirection: Panel, modifier: Modifier) -> Unit)? = null,
    toolLauncher: (@Composable (hintDirection: Panel, modifier: Modifier) -> Unit)?,
) {
    Surface(
        modifier =
            Modifier
                .padding(margin)
                .border(1.dp, BossTheme.colors.line, BossTheme.radius.cardShape)
                .testTag(FOCUS_QUICK_ACTIONS_TAG),
        color = BossTheme.colors.raised,
        shape = BossTheme.radius.cardShape,
        // No elevation, deliberately. The overlay window shrinks to the measured content, so a
        // drop shadow would fall outside it and simply vanish on the heavyweight path while
        // showing on the lightweight one - the two paths have to look the same. The hairline
        // border is what separates the cluster from the content underneath.
    ) {
        // Centred, not top-aligned. A child even slightly taller than the rest used to hang its
        // glyph below theirs rather than sitting level with them.
        Row(
            modifier = Modifier.padding(horizontal = BossTheme.space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // hintDirection = top throughout: the cluster sits on the bottom edge of the content
            // area, so a hint below it would be off the window on the lightweight path. (On the
            // heavyweight path BossActionButton routes hints to SwingTooltip, which places itself,
            // and this is ignored.)
            focusQuickActionButtons(
                hintDirection = top,
                onShowSettings = onShowSettings,
                toolbox = toolbox,
                onShowSearch = onShowSearch,
                onSignOut = onSignOut,
                toolLauncher = toolLauncher,
            ).forEach { action -> action() }
        }
    }
}

/**
 * Hover hint for the sign-out button, naming [email] when there is one.
 *
 * The top bar shows the signed-in address next to this button as plain text; the cluster is
 * icon-only, so the hint is where that identity goes instead. Which account is about to be signed
 * out is worth confirming before clicking, not after.
 *
 * Pure, and separate, because the hint itself is only reachable through a 500ms hover that a UI
 * test cannot see without driving the clock.
 */
internal fun signOutHint(email: String?): String {
    val signedInAs = email?.takeIf { it.isNotBlank() } ?: return QuickActionHints.SIGN_OUT
    return "Sign out - $signedInAs"
}
