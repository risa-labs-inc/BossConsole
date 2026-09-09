package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

/**
 * Tells the user a plugin was refused for a version floor, and offers the ways out.
 *
 * The thing this replaces is silence. A `minBossVersion` or `minApiVersion` miss produced one ERROR
 * line in `~/.boss/logs` and nothing else, so the plugin just stopped existing - and for a
 * systemPlugin that reads as a feature disappearing, since fluck-browser *is* the browser tab.
 *
 * The remedies come from [remediesFor] and are rendered in the order it returns them, most useful
 * first. Two properties of that ordering are load-bearing here:
 *
 * - **An update is only offered when it clears the floor.** A button that costs a download and a
 *   restart and lands back on the same dialog is worse than no button.
 * - **Reverting is last but always present when possible.** It needs nothing published and no
 *   restart, so it is what works when everything else is unavailable.
 *
 * @param busy true while a remedy is being applied, so the dialog stays put and says what is
 *   happening rather than vanishing and leaving a user to guess whether it worked
 * @param successMessage confirmation of a started update; other remedies stay available
 * @param error a failure from the last attempt, kept on screen instead of dismissing the dialog
 */
@Composable
fun PluginLoadGateDialog(
    gate: PluginLoadGate,
    remedies: List<PluginLoadRemedy>,
    busy: Boolean,
    error: String?,
    successMessage: String?,
    onDismiss: () -> Unit,
    onApply: (PluginLoadRemedy) -> Unit,
) {
    BossDialog(
        // Not dismissable while a remedy is running: an app download or a jar restore continues
        // regardless, and a dialog that disappears mid-way reads as "nothing happened".
        onDismissRequest = { if (!busy) onDismiss() },
        properties =
            DialogProperties(
                dismissOnBackPress = !busy,
                dismissOnClickOutside = !busy,
                usePlatformDefaultWidth = false,
            ),
    ) {
        Card(
            modifier =
                Modifier
                    .width(440.dp)
                    .onKeyEvent { event ->
                        val escape = event.type == KeyEventType.KeyDown && event.key == Key.Escape
                        if (!busy && escape) {
                            onDismiss()
                            true
                        } else {
                            false
                        }
                    },
            shape = RoundedCornerShape(8.dp),
            backgroundColor = BossTheme.colors.panel,
            elevation = 8.dp,
        ) {
            PluginLoadGateBody(
                gate = gate,
                remedies = remedies,
                busy = busy,
                error = error,
                successMessage = successMessage,
                onDismiss = onDismiss,
                onApply = onApply,
            )
        }
    }
}

@Composable
private fun PluginLoadGateBody(
    gate: PluginLoadGate,
    remedies: List<PluginLoadRemedy>,
    busy: Boolean,
    error: String?,
    successMessage: String?,
    onDismiss: () -> Unit,
    onApply: (PluginLoadRemedy) -> Unit,
) {
    Column(modifier = Modifier.padding(20.dp)) {
        PluginLoadGateHeader(gate)

        if (successMessage != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = successMessage,
                fontSize = 13.sp,
                color = BossTheme.colors.signalText,
            )
        }

        if (error != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = error,
                fontSize = 12.sp,
                color = BossTheme.colors.alert,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        PluginLoadGateActions(
            remedies = remainingLoadRemedies(remedies, updateStarted = successMessage != null),
            busy = busy,
            onDismiss = onDismiss,
            onApply = onApply,
        )
    }
}

