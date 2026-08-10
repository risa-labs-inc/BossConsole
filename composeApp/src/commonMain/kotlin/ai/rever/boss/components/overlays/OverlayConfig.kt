package ai.rever.boss.components.overlays

import ai.rever.boss.plugin.ui.BossOverlayHost
import ai.rever.boss.plugin.ui.BossPopupAnchoring
import ai.rever.boss.plugin.ui.LocalHeavyweightOverlays
import ai.rever.boss.plugin.ui.shouldRouteHeavyweight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.window.DialogProperties

/**
 * Routing for app overlays (context menus, dropdowns) so they can render correctly above a
 * HEAVYWEIGHT browser surface when JxBrowser runs in HARDWARE_ACCELERATED mode.
 *
 * On OFF_SCREEN the browser is lightweight, so ordinary Compose `Popup`s layer correctly and
 * [useHeavyweightPopups] stays false — nothing changes. When the desktop entry point detects
 * HARDWARE_ACCELERATED mode (see `JxBrowserConfig.renderingMode`) it sets [useHeavyweightPopups] =
 * true and injects the renderers below. This indirection keeps the platform-specific window code in
 * desktopMain while letting common UI opt into it without an expect/actual.
 *
 * Ported from BossConsoleLite, where HARDWARE was defaulted first and the Windows fleet hit the
 * regressions this fixes. Why the mode changed at all:
 * benchmarks/speedometer/win/WINDOWS.md.
 *
 * **MODALS live in [BossOverlayHost], not here.** Dynamic plugins draw dialogs too, and they cannot
 * see this module - they compile against `ai.rever.boss.plugin.ui`. So the modal registry moved down
 * into plugin-ui-core and the three modal-related properties below are now forwarding accessors, so
 * that the host and every plugin share one switch, one renderer and one popup counter rather than
 * two sets that can disagree. Popups, tooltips, HUDs and drag ghosts stay host-only.
 */
object OverlayConfig {
    /**
     * True when overlays must escape into heavyweight windows (HARDWARE_ACCELERATED browser).
     *
     * WRITE-ONCE at startup, before any composition, which is why a plain `@Volatile var` is
     * enough. Composables read it directly, and it is NOT snapshot state — flipping it at runtime
     * would not recompose anything that is already on screen. If a runtime toggle is ever wanted,
     * this has to become a `mutableStateOf` first.
     *
     * Forwards to [BossOverlayHost.useHeavyweightOverlays] so plugins see the same value.
     */
    var useHeavyweightPopups: Boolean
        get() = BossOverlayHost.useHeavyweightOverlays
        set(value) {
            BossOverlayHost.useHeavyweightOverlays = value
        }

    /**
     * Platform-injected heavyweight popup renderer. Renders [content] in a separate always-on-top
     * window anchored near [offset]. Null until injected by the desktop entry point; when null,
     * callers fall back to a normal Compose `Popup`.
     */
    var heavyweightPopup: (
        @Composable (
            onDismissRequest: () -> Unit,
            anchorInWindow: IntRect,
            anchoring: BossPopupAnchoring,
            offset: IntOffset,
            focusable: Boolean,
            content: @Composable () -> Unit,
        ) -> Unit
    )?
        get() = BossOverlayHost.popupRenderer
        set(value) {
            BossOverlayHost.popupRenderer = value
        }

    /**
     * Platform-injected heavyweight MODAL renderer — for centered, full-window dialogs rather than
     * cursor-anchored menus. Renders [content] in a separate always-on-top window matching the
     * parent window's bounds.
     *
     * Forwards to [BossOverlayHost.modalRenderer]; `BossDialog` is what reads it.
     */
    var heavyweightModal: (
        @Composable (
            properties: DialogProperties,
            onDismissRequest: () -> Unit,
            content: @Composable () -> Unit,
        ) -> Unit
    )?
        get() = BossOverlayHost.modalRenderer
        set(value) {
            BossOverlayHost.modalRenderer = value
        }

    /**
     * Platform-injected heavyweight TOOLTIP renderer — shows a short text tooltip in a tiny,
     * auto-sized, non-focusable native window near the cursor, so it layers above the heavyweight
     * browser surface (a lightweight Compose tooltip Popup renders behind it). Unlike
     * [heavyweightPopup] this is sized to its content and never steals focus or captures clicks,
     * which matters for transient hover tooltips. Null until injected; callers fall back to a normal
     * Compose tooltip Popup. Call [hideHeavyweightTooltip] when the hover ends.
     */
    var heavyweightTooltip: ((text: String) -> Unit)? = null

