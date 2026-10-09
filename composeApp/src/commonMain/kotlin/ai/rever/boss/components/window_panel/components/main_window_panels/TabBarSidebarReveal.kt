package ai.rever.boss.components.window_panel.components.main_window_panels

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection

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
 * Forty milliseconds after leaving the retention margin covers more than two 16ms cursor samples.
 * One transient outside sample therefore cannot close the drawer before the next sample recovers.
 */
internal const val SIDEBAR_REVEAL_CLOSE_DELAY_MS = 40L
internal const val SIDEBAR_POINTER_SAMPLE_INTERVAL_MS = 16L

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

/**
 * Whether the native edge tracker runs at all. Off in fullscreen, as the edge reveal has been since
 * #1753: #1828 moved activation from the hover strip (which still checks fullscreen) to this tracker,
 * which did not, so a fullscreen window opened the drawer whenever the pointer touched its left edge.
 */
internal fun edgeRevealTracking(
    railShown: Boolean,
    hoverExpand: Boolean,
    fullscreen: Boolean,
): Boolean = railShown && hoverExpand && !fullscreen

/** Exact edge activation, with crossing detection for cursor movements that skip the edge. */
internal fun pointerReachesSidebarEdge(
    x: Int,
    y: Int,
    previousX: Int?,
    region: IntRect,
    direction: LayoutDirection = LayoutDirection.Ltr,
): Boolean {
    val edge = if (direction == LayoutDirection.Ltr) region.left else region.right - 1
    return y >= region.top && y < region.bottom &&
        (
            x == edge || (
                previousX != null &&
                    (
                        (previousX > edge && x < edge) ||
                            (previousX < edge && x > edge)
                    )
            )
        )
}

/** Retention is horizontal only: leaving through the left edge keeps the reveal open. */
internal fun pointerWithinSidebarMargin(
    x: Int,
    region: IntRect,
    sidebarWidthDp: Float,
    direction: LayoutDirection = LayoutDirection.Ltr,
): Boolean =
    if (direction == LayoutDirection.Ltr) {
        x <= region.left + sidebarWidthDp + 100f
    } else {
        x >= region.right - sidebarWidthDp - 100f
    }