@Composable
private fun PluginLoadGateActions(
    remedies: List<PluginLoadRemedy>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onApply: (PluginLoadRemedy) -> Unit,
) {
    // NothingAvailable is a sentence, not a button. remediesFor guarantees it is alone in the list
    // when present, so it is handled before anything is laid out as an action.
    val explanation = remedies.filterIsInstance<PluginLoadRemedy.NothingAvailable>().firstOrNull()
    if (explanation != null) {
        NoRemedyAvailable(reason = explanation.reason, onDismiss = onDismiss)
        return
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = BossTheme.colors.signalText,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Working",
                    fontSize = 12.sp,
                    color = BossTheme.colors.textSecondary,
                )
            }
            return@Column
        }

        // One button per remedy, stacked rather than in a row: the labels name versions, so they
        // are long enough that a row would truncate exactly the part that distinguishes them.
        remedies.forEachIndexed { index, remedy ->
            if (index > 0) Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { onApply(remedy) },
                modifier = Modifier.fillMaxWidth(),
                colors =
                    ButtonDefaults.buttonColors(
                        // Only the first remedy is the recommended one. Painting every button as
                        // primary would leave nothing saying which to take.
                        //
                        // `onSignal` rather than `signalText` on the filled button: signalText is
                        // the accent held to a text contrast floor against ink/panel/raised, and
                        // this text sits on the accent itself.
                        backgroundColor =
                            if (index == 0) BossTheme.colors.signal else BossTheme.colors.raised,
                        contentColor =
                            if (index == 0) BossTheme.colors.onSignal else BossTheme.colors.textPrimary,
                    ),
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(remedyLabel(remedy), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) {
                Text(
                    if (remedies.isEmpty()) "Close" else "Not now",
                    fontSize = 13.sp,
                    color = BossTheme.colors.textSecondary,
                )
            }
        }
    }
}

/**
 * The dead end: nothing is published and nothing was kept.
 *
 * Says so rather than showing an empty dialog. This is the position a user is in when a plugin's
 * first release already overshoots their host, and it is the one case where the honest answer is
 * "wait for the release" - which is still better than the silence this whole feature replaced.
 */
@Composable
private fun NoRemedyAvailable(
    reason: String,
    onDismiss: () -> Unit,
) {
    Column {
        Text(
            text = reason,
            fontSize = 12.sp,
            color = BossTheme.colors.textSecondary,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) {
                Text("Close", fontSize = 13.sp, color = BossTheme.colors.textSecondary)
            }
        }
    }
}

/**
 * The button text for a remedy.
 *
 * Every label names a version. "Downgrade" or "Update BOSS" leaves the user unable to tell what
 * they are about to get, and in the case of a revert, which version they are going back to.
 */
internal fun remedyLabel(remedy: PluginLoadRemedy): String =
    when (remedy) {
        is PluginLoadRemedy.UpdateHost -> "Update BOSS to ${remedy.availableVersion}"

        is PluginLoadRemedy.UpdateApi -> "Update the plugin API to ${remedy.availableVersion}"

        is PluginLoadRemedy.RevertPlugin -> "Go back to version ${remedy.toVersion}"

        // "Reinstall" rather than "Update", even when the store's version is higher: the point is
        // replacing bytes that cannot be trusted, and a user told they are updating would not
        // understand why it was offered for a plugin they never chose to change.
        is PluginLoadRemedy.ReinstallFromStore -> "Reinstall version ${remedy.version} from the store"

        is PluginLoadRemedy.NothingAvailable -> remedy.reason
    }

@Composable
private fun PluginLoadGateHeader(gate: PluginLoadGate) {
    Text(
        text = "${gate.displayName} could not be loaded",
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = BossTheme.colors.textPrimary,
        // The display name falls back to the plugin id, which comes from a manifest. Clamped
        // so a crafted one cannot grow the dialog or run on into something that reads like our
        // own copy.
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )

    Spacer(modifier = Modifier.height(10.dp))

    Text(
        text =
            when (gate) {
                is PluginLoadGate.NeedsNewerHost -> {
                    "It needs BOSS ${gate.required} or later. This is ${gate.current}."
                }

                is PluginLoadGate.NeedsNewerApi -> {
                    "It needs plugin API ${gate.required} or later. The installed API layer is ${gate.current}."
                }

                is PluginLoadGate.SignatureRejected -> {
                    // Says what is wrong with the FILE, not with a version, and does not
                    // accuse: the ordinary cause is a jar replaced by hand during development,
                    // not an attack. The verifier's own reason is shown below.
                    "The installed file does not match the signature the store recorded for " +
                        "it, so it was not loaded."
                }
            },
        fontSize = 13.sp,
        color = BossTheme.colors.textSecondary,
    )

    Spacer(modifier = Modifier.height(6.dp))

    Text(
        text = gate.pluginId,
        fontSize = 11.sp,
        color = BossTheme.colors.textMuted,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A background host update must not take the immediate rollback option away. */
internal fun remainingLoadRemedies(
    remedies: List<PluginLoadRemedy>,
    updateStarted: Boolean,
): List<PluginLoadRemedy> = if (updateStarted) remedies.filterNot { it is PluginLoadRemedy.UpdateHost } else remedies