    /** Hide the tooltip shown by [heavyweightTooltip]. */
    var hideHeavyweightTooltip: (() -> Unit)? = null

    /**
     * Platform-injected heavyweight HUD renderer - a parent-sized, transparent, **non-focusable**
     * window that places [content] at [alignment] with no scrim and no dismissal of its own.
     *
     * Separate from [heavyweightModal] because a HUD is not a modal: the Ctrl+Tab switcher is up
     * only while a key is held, so the window must not take focus (that would cut off the very key
     * stream driving it) and must not dismiss on focus loss. Null until injected; callers fall back
     * to drawing in place.
     */
    var heavyweightHud: (
        @Composable (
            alignment: Alignment,
            content: @Composable () -> Unit,
        ) -> Unit
    )? = null

    /**
     * Platform-injected heavyweight GHOST renderer - a **content-sized**, non-focusable window of
     * [size] that follows the cursor, offset by a gap so the pointer never lands on it.
     *
     * The gap is load-bearing rather than cosmetic: the JVM has no portable click-through, so a
     * ghost sitting under the cursor would swallow the drag that is moving it. Null until injected;
     * callers fall back to drawing in place.
     */
    var heavyweightGhost: (
        @Composable (
            size: DpSize,
            content: @Composable () -> Unit,
        ) -> Unit
    )? = null

    /**
     * Platform-injected heavyweight CORNER renderer - a **content-sized**, non-focusable window
     * placed at [alignment] inside the parent, opening at `initialSize` until its content measures.
     *
     * Distinct from [heavyweightHud] purely by sizing, and that distinction is the point: a HUD is
     * parent-sized, which swallows every click beneath it, and that is only acceptable for something
     * up for as long as a key is held. Toasts linger for seconds while the user keeps working, so
     * their overlay must cover no more than itself. Null until injected; callers fall back to
     * drawing in place.
     *
     * `inset` shrinks the parent rectangle at its END and BOTTOM edges before the corner is
     * resolved, so a caller anchored to a sub-region of the window (rather than to the window
     * itself) can say so. See [OverlayCorner].
     */
    var heavyweightCorner: (
        @Composable (
            alignment: Alignment,
            initialSize: DpSize,
            inset: DpSize,
            content: @Composable () -> Unit,
        ) -> Unit
    )? = null

    /**
     * How many heavyweight POPUP windows are currently open.
     *
     * Exists so a heavyweight MODAL can tell "the user clicked away" from "a child overlay of mine
     * took focus". Both are separate always-on-top windows, so a dropdown opening inside a modal
     * fires the modal's `windowLostFocus` and would dismiss the whole dialog underneath it —
     * concretely, expanding the folder dropdown in `NewTabDialog` closed the dialog, and so did
     * the native directory picker behind "Browse…".
     *
     * Maintained by [ai.rever.boss.components.overlays.HeavyweightPopup] for its own lifetime and
     * read by the modal before it acts on focus loss. Only ever touched on the UI thread, so a
     * plain Int is enough.
     *
     * Forwards to [BossOverlayHost.openHeavyweightPopups]: a plugin's dialog can host a host-drawn
     * context menu, so the counter has to be the same one on both sides.
     */
    var openHeavyweightPopups: Int
        get() = BossOverlayHost.openHeavyweightPopups
        set(value) {
            BossOverlayHost.openHeavyweightPopups = value
        }
}

/**
 * Whether an overlay with a [hasRenderer] renderer should escape into its own window here.
 *
 * One helper for every non-modal overlay in the host, sharing [shouldRouteHeavyweight] with
 * `BossDialog` so the rule cannot drift between them. The [LocalHeavyweightOverlays] half is the
 * part that is easy to forget: without it a right-click menu or a status card opened from the
 * Settings window routes into a window sized to the MAIN window, and lands over the wrong one.
 */
@Composable
internal fun routeOverlayHeavyweight(hasRenderer: Boolean): Boolean =
    shouldRouteHeavyweight(
        useHeavyweightOverlays = OverlayConfig.useHeavyweightPopups,
        hasRenderer = hasRenderer,
        hostNeedsHeavyweight = LocalHeavyweightOverlays.current,
    )

