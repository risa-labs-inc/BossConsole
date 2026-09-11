package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpPolicyAction
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/**
 * Lets an operator see and undo every persistent MCP tool policy rule saved from the approval
 * dialog's "Always Allow"/"Always Deny" actions ([McpApprovalDialog]) - the inspection and
 * revocation path this repo's own AGENTS.md names as reachable only by hand-editing
 * `~/.boss/mcp-tool-policy.json` and restarting, until this dialog existed.
 *
 * [rules] is a snapshot the caller re-derives from [ai.rever.boss.mcp.McpPolicyEngine.config] on
 * every recomposition, not a copy this dialog owns - a revoke calls back into [onRevoke] and
 * waits for the same state flow to reflect it, rather than mutating a local list that could drift
 * from what is actually on disk.
 */
@Composable
@Suppress("LongMethod") // Declarative Compose layout.
fun McpPolicyManagerDialog(
    rules: Map<String, McpPolicyAction>,
    onRevoke: suspend (toolName: String) -> Boolean,
    onDismiss: () -> Unit,
) {
    val colors = BossTheme.colors
    val radii = BossTheme.radius
    val scope = rememberCoroutineScope()
    // Which tool's revoke most recently failed to persist. A single slot, not a set: a
    // SUCCESSFUL revoke of any tool clears it, not only a retry of the same one, so two failures
    // in quick succession show only the most recent - accepted, since this is diagnostic rather
    // than an audit trail (the host log has that).
    var failedRevoke by remember { mutableStateOf<String?>(null) }
    // A DENY row asked to confirm once before it resets: resetting an ALLOW reduces standing
    // privilege, but resetting a DENY raises it, removing the one rule that beats session trust
    // (McpPolicyEngine.policyFor). Tracks at most one row at a time - switching to a different
    // row's button, or dismissing, drops any pending confirmation rather than carrying it silently.
    var confirmingDeny by remember { mutableStateOf<String?>(null) }

    BossDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(),
    ) {
        Surface(
            modifier = Modifier.width(480.dp).wrapContentHeight(),
            shape = RoundedCornerShape(radii.dialog),
            color = colors.panel,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Persistent MCP Tool Policies",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text =
                        "Saved from \"Always Allow\" / \"Always Deny\" in the tool approval dialog. " +
                            "Resetting a tool removes its saved rule - the next mutating call is " +
                            "governed by the default policy again, asking unless that default is " +
                            "itself Allow or Deny.",
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (rules.isEmpty()) {
                    Text(
                        text = "No saved rules. Default policies and session trust still apply.",
                        fontSize = 13.sp,
                        color = colors.textSecondary,
                    )
                } else {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState()),
                    ) {
                        rules.toSortedMap().forEach { (toolName, action) ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = toolName,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = colors.textPrimary,
                                    )
                                    Text(
                                        text = action.name,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (action == McpPolicyAction.DENY) colors.alert else colors.signal,
                                    )
                                    if (failedRevoke == toolName) {
                                        Text(
                                            // Session trust clears even on failure, but a saved ALLOW still
                                            // permits calls. Do not promise ASK while that durable rule remains.
                                            text =
                                                "Saved rule unchanged; this tool's session trust was cleared. " +
                                                    "See the host log.",
                                            fontSize = 11.sp,
                                            color = colors.alert,
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))

                                fun revoke() {
                                    confirmingDeny = null
                                    scope.launch {
                                        failedRevoke = if (onRevoke(toolName)) null else toolName
                                    }
                                }
                                // Removing an ALLOW is a de-escalation and needs no confirmation.
                                // Removing a DENY is the one control in this dialog that INCREASES
                                // what the tool is allowed to do, so it gets a second tap instead of
                                // firing on the first click like every other row's button does.
                                if (action == McpPolicyAction.DENY && confirmingDeny != toolName) {
                                    TextButton(onClick = { confirmingDeny = toolName }) {
                                        Text("Remove denial", fontSize = 12.sp, color = colors.alert)
                                    }
                                } else if (action == McpPolicyAction.DENY) {
                                    TextButton(onClick = { revoke() }) {
                                        Text("Confirm remove?", fontSize = 12.sp, color = colors.alert)
                                    }
                                } else {
                                    TextButton(onClick = { revoke() }) {
                                        Text("Reset", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(backgroundColor = colors.signal),
                    ) {
                        Text("Close", color = colors.onSignal, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
