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
 * Lets an operator see and undo every persisted "Trust This Plugin" grant made from
 * [McpApprovalDialog] - each one is an ALLOW for an entire provider's tools, not a single tool,
 * so this is the only path back short of hand-editing `~/.boss/mcp-tool-policy.json`.
 *
 * [providerRules] is a snapshot the caller re-derives from
 * [ai.rever.boss.mcp.McpPolicyEngine.config] on every recomposition, not a copy this dialog owns.
 * Only ALLOW rows are listed: this dialog has no affordance for setting a provider-wide DENY, so
 * there is nothing else a provider rule can currently hold when this UI is the only writer.
 */
@Composable
@Suppress("LongMethod") // Declarative Compose layout.
fun McpProviderTrustDialog(
    providerRules: Map<String, McpPolicyAction>,
    onRevoke: suspend (providerId: String) -> Boolean,
    onDismiss: () -> Unit,
) {
    val colors = BossTheme.colors
    val radii = BossTheme.radius
    val scope = rememberCoroutineScope()
    // Which provider's revoke most recently failed to persist. A single slot, not a set: a
    // successful revoke of any provider clears it, matching McpPolicyManagerDialog's own
    // reasoning for the equivalent per-tool dialog - this is diagnostic, not an audit trail.
    var failedRevoke by remember { mutableStateOf<String?>(null) }
    val trusted = providerRules.filterValues { it == McpPolicyAction.ALLOW }

    BossDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(),
    ) {
        Surface(
            modifier = Modifier.width(440.dp).wrapContentHeight(),
            shape = RoundedCornerShape(radii.dialog),
            color = colors.panel,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Trusted Plugins",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text =
                        "Saved from \"Trust This Plugin\" in the tool approval dialog. Removing " +
                            "one returns every tool from that plugin to its own rule or the " +
                            "default policy - not necessarily to asking again.",
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (trusted.isEmpty()) {
                    Text(
                        text = "No plugin is trusted as a whole. Tools are governed individually.",
                        fontSize = 13.sp,
                        color = colors.textSecondary,
                    )
                } else {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp)
                                .verticalScroll(rememberScrollState()),
                    ) {
                        trusted.keys.sorted().forEach { providerId ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = providerId,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = colors.textPrimary,
                                    )
                                    Text(
                                        text = "All tools trusted",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.signal,
                                    )
                                    if (failedRevoke == providerId) {
                                        Text(
                                            text = "Could not save - see the host log for the reason.",
                                            fontSize = 11.sp,
                                            color = colors.alert,
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            failedRevoke = if (onRevoke(providerId)) null else providerId
                                        }
                                    },
                                ) {
                                    Text("Revoke", fontSize = 12.sp)
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