/**
 * A transient status card (the Ctrl+Tab switcher) placed at [alignment], layered above the browser.
 *
 * Not a modal: it takes no focus, draws no scrim and has no dismissal of its own - it is shown for
 * exactly as long as the caller composes it. On the lightweight path that is a plain aligned `Box`
 * in the calling scope, which is what this used to be unconditionally.
 */
@Composable
fun BoxScope.OverlayHud(
    alignment: Alignment,
    content: @Composable () -> Unit,
) {
    val hw = OverlayConfig.heavyweightHud
    if (routeOverlayHeavyweight(hw != null) && hw != null) {
        hw(alignment) { content() }
    } else {
        Box(modifier = Modifier.align(alignment)) { content() }
    }
}

/**
 * A long-lived corner overlay (toast notifications) at [alignment], layered above the browser.
 *
 * Distinct from [OverlayHud] because it must not swallow clicks: a HUD covers the whole parent
 * window, which is fine for a keypress-length switcher and not for a toast that stays up for
 * seconds while the user keeps working. This one is sized to its content, so only the toast itself
 * is covered.
 *
 * [initialSize] is the size of the window before its content has been measured, so it must be a
 * generous UPPER bound - too small and the content measures clipped, then the overlay settles at
 * the clipped size. On the lightweight path it is unused, as the content sizes itself normally.
 *
 * [inset] exists because the two paths anchor to different things. The lightweight path aligns
 * inside the CALLER's `BoxScope`, but the heavyweight one is a separate window placed against the
 * parent window's whole content pane - so a caller that draws in a sub-region of the window (the
 * main content area, say, rather than over the sidebars and status bar) is correct on one path and
 * lands in the wrong corner on the other. Passing the sub-region's distance from the window's end
 * and bottom edges makes both agree. It is applied to the heavyweight path only, where it shrinks
 * the rectangle the corner is resolved inside; the overlay itself is not made any larger, so the
 * region it covers - and therefore the region whose clicks it swallows - is unchanged.
 */
@Composable
fun BoxScope.OverlayCorner(
    alignment: Alignment,
    initialSize: DpSize,
    inset: DpSize = DpSize.Zero,
    content: @Composable () -> Unit,
) {
    val hw = OverlayConfig.heavyweightCorner
    if (routeOverlayHeavyweight(hw != null) && hw != null) {
        hw(alignment, initialSize, inset) { content() }
    } else {
        Box(modifier = Modifier.align(alignment)) { content() }
    }
}

/**
 * Whether [OverlayCorner] would escape into its own window right here.
 *
 * Exists because [OverlayCorner]'s two paths do not accept the same description of where to sit.
 * The heavyweight one is placed against the whole content pane and so needs an `inset`; the
 * lightweight one aligns inside the caller's own `BoxScope`, where that same inset would be
 * double-counted and is therefore ignored. A caller whose spacing must survive both has to know
 * which it is getting - the alternative is spacing that silently disappears on one path, which is
 * exactly what happened to this cluster's corner margin.
 *
 * Asks the same question [OverlayCorner] does, in the same composition, so the two cannot disagree.
 */
@Composable
internal fun overlayCornerIsHeavyweight(): Boolean = routeOverlayHeavyweight(OverlayConfig.heavyweightCorner != null)

/**
 * A drag ghost of [size] following the pointer, layered above the browser.
 *
 * [windowOffset] positions it on the lightweight path, in the calling layout's coordinates, exactly
 * as the ghosts did before. The heavyweight path ignores it and reads the cursor directly: the
 * ghost's own window has to be placed in SCREEN coordinates, and converting from a Compose offset
 * means going through the content pane rather than the window (via the window it is off by the
 * title-bar height), which reading the cursor avoids entirely.
 */
@Composable
fun OverlayGhost(
    size: DpSize,
    windowOffset: () -> IntOffset,
    content: @Composable () -> Unit,
) {
    val hw = OverlayConfig.heavyweightGhost
    if (routeOverlayHeavyweight(hw != null) && hw != null) {
        hw(size) { content() }
    } else {
        Box(modifier = Modifier.offset { windowOffset() }) { content() }
    }
}
