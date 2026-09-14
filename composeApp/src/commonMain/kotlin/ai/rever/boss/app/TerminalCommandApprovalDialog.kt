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

/** Each distinct request gets a fresh arming interval, including identical command text. */
@Composable
internal fun TerminalCommandApprovalDialog(
    request: PendingTerminalCommand,
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
            title = "Run this command? ($pendingCount pending)",
            message =
                "BOSS was asked from outside the app to run a command in a new terminal tab. " +
                    "It has not run. Confirm only if you recognise it:\n\n${request.command}",
            confirmText = "Run command",
            confirmEnabled = armed,
            onDismiss = onDismiss,
            onConfirm = { if (armed) onConfirm() },
        )
    }
}
