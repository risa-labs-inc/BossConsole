package ai.rever.boss.app

import ai.rever.boss.components.buttons.BossActionButton
import ai.rever.boss.components.buttons.QuickActionHints
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * Hard upper bound on the quick-actions overlay: its size before measurement, and the ceiling every
 * later measurement is taken against.
 *
 * A bound, not an estimate - content that would exceed it is CLIPPED. The real content is ~94x30dp:
 * three `BossActionButton`s at 28.dp square in `imageVector` mode, plus `space.xs` on each end of
 * the row and the 1.dp border. Kept close to that rather than round, because until measurement
 * lands this is also the region the overlay swallows clicks in - the same reason
 * `TOAST_OVERLAY_INITIAL_SIZE` gives for keeping itself no larger than it needs to be. The margin
 * is deliberately NOT in here; it rides in the inset (see [QUICK_ACTIONS_MARGIN]).
 */
private val QUICK_ACTIONS_OVERLAY_SIZE = DpSize(120.dp, 40.dp)

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
 * against the whole content pane - so without it the cluster sits over the status bar and the right
 * sidebar, which Windows focus-mode defaults leave visible.
 *
 * It is a **lambda, not a value**, so the state read lands in this composable's restart scope. The
 * scaffold builds its tree entirely out of `Box`/`Column`/`Row` content lambdas, which are inline
 * and so create no restart scope of their own: reading the inset there subscribes the whole
 * scaffold body to it, and it changes every frame of a 250ms sidebar reveal - the case the measured
 * inset exists to follow. Deferring the read costs nothing here, since this function is
 * non-skippable anyway (fresh lambdas each pass). Note the deferral only buys anything on the
 * heavyweight path: `OverlayCorner` takes a value, so the lightweight branch still reads it and
 * subscribes to something it does not use.
 */
@Composable
internal fun BoxScope.FocusModeQuickActions(
    visible: Boolean,
    inset: () -> DpSize,
    onShowSettings: () -> Unit,
    onShowSearch: () -> Unit,
    onSignOut: () -> Unit,
) {
    if (!visible) return

    if (!LocalWindowInfo.current.isWindowFocused) {
        Box(modifier = Modifier.align(Alignment.BottomEnd)) {
            QuickActions(QUICK_ACTIONS_MARGIN, onShowSettings, onShowSearch, onSignOut)
        }
        return
    }
    val measured = inset()
    OverlayCorner(
        alignment = Alignment.BottomEnd,
        initialSize = QUICK_ACTIONS_OVERLAY_SIZE,
        // The margin rides in the inset rather than in the content - see QUICK_ACTIONS_MARGIN.
        inset =
            DpSize(
                measured.width + QUICK_ACTIONS_MARGIN,
                measured.height + QUICK_ACTIONS_MARGIN,
            ),
    ) {
        QuickActions(margin = 0.dp, onShowSettings, onShowSearch, onSignOut)
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
) {
    val currentUser by AuthService.currentUser.collectAsState()

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
        Row(modifier = Modifier.padding(horizontal = BossTheme.space.xs)) {
            // Same order as BossTopRightBar, which is not only about muscle memory: it puts Sign
            // Out at the INNER end, so the destructive action is not the one at the very corner of
            // the window, where it is easiest to hit by accident.
            //
            // hintDirection = top throughout: the cluster sits on the bottom edge of the content
            // area, so a hint below it would be off the window on the lightweight path. (On the
            // heavyweight path BossActionButton routes hints to SwingTooltip, which places itself,
            // and this is ignored.)
            BossActionButton(
                imageVector = Icons.AutoMirrored.Outlined.Logout,
                text = "Sign Out",
                hintText = signOutHint(currentUser?.email),
                hintDirection = top,
                onClick = onSignOut,
            )
            BossActionButton(
                imageVector = Icons.Outlined.Search,
                text = "Search",
                hintText = QuickActionHints.SEARCH,
                hintDirection = top,
                onClick = onShowSearch,
            )
            BossActionButton(
                imageVector = Icons.Outlined.Settings,
                text = "Settings",
                hintText = QuickActionHints.SETTINGS,
                hintDirection = top,
                onClick = onShowSettings,
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
    val signedInAs = email?.takeIf { it.isNotBlank() } ?: return QuickActionHints.SIGN_OUT
    return "Sign out - $signedInAs"
}
