package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpSessionTrust
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

/**
 * Every tool trusted for this session from [McpApprovalDialog]'s "This session" scope, each
 * revocable on its own. Before this the bar offered only a count and a clear-all, so an operator
 * could not see WHICH tools were running without prompting, nor take back one grant without
 * dropping the rest.
 *
 * Rows are the exact `(providerId, toolName)` identity the engine grants on, and [onRevoke]
 * revokes that pair only - a same-named tool from another provider keeps its own grant.
 * [trusted] is the live engine flow's value, so a row disappears as soon as its revoke lands.
 * Session trust lives in memory only; nothing here writes to disk, so a revoke cannot fail.
 */
@Composable
@Suppress("LongMethod") // Declarative Compose layout.
fun McpSessionTrustDialog(
    trusted: Set<McpSessionTrust>,
    onRevoke: (McpSessionTrust) -> Unit,
    onRevokeAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = BossTheme.colors
    val radii = BossTheme.radius
    val rows = sessionTrustRows(trusted)

    BossDialog(onDismissRequest = onDismiss, properties = DialogProperties()) {
        Surface(
            modifier = Modifier.width(460.dp).wrapContentHeight(),
            shape = RoundedCornerShape(radii.dialog),
            color = colors.panel,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Session Trust",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text =
                        "Tools allowed with \"This session\" in the tool approval dialog. They run " +
                            "without asking until revoked or until BOSS quits.",
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (rows.isEmpty()) {
                    Text(
                        text = "No tool is trusted for this session.",
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
                        rows.forEach { grant ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = grant.toolName,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = colors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = grant.providerId,
                                        fontSize = 11.sp,
                                        color = colors.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                TextButton(
                                    onClick = { onRevoke(grant) },
                                    colors = ButtonDefaults.textButtonColors(contentColor = colors.alert),
                                ) {
                                    Text("Revoke", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (rows.size > 1) {
                        TextButton(
                            onClick = onRevokeAll,
                            colors = ButtonDefaults.textButtonColors(contentColor = colors.alert),
                        ) {
                            Text("Revoke all", fontSize = 12.sp)
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
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

/** Rows in a stable, readable order: by tool name, then by provider for same-named tools. */
internal fun sessionTrustRows(trusted: Set<McpSessionTrust>): List<McpSessionTrust> =
    trusted.sortedWith(compareBy({ it.toolName }, { it.providerId }))
