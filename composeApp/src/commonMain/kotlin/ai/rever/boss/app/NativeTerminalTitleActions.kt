package ai.rever.boss.app

import ai.rever.boss.plugin.ui.TerminalTitleBarBridge
import ai.rever.boss.window.NativeTitleBarAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

@Composable
internal fun nativeTerminalTitleActions(windowId: String): List<NativeTitleBarAction> =
    TerminalTitleBarBridge.actions(windowId).map {
        NativeTitleBarAction(
            id = "terminal_${it.id}",
            label = it.label,
            symbol = it.symbol.takeUnless { symbol -> symbol == "mcp" },
            icon = if (it.symbol == "mcp") it.icon else null,
            active = it.active,
            onClick = it.onClick,
        )
    }

@Composable
internal fun NativeTerminalHostAvailability(
    windowId: String,
    ready: Boolean,
) {
    TerminalTitleBarBridge.Content(windowId)
    DisposableEffect(windowId, ready) {
        TerminalTitleBarBridge.hostWindow(windowId, ready)
        onDispose { TerminalTitleBarBridge.hostWindow(windowId, false) }
    }
}
