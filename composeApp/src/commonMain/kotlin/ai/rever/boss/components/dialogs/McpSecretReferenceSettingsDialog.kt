package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpHostSecretSettings
import ai.rever.boss.mcp.McpProactivePolicyOutcome
import ai.rever.boss.mcp.McpSecretPolicyAction
import ai.rever.boss.plugin.ui.BossColorScheme
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.RadioButton
import androidx.compose.material.RadioButtonDefaults
import androidx.compose.material.Surface
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/**
 * The host's three secret-reference switches, the ones `~/.boss/mcp-tool-policy.json` held with
 * no UI (#1672): whether `{{secret:<id>}}` references are delivered at all, whether a call that
 * carries one is asked about or refused, and whether delivered values are scrubbed from results.
 *
 * It offers nothing the file does not: secret-bearing calls are Ask or Refuse, never Allow
 * ([McpSecretPolicyAction]). The operator edits a draft and saves it in one write; [onSave] is
 * given the settings the dialog started from, so a change saved meanwhile from another window
 * refuses this one instead of being written over. Turning scrubbing off takes a second, confirming
 * tap that says what it gives up, the way an Allow does in Tool policies.
 *
 * [saved] is the live value (the caller collects the policy config), so after a refused save the
 * dialog shows what is on disk. [policyUnreadable] disables every control: the engine is running
 * on fail-closed defaults then, and refuses every write.
 */
@Composable
@Suppress("LongMethod") // Declarative Compose layout.
fun McpSecretReferenceSettingsDialog(
    saved: McpHostSecretSettings,
    policyUnreadable: Boolean,
    onSave: suspend (expected: McpHostSecretSettings, updated: McpHostSecretSettings) -> McpProactivePolicyOutcome,
    onDismiss: () -> Unit,
) {
    val colors = BossTheme.colors
    val radii = BossTheme.radius
    val scope = rememberCoroutineScope()
    // Read when a save comes back refused, after the coroutine resumes: the value then, not the
    // one this composition captured.
    val onDisk by rememberUpdatedState(saved)
    // What the operator started from, and what they have changed it to.
    var base by remember { mutableStateOf(saved) }
    var draft by remember { mutableStateOf(saved) }
    var confirmingScrubOff by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val givesUpScrubbing = base.resultScrubbingEnabled && !draft.resultScrubbingEnabled
    val editable = !policyUnreadable && !saving

    BossDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(),
    ) {
        Surface(
            modifier = Modifier.width(460.dp).wrapContentHeight(),
            shape = RoundedCornerShape(radii.dialog),
            color = colors.panel,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Secret References",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text =
                        "How this host handles {{secret:<id>}} in MCP tool arguments. Saved to the MCP " +
                            "policy file and applied at once, including to a call whose prompt is open.",
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                )
                if (policyUnreadable) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text =
                            "The MCP policy file could not be read, so every tool is withheld and nothing " +
                                "here can be saved. Repair the file and restart BOSS.",
                        fontSize = 12.sp,
                        color = colors.alert,
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))

                SettingSwitch(
                    title = "Deliver secret references",
                    description =
                        if (draft.referencesEnabled) {
                            "A call that names a secret receives it after you approve the call."
                        } else {
                            "Every call that carries a reference is refused, before any vault read."
                        },
                    checked = draft.referencesEnabled,
                    enabled = editable,
                    onCheckedChange = { draft = draft.copy(referencesEnabled = it) },
                    colors = colors,
                )
                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Calls that carry a secret",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SecretPolicyChoice(
                        label = "Ask every time, showing which secrets and fields",
                        selected = draft.secretBearingCalls == McpSecretPolicyAction.ASK,
                        enabled = editable,
                        onClick = { draft = draft.copy(secretBearingCalls = McpSecretPolicyAction.ASK) },
                        colors = colors,
                    )
                    SecretPolicyChoice(
                        label = "Refuse them",
                        selected = draft.secretBearingCalls == McpSecretPolicyAction.DENY,
                        enabled = editable,
                        onClick = { draft = draft.copy(secretBearingCalls = McpSecretPolicyAction.DENY) },
                        colors = colors,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "There is no Allow: a call that receives a secret always reaches you first.",
                    fontSize = 11.sp,
                    color = colors.textSecondary,
                )
                Spacer(modifier = Modifier.height(14.dp))

                SettingSwitch(
                    title = "Scrub delivered values from results",
                    description = "A value the tool echoes back is replaced before the agent sees it.",
                    checked = draft.resultScrubbingEnabled,
                    enabled = editable,
                    onCheckedChange = {
                        draft = draft.copy(resultScrubbingEnabled = it)
                        confirmingScrubOff = false
                    },
                    colors = colors,
                )
                if (givesUpScrubbing) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text =
                            "Off, a tool that echoes a delivered value hands it to the agent, and it can " +
                                "reach the ledger's error snippet. The prompt and the recorded arguments " +
                                "still show only the reference.",
                        fontSize = 12.sp,
                        color = colors.alert,
                    )
                }

                problem?.let {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = it, fontSize = 12.sp, color = colors.alert)
                }

                Spacer(modifier = Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = colors.textSecondary, fontSize = 12.sp)
                    }
                    Button(
                        enabled = editable && draft != base,
                        onClick = {
                            if (givesUpScrubbing && !confirmingScrubOff) {
                                // The first tap only arms: the warning above is what it gives up.
                                confirmingScrubOff = true
                            } else {
                                saving = true
                                problem = null
                                scope.launch {
                                    val outcome = onSave(base, draft)
                                    saving = false
                                    confirmingScrubOff = false
                                    problem = secretSettingsSaveProblem(outcome)
                                    if (outcome == McpProactivePolicyOutcome.Refused) {
                                        // Show what is on disk now; the operator's edit was made against
                                        // something else.
                                        base = onDisk
                                        draft = onDisk
                                    }
                                    if (outcome == McpProactivePolicyOutcome.Saved) onDismiss()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = colors.signal),
                    ) {
                        Text(
                            text = if (givesUpScrubbing && confirmingScrubOff) "Confirm scrubbing off?" else "Save",
                            color = colors.onSignal,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

/** What the dialog says after a save that did not go through, or null when it did. */
internal fun secretSettingsSaveProblem(outcome: McpProactivePolicyOutcome): String? =
    when (outcome) {
        McpProactivePolicyOutcome.Saved -> {
            null
        }

        McpProactivePolicyOutcome.Refused -> {
            "Changed in another window meanwhile. Showing the saved settings; make your change again."
        }

        McpProactivePolicyOutcome.PolicyUnreadable -> {
            "The MCP policy file could not be read; nothing was saved."
        }

        McpProactivePolicyOutcome.Denied, is McpProactivePolicyOutcome.Failed -> {
            "Could not save - see the host log for the reason."
        }
    }

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    colors: BossColorScheme,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            Text(description, fontSize = 12.sp, color = colors.textSecondary)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            modifier = Modifier.semantics { contentDescription = title },
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = colors.signal),
        )
    }
}

@Composable
private fun SecretPolicyChoice(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    colors: BossColorScheme,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    if (selected) colors.signal.copy(alpha = 0.10f) else colors.textSecondary.copy(alpha = 0.04f),
                    RoundedCornerShape(8.dp),
                ).selectable(selected = selected, enabled = enabled, onClick = onClick, role = Role.RadioButton)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(selectedColor = colors.signal, unselectedColor = colors.textSecondary),
        )
        Text(label, fontSize = 13.sp, color = colors.textPrimary)
    }
}
