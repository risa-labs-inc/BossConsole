package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.ui.BossAlertDialog
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Asked when switching away from a workspace: keep it running, or close it.
 *
 * The choice has a cost in both directions and neither was visible. Keeping a workspace keeps its
 * whole split tree alive - live tab components, and for browser tabs live Chromium - for as long
 * as the window is open; closing it throws away an arrangement that took work to build. Before
 * this, BOSS always kept, silently, and there was no way to stop one afterwards.
 *
 * "Don't ask again" writes the answer to settings, so this becomes a question asked once rather
 * than a toll on every switch. Settings > Workspaces can change it back.
 *
 * @param onChoose keep, and whether to stop asking.
 */
@Composable
fun WorkspaceSwitchDialog(
    leavingName: String,
    enteringName: String,
    onChoose: (keep: Boolean, dontAskAgain: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var dontAskAgain by remember { mutableStateOf(false) }
    val colors = BossTheme.colors

    BossAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Switch to $enteringName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text =
                        "Keep $leavingName running in the background, or close it?\n\n" +
                            "Running keeps its tabs open so switching back is instant. " +
                            "Closing frees them, and $leavingName is rebuilt from its layout next time.",
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                )
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            // The whole row toggles, not just the 20dp box: this is the control
                            // people reach for last, having already decided, and a checkbox that
                            // needs aiming at is the one thing here that can waste a click.
                            .clickable { dontAskAgain = !dontAskAgain },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Checkbox(
                        checked = dontAskAgain,
                        onCheckedChange = { dontAskAgain = it },
                        colors = CheckboxDefaults.colors(checkedColor = colors.signal),
                    )
                    Text(
                        text = "Don't ask again",
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
        },
        // Keep is the confirm button because it is what BOSS has always done and what the user
        // most often means. Closing throws tabs away, so it is the one you have to aim at.
        confirmButton = {
            TextButton(onClick = { onChoose(true, dontAskAgain) }) {
                Text("Keep Running")
            }
        },
        dismissButton = {
            TextButton(onClick = { onChoose(false, dontAskAgain) }) {
                Text("Close $leavingName")
            }
        },
    )
}
