package ai.rever.boss.app

import ai.rever.boss.components.buttons.BossActionButton
import ai.rever.boss.components.overlays.OverlayCorner
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.services.supabase.AuthService
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * Hard upper bound on the quick-actions overlay: its size before measurement, and the ceiling every
 * later measurement is taken against.
 *
 * A bound, not an estimate - content that would exceed it is CLIPPED. Three `BossActionButton`s in
 * `imageVector` mode are 28.dp square each, plus the row's padding and the surface border, so this
 * clears the real content comfortably while staying small enough that the dead click region it
 * defines stays small too.
 */
private val QUICK_ACTIONS_OVERLAY_SIZE = DpSize(160.dp, 56.dp)

/** Test tag of the cluster - see `FocusModeQuickActionsTest`. */
internal const val FOCUS_QUICK_ACTIONS_TAG = "focus-quick-actions"

/**
 * Settings, Search and Sign Out, pinned to the bottom-right of the main content area while focus
 * mode has the top bar cleared.
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
 * [inset] is the content area's distance from the window's end and bottom edges. The lightweight
 * path aligns inside this `BoxScope` and needs nothing, but the heavyweight path places a window
 * against the whole content pane - so without it the cluster sits over the status bar and the right
 * sidebar, which Windows focus-mode defaults leave visible.
 */
@Composable
internal fun BoxScope.FocusModeQuickActions(
    visible: Boolean,
    inset: DpSize,
    onShowSettings: () -> Unit,
    onShowSearch: () -> Unit,
    onSignOut: () -> Unit,
) {
    if (!visible) return

    if (!LocalWindowInfo.current.isWindowFocused) {
        Box(modifier = Modifier.align(Alignment.BottomEnd)) {
            QuickActions(onShowSettings, onShowSearch, onSignOut)
        }
        return
    }
    OverlayCorner(
        alignment = Alignment.BottomEnd,
        initialSize = QUICK_ACTIONS_OVERLAY_SIZE,
        inset = inset,
    ) {
        QuickActions(onShowSettings, onShowSearch, onSignOut)
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
                ((root.width - bounds.right) / density).dp,
                ((root.height - bounds.bottom) / density).dp,
            ),
        )
    }

/** The cluster itself, identical on both paths - only where it is drawn differs. */
@Composable
private fun QuickActions(
    onShowSettings: () -> Unit,
    onShowSearch: () -> Unit,
    onSignOut: () -> Unit,
) {
    val currentUser by AuthService.currentUser.collectAsState()

    Surface(
        modifier =
            Modifier
                .padding(BossTheme.space.sm)
                .border(1.dp, BossTheme.colors.line, BossTheme.radius.cardShape)
                .testTag(FOCUS_QUICK_ACTIONS_TAG),
        color = BossTheme.colors.raised,
        shape = BossTheme.radius.cardShape,
        elevation = BossTheme.elevation.popover,
    ) {
        Row(modifier = Modifier.padding(horizontal = BossTheme.space.xs)) {
            // hintDirection = top throughout: the cluster sits on the bottom edge of the content
            // area, so a hint below it would be off the window on the lightweight path. (On the
            // heavyweight path BossActionButton routes hints to SwingTooltip, which places itself,
            // and this is ignored.)
            BossActionButton(
                imageVector = Icons.Outlined.Settings,
                text = "Settings",
                hintText = "Configure application settings",
                hintDirection = top,
                onClick = onShowSettings,
            )
            BossActionButton(
                imageVector = Icons.Outlined.Search,
                text = "Search",
                hintText = "Search files, tabs, bookmarks (⇧⇧)",
                hintDirection = top,
                onClick = onShowSearch,
            )
            BossActionButton(
                imageVector = Icons.AutoMirrored.Outlined.Logout,
                text = "Sign Out",
                hintText = signOutHint(currentUser?.email),
                hintDirection = top,
                onClick = onSignOut,
            )
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
    val signedInAs = email?.takeIf { it.isNotBlank() } ?: return "Sign out of your account"
    return "Sign out - $signedInAs"
}
