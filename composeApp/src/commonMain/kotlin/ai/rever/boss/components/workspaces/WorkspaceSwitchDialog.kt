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
 * What the switch prompt says about the Space being left.
 *
 * A string rather than inline composition, because the two versions of this question are not the
 * same question and the difference is the whole point. Verified against the code rather than
 * guessed at, since the wording turns on what Close actually costs:
 *
 * - `closeCurrentWorkspace` drops the window's preserved state for the Space and clears every
 *   panel, with `recordForReopen = false`, so the tabs cannot be reopened either.
 * - The Space's own file holds its last EXPLICIT save. Since the layout watcher stopped writing
 *   named Spaces, nothing has been quietly saving the arrangement on screen.
 * - `Last_Session.json` holds the live layout, but only until the watcher next settles - about two
 *   seconds after the Space being entered is applied, which overwrites it.
 * - The multi-Space set is written at shutdown, and a Space that was closed is no longer running,
 *   so it is not in the set either.
 *
 * So Close on an unsaved Space really does lose the arrangement, and Keep Running really does
 * preserve it - in memory, and into the next launch through the shutdown set. That is stated
 * plainly and no harder than that: "discards them" is true, and a scarier sentence about data loss
 * would be describing tabs that are still on disk in the Space's saved layout.
 */
internal fun switchPromptBody(
    leavingName: String,
    leavingUnsaved: Boolean,
): String =
    if (leavingUnsaved) {
        "Keep $leavingName running in the background, or close it?\n\n" +
            "$leavingName has unsaved changes. Keeping it running keeps them, " +
            "and switching back is instant. Closing discards them: $leavingName comes back from " +
            "its last saved layout, without what you have changed since.\n\n" +
            "Escape cancels the switch, if you would rather save first."
    } else {
        "Keep $leavingName running in the background, or close it?\n\n" +
            "Running keeps its tabs open so switching back is instant. " +
            // "saved layout", not "its layout": a Space is rebuilt from what was last saved, and
            // the old wording read as though it were rebuilt from what is on screen.
            "Closing frees them, and $leavingName is rebuilt from its saved layout next time."
    }

/**
 * The close button's label.
 *
 * Names the consequence when there is one. A button that throws work away should say so on its
 * face, not only in the paragraph above it - that paragraph is the thing people skip.
 */
internal fun closeButtonLabel(
    leavingName: String,
    leavingUnsaved: Boolean,
): String = if (leavingUnsaved) "Discard and Close" else "Close $leavingName"

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
 * @param leavingUnsaved whether this window holds changes to the workspace being left that are
 *   not on disk. The dialog offered to close a Space without ever saying there was work in it,
 *   which made "Close" the cheap-looking option precisely when it was the expensive one.
 * @param onChoose keep, and whether to stop asking.
 */
@Composable
fun WorkspaceSwitchDialog(
    leavingName: String,
    enteringName: String,
    leavingUnsaved: Boolean = false,
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
                    text = switchPromptBody(leavingName, leavingUnsaved),
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
                Text(closeButtonLabel(leavingName, leavingUnsaved))
            }
        },
    )
}
