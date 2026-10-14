package ai.rever.boss.keymap

import ai.rever.boss.components.bars.horizontal.StatusMessageManager
import ai.rever.boss.plugin.ui.BossAlertDialog
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.utils.revealInFileManager
import ai.rever.boss.utils.revealInFileManagerLabel
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

@Composable
internal fun KeymapRecoveryDialog() {
    // Object initialization loads settings and publishes any recovery. Do this explicitly,
    // rather than relying on BossAppDialogs collecting currentSettings before claiming.
    KeymapSettingsManager.ensureLoaded()
    val pending by KeymapRecoveryNotices.pending.collectAsState()
    val owner = remember(pending?.notice) { Any() }

    DisposableEffect(owner) {
        onDispose { KeymapRecoveryNotices.release(owner) }
    }
    LaunchedEffect(pending, owner) {
        // Effects run only after composition commits. Observing the retained state also lets
        // an already-open window take over when the owner closes without acknowledging.
        if (pending?.owner == null) KeymapRecoveryNotices.claim(owner)
    }

    val notice = pending?.takeIf { it.owner === owner }?.notice ?: return
    KeymapRecoveryDialogContent(notice) { KeymapRecoveryNotices.acknowledge(owner) }
}

@Composable
private fun KeymapRecoveryDialogContent(
    notice: KeymapRecoveryNotice,
    acknowledge: () -> Unit,
) {
    val preservedFile = notice.preservedFile
    BossAlertDialog(
        onDismissRequest = acknowledge,
        title = { Text("Keyboard shortcuts reset", color = BossTheme.colors.textPrimary) },
        text = {
            Text(
                if (preservedFile != null) {
                    "The keyboard shortcuts file was not valid, so BOSS restored the defaults. " +
                        "A copy of the invalid file was saved for inspection at:\n$preservedFile"
                } else {
                    "The keyboard shortcuts file was not valid, so BOSS restored the defaults. " +
                        "A copy of the invalid file could not be saved."
                },
                color = BossTheme.colors.textSecondary,
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (preservedFile != null && revealInFileManager(preservedFile).isFailure) {
                        StatusMessageManager.showMessage("Could not open the saved shortcuts file's folder")
                    }
                    acknowledge()
                },
            ) {
                Text(
                    if (preservedFile != null) revealInFileManagerLabel() else "Close",
                    color = BossTheme.colors.signalText,
                )
            }
        },
        dismissButton =
            if (preservedFile != null) {
                {
                    TextButton(onClick = acknowledge) {
                        Text("Close", color = BossTheme.colors.signalText)
                    }
                }
            } else {
                null
            },
    )
}
