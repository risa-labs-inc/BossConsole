package ai.rever.boss.window

import ai.rever.boss.plugin.browser.LocalAwtWindow
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.theme.LocalWindowGlass
import ai.rever.boss.theme.sidebarGlassEnabled
import ai.rever.boss.utils.SystemUtils
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** The system capture indicator can change without a Compose state update. */
@Composable
private fun ObserveSharingWindowControls(
    controller: MacSidebarToolbar?,
    sharing: Boolean,
) {
    LaunchedEffect(controller, sharing) {
        while (sharing && controller != null) {
            controller.refreshSharingWindowControls()
            delay(250)
        }
    }
}

/** True only after AppKit installed and measured the toolbar; callers keep their fallback until then. */
@Composable
internal actual fun NativeSidebarTitleBar(
    title: String,
    actions: List<NativeTitleBarAction>,
    sharing: Boolean,
): Boolean {
    val currentWindow = LocalAwtWindow.current as? ComposeWindow
    val window = currentWindow?.takeIf { SystemUtils.isMacOS } ?: return false
    val currentActions by rememberUpdatedState(actions)
    var controller by remember(window) { mutableStateOf<MacSidebarToolbar?>(null) }
    var headerHeight by remember(window) { mutableStateOf<Double?>(null) }
    var remoteMenu by remember(window) { mutableStateOf<NativeToolbarMenuRequest?>(null) }
    LaunchedEffect(window) {
        while (!window.isShowing || window.windowHandle == 0L) delay(16)
        controller =
            MacSidebarToolbar(
                window.windowHandle,
                { headerHeight = it },
                onAction = { id ->
                    currentActions
                        .flatMap { listOf(it) + it.menu.orEmpty() + it.contextMenu }
                        .find { it.id == id }
                        ?.onClick
                        ?.invoke()
                },
                onRemoteMenu = { remoteMenu = it },
            )
    }
    val currentController = controller
    NativeBrowserFieldHosting(currentController, actions, headerHeight != null)
    NativeAddressSuggestions(currentController, actions)
    if (sharing) remoteMenu?.let { NativeRemoteToolbarMenu(it) { remoteMenu = null } }
    LaunchedEffect(sharing) { if (!sharing) remoteMenu = null }
    ObserveSharingWindowControls(currentController, sharing)
    DisposableEffect(currentController) { onDispose { currentController?.close() } }
    val background =
        (if (sidebarGlassEnabled) BossTheme.colors.panel else BossTheme.colors.raised)
            .copy(alpha = LocalWindowGlass.current.chromeOpacity)
    val icons =
        actions
            .mapNotNull { action ->
                action.textInput?.favicon?.let { action.id to rememberNativeFavicon(it) }
                    ?: action.icon?.let { action.id to rememberNativeToolbarIcon(it) }
            }.toMap()
    val glass = LocalWindowGlass.current
    // Compose owns the two glass fills; an NSWindow color would add a third layer.
    val nativeBackground = if (glass.installed) Color.Transparent else background
    SideEffect {
        currentController?.remoteInput?.enabled = sharing
        currentController?.update(title, actions, background.luminance() < 0.5f, nativeBackground.toArgb(), icons)
    }
    headerHeight?.let { GlassTitleBarInset(it, background, actions.firstOrNull { action -> action.id == "sidebar" }) }
    return headerHeight != null
}

/** Two sibling fills: the sidebar extends upward; content continues through the toolbar. */
@Composable
private fun GlassTitleBarInset(
    height: Double,
    background: Color,
    sidebar: NativeTitleBarAction?,
) {
    val glass = LocalWindowGlass.current
    val contentFill = if (glass.coverage == "window") Color.Transparent else BossTheme.colors.ink
    val separateSidebar = sidebar?.takeIf { !it.active && it.sidebarWidth > 0f }
    Spacer(
        Modifier.fillMaxWidth().height(height.toFloat().dp).drawBehind {
            if (!glass.installed) {
                drawRect(background)
            } else {
                val left =
                    separateSidebar
                        ?.sidebarLeading
                        ?.dp
                        ?.toPx()
                        ?.coerceIn(0f, size.width) ?: 0f
                val width = separateSidebar?.sidebarWidth?.dp?.toPx() ?: 0f
                val right = (left + width).coerceIn(left, size.width)
                if (left > 0f) drawRect(contentFill, size = Size(left, size.height))
                drawRect(contentFill, topLeft = Offset(right, 0f), size = Size(size.width - right, size.height))
            }
        },
    )
}

/** Release the plugin fallback only after the native field exists, and restore it on disposal. */
@Composable
private fun NativeBrowserFieldHosting(
    controller: MacSidebarToolbar?,
    actions: List<NativeTitleBarAction>,
    ready: Boolean,
) {
    val input = actions.firstOrNull { it.textInput != null }?.textInput
    DisposableEffect(controller, input?.identity, ready) {
        if (ready && controller != null) input?.onHosted { controller.focusAddress() }
        onDispose { input?.onHosted(null) }
    }
}
