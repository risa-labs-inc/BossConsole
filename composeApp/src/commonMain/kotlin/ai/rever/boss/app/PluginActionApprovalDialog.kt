package ai.rever.boss.app

import ai.rever.boss.components.dialogs.ConfirmationDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** Each distinct request gets a fresh arming interval, including an identical action. */
@Composable
internal fun PluginActionApprovalDialog(
    request: PendingPluginAction,
    pendingCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    key(request) {
        var armed by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(500)
            armed = true
        }
        ConfirmationDialog(
            title = "Run this plugin action? ($pendingCount pending)",
            message = pluginActionApprovalMessage(request),
            confirmText = "Run action",
            confirmEnabled = armed,
            onDismiss = onDismiss,
            onConfirm = { if (armed) onConfirm() },
        )
    }
}

/**
 * The prompt's body. Names the plugin handler and the action, and lists the
 * parameter KEYS the handler would receive — never their values, which are
 * attacker-chosen text that a prompt is not a safe place to render.
 */
internal fun pluginActionApprovalMessage(request: PendingPluginAction): String {
    val parameters =
        if (request.paramKeys.isEmpty()) {
            "no parameters"
        } else {
            "parameters: ${request.paramKeys.joinToString(", ")}"
        }
    return "BOSS was asked through a link to run a plugin action. It has not run. " +
        "Confirm only if you expected it:\n\n" +
        "plugin: ${request.handlerId}\n" +
        "action: ${request.action}\n" +
        parameters
}
