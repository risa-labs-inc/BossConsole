package ai.rever.boss.components.window_panel.components.main_window_panels

/**
 * Hover-reveal for the collapsed vertical tab bar: when the bar is down to its slim icon rail -
 * either forced by a narrow panel ([TAB_BAR_AUTO_COLLAPSE_WIDTH]) or collapsed with the chevron -
 * resting the pointer on the rail slides the full bar in as an overlay drawer, which retracts
 * once the pointer leaves. Gated by the `tabBarHoverExpand` setting (on by default).
 *
 * The timing lives in a `LaunchedEffect` in `BossMainPanel`; the decision itself is kept pure
 * here so it can be unit-tested. Ported from BossTerm's `tabs/SidebarHoverReveal.kt`, where the
 * same three-way vote (rail hover, drawer hover, busy latch) settled after several rounds of
 * the drawer retracting out from under an interaction.
 */

/** Pointer rest time before the collapsed rail reveals the full bar. */
internal const val SIDEBAR_REVEAL_OPEN_DELAY_MS = 150L

/**
 * Grace period after the pointer leaves before the reveal retracts. Must exceed
 * [SIDEBAR_REVEAL_OPEN_DELAY_MS]: it covers the rail-to-drawer handoff (the drawer slides over
 * the rail, so hover moves between two different nodes - and under HARDWARE it moves between two
 * different WINDOWS, which is slower still) and brief excursions past the drawer's edge.
 */
internal const val SIDEBAR_REVEAL_CLOSE_DELAY_MS = 250L

/**
 * Whether the hover drawer should be revealed.
 *
 * @param enabled the `tabBarHoverExpand` setting.
 * @param railShown true while the slim rail (not the full bar) is what's in the layout.
 * @param pointerOnRail pointer is over the rail.
 * @param pointerOnDrawer pointer is over the revealed drawer - true during the handoff, and what
 *   keeps the drawer open while the user reaches for a tab.
 * @param drawerBusy the revealed bar has an interaction in flight that outlives a click - an open
 *   context menu, or a tab drag. Retracting would dispose the composition that owns it, so hover
 *   alone must not decide. Without this, right-clicking a tab in the drawer and moving the
 *   pointer onto the menu (which is its own popup, not the drawer) drops the menu.
 */
internal fun hoverRevealTarget(
    enabled: Boolean,
    railShown: Boolean,
    pointerOnRail: Boolean,
    pointerOnDrawer: Boolean,
    drawerBusy: Boolean = false,
): Boolean = enabled && railShown && (pointerOnRail || pointerOnDrawer || drawerBusy)
