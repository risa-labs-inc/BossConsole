package ai.rever.boss.components.overlays

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * Routing for app overlays (context menus, dropdowns) so they can render correctly above a
 * HEAVYWEIGHT browser surface when JxBrowser runs in HARDWARE_ACCELERATED mode.
 *
 * On OFF_SCREEN (the macOS/Linux default) the browser is lightweight, so ordinary Compose `Popup`s
 * layer correctly and [useHeavyweightPopups] stays false — nothing changes. When the desktop entry
 * point detects HARDWARE_ACCELERATED mode (the Windows default, see
 * `JxBrowserConfig.renderingMode`) it sets [useHeavyweightPopups] = true and injects the renderers
 * below. This indirection keeps the platform-specific window code in desktopMain while letting
 * common UI opt into it without an expect/actual.
 *
 * Ported from BossConsoleLite, where HARDWARE was defaulted first and the Windows fleet hit the
 * regressions this fixes. Why the mode changed at all:
 * benchmarks/speedometer/win/WINDOWS.md.
 */
object OverlayConfig {
    /** True when overlays must escape into heavyweight windows (HARDWARE_ACCELERATED browser). */
    @Volatile
    var useHeavyweightPopups: Boolean = false

    /**
     * Platform-injected heavyweight popup renderer. Renders [content] in a separate always-on-top
     * window anchored near [offset]. Null until injected by the desktop entry point; when null,
     * callers fall back to a normal Compose `Popup`.
     */
    var heavyweightPopup: (
        @Composable (
            onDismissRequest: () -> Unit,
            offset: IntOffset,
            focusable: Boolean,
            content: @Composable () -> Unit,
        ) -> Unit
    )? = null

    /**
     * Platform-injected heavyweight MODAL renderer — for centered, full-window dialogs (e.g. the
     * new-tab dialog) rather than cursor-anchored menus. Renders [content] in a separate
     * always-on-top window matching the parent window's bounds. Null until injected; callers fall
     * back to a normal centered Popup.
     */
    var heavyweightModal: (
        @Composable (
            onDismissRequest: () -> Unit,
            content: @Composable () -> Unit,
        ) -> Unit
    )? = null

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
}

/**
 * A modal overlay (full-window scrim + centered content) that layers correctly above the browser.
 * In HARDWARE_ACCELERATED mode (useHeavyweightPopups) it routes through the injected heavyweight
 * window; otherwise it's an ordinary centered Popup. Drop-in replacement for a centered modal
 * Popup — [content] keeps drawing its own scrim/centering exactly as before.
 */
@Composable
fun OverlayModal(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    val hw = OverlayConfig.heavyweightModal
    if (OverlayConfig.useHeavyweightPopups && hw != null) {
        hw(onDismissRequest, content)
    } else {
        Popup(
            alignment = Alignment.Center,
            onDismissRequest = onDismissRequest,
            properties = PopupProperties(focusable = true),
        ) {
            content()
        }
    }
}
