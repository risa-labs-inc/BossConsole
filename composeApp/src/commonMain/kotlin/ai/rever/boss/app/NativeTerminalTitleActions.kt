package ai.rever.boss.app

import ai.rever.boss.components.bars.horizontal.rememberMcpAccessMenu
import ai.rever.boss.components.bars.horizontal.rememberMcpActivityItem
import ai.rever.boss.mcp.McpToolRegistryImpl
import ai.rever.boss.plugin.ui.TerminalTitleBarBridge
import ai.rever.boss.window.NativeTitleBarAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key

@Composable
internal fun nativeTerminalTitleActions(windowId: String): List<NativeTitleBarAction> =
    TerminalTitleBarBridge.actions(windowId).map {
        key(it.id) {
            val action =
                NativeTitleBarAction(
                    id = "terminal_${it.id}",
                    label = it.label,
                    symbol = it.symbol.takeUnless { symbol -> symbol == "mcp" },
                    icon = if (it.symbol == "mcp") it.icon else null,
                    active = it.active,
                    onClick = it.onClick,
                )
            if (it.id == "mcp") nativeMcpMenu(action) else action
        }
    }

/** Extend the terminal's existing sharing button instead of rendering a second one. */
internal fun mergeNativeSharingActions(actions: List<NativeTitleBarAction>): List<NativeTitleBarAction> {
    val windowSharing = actions.firstOrNull { it.id == "sharing" }
    val terminalSharing = actions.firstOrNull { it.id == "terminal_sharing" }
    if (windowSharing == null || terminalSharing == null) return actions
    val terminalMenu =
        terminalSharing.menu ?: listOf(terminalSharing.copy(label = "Terminal sharing…"))
    val combined =
        terminalSharing.copy(
            label = "Sharing",
            active = terminalSharing.active || windowSharing.active,
            enabled = true,
            menu = terminalMenu + windowSharing.menu.orEmpty(),
            onClick = {},
        )
    return actions.mapNotNull {
        when (it.id) {
            "sharing" -> null
            "terminal_sharing" -> combined
            else -> it
        }
    }
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

/** Keep host permissions and activity beside the plugin's existing MCP server controls. */
@Composable
private fun nativeMcpMenu(server: NativeTitleBarAction): NativeTitleBarAction {
    val policy by McpToolRegistryImpl.policyEngine.config.collectAsState()
    val access = rememberMcpAccessMenu(policy)
    val activity = rememberMcpActivityItem()
    return server.copy(
        label = if (access.summary.yolo) "MCP: YOLO" else server.label,
        menu =
            buildList {
                add(server.copy(id = "mcp_server", label = "Server and connections…"))
                add(
                    NativeTitleBarAction(
                        "mcp_activity",
                        "Activity log… (${activity.text.removePrefix("MCP: ")})",
                        onClick = activity.onClick,
                    ),
                )
                access.items.filterNot { it.isDivider }.forEach { item ->
                    add(
                        NativeTitleBarAction(
                            "mcp_access_${item.text}",
                            item.text,
                            enabled = item.enabled,
                            onClick = item.onClick,
                        ),
                    )
                }
            },
        onClick = {},
    )
}
