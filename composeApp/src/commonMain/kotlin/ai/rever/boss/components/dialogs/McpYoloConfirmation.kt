package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpToolRegistryImpl
import ai.rever.boss.mcp.McpYoloPrompt
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/**
 * The one confirmation for turning YOLO mode on, whichever entry point asked (see
 * [McpYoloPrompt]). Composed by every window's `BossAppDialogs` and shown only in the window that
 * asked, so it works with the bottom bar hidden.
 */
@Composable
fun McpYoloConfirmation(windowId: String) {
    val requestedBy by McpYoloPrompt.requestedBy.collectAsState()
    if (requestedBy != windowId) return
    val scope = rememberCoroutineScope()
    ConfirmationDialog(
        title = "Turn on YOLO mode?",
        message = MCP_YOLO_CONFIRMATION_MESSAGE,
        icon = Icons.Outlined.GppMaybe,
        iconTint = BossTheme.colors.alert,
        confirmText = "Turn on for this session",
        onDismiss = McpYoloPrompt::dismiss,
        onConfirm = {
            McpYoloPrompt.dismiss()
            scope.launch { McpToolRegistryImpl.setYoloMode(true) }
        },
    )
}

internal const val MCP_YOLO_CONFIRMATION_MESSAGE =
    "Every MCP tool call that would normally ask for approval will run without asking, from " +
        "every agent and plugin, including tools added later and high-risk ones such as shell " +
        "commands.\n\n" +
        "Still enforced: tools and plugins you set to Always deny, the approval prompt for " +
        "calls that use a vault secret, the kill switch, and your role's permissions. Every " +
        "call is still recorded in the MCP activity log as " +
        "\"Yolo allowed\", and so is turning this on and off.\n\n" +
        "Shown as \"MCP: YOLO\" in the bottom bar and as a checked \"MCP YOLO Mode\" in the " +
        "Tools menu. Lasts until you turn it off or quit BOSS."
