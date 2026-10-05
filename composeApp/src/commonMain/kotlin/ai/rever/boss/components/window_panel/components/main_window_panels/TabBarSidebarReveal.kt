package ai.rever.boss.components.window_panel.components.main_window_panels

import androidx.compose.ui.unit.IntRect

/**
 * Hover-reveal for the collapsed vertical tab bar: when the bar is down to its slim icon rail -
 * either forced by a narrow panel ([TAB_BAR_AUTO_COLLAPSE_WIDTH]) or collapsed with the chevron -
 * reaching or crossing its left edge reveals the full bar as an overlay drawer. It remains
 * open through a 100dp margin beyond its right edge. Gated by `tabBarHoverExpand`.
 *
 * The timing lives in a `LaunchedEffect` in `BossMainPanel`; the decision itself is kept pure
 * here so it can be unit-tested. Ported from BossTerm's `tabs/SidebarHoverReveal.kt`, where the
 * same three-way vote (rail hover, drawer hover, busy latch) settled after several rounds of
 * the drawer retracting out from under an interaction.
 */

/**
 * Brief grace after leaving the 100dp retention margin, so a transient native cursor sample
 * does not dispose an interaction.
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

/** Exact edge activation, with crossing detection for cursor movements that skip the edge. */
internal fun pointerReachesSidebarEdge(x: Int, y: Int, previousX: Int?, region: IntRect): Boolean =
    y >= region.top && y < region.bottom &&
        (x == region.left || (previousX != null &&
            ((previousX > region.left && x < region.left) ||
                (previousX < region.left && x > region.left))))

/** Retention is horizontal only: leaving through the left edge keeps the reveal open. */
internal fun pointerWithinSidebarMargin(x: Int, region: IntRect, sidebarWidthDp: Float): Boolean =
    x <= region.left + sidebarWidthDp + 100f
