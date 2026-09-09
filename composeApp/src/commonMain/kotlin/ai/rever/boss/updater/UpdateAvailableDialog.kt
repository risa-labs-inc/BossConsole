package ai.rever.boss.updater

import ai.rever.boss.components.dialogs.dialogScrollFence
import ai.rever.boss.plugin.ui.BossAlertDialog
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Dismissible dialog shown when a new BossConsole version is available.
 *
 * "Update Now" starts the download (the top banner then shows progress);
 * "Later" (or clicking outside / Esc) persists the dismissal for this
 * version — it won't be prompted again until a different version is
 * published or the user manually checks for updates.
 */
@Composable
fun UpdateAvailableDialog(
    updateInfo: UpdateInfo,
    onUpdateNow: () -> Unit,
    onLater: () -> Unit,
) {
    // Render release notes through the shared Markdown renderer with a plain-text fallback.
    BossAlertDialog(
        onDismissRequest = onLater,
        modifier = Modifier.widthIn(min = 360.dp, max = 480.dp),
        title = {
            Text(
                "Update available",
                color = BossTheme.colors.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 320.dp)) {
                Text(
                    "BossConsole v${updateInfo.latestVersion} is available " +
                        "(you have v${updateInfo.currentVersion}).",
                    color = BossTheme.colors.textSecondary,
                    fontSize = 13.sp,
                )
                if (updateInfo.releaseNotes.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "What's new",
                        color = BossTheme.colors.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(4.dp))
                    // Column+verticalScroll behind dialogScrollFence (see its kdoc):
                    // the scroll area's intrinsic height AND baseline alignment
                    // lines must both be fenced off, or the desktop AlertDialog
                    // re-sizes / shifts the text slot as the notes are scrolled.
                    Column(
                        Modifier
                            .dialogScrollFence(NOTES_MAX_HEIGHT)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ReleaseNotesContent(updateInfo.releaseNotes)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onUpdateNow) {
                Text("Update Now", color = BossTheme.colors.signalText, fontSize = 13.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onLater) {
                Text("Later", color = BossTheme.colors.textMuted, fontSize = 13.sp)
            }
        },
        backgroundColor = BossTheme.colors.panel,
        contentColor = BossTheme.colors.textPrimary,
    )
}

/** How tall the release-notes scroll area is allowed to grow before it scrolls. */
private val NOTES_MAX_HEIGHT = 220.dp
