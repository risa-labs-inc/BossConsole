package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.sandbox.McpApprovalRequest
import ai.rever.boss.mcp.sandbox.McpRiskLevel
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

private val McpApprovalCardWidth = 440.dp

/**
 * BossConsole-styled human approval dialog for HIGH and CRITICAL MCP tool invocations.
 *
 * Security:
 * - Renders ONLY safe contextual info ([request.toolName], [request.riskLevel], [request.reason]).
 * - Does NOT display raw arguments, secret keys, or raw command strings.
 * - Dismissal (outside click / ESC / close) executes [onDismiss] (fails closed / Deny).
 */
@Suppress("LongMethod")
@Composable
internal fun McpApprovalDialog(
    request: McpApprovalRequest,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = BossTheme.colors
    val radii = BossTheme.radius
    val space = BossTheme.space

    val isCritical = request.riskLevel == McpRiskLevel.CRITICAL
    val accentColor = if (isCritical) colors.alert else colors.warn
    val riskLabel = if (isCritical) "CRITICAL RISK" else "HIGH RISK"

    BossDialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                dismissOnClickOutside = true,
                dismissOnBackPress = true,
            ),
    ) {
        Surface(
            modifier =
                Modifier
                    .width(McpApprovalCardWidth)
                    .wrapContentHeight(),
            shape = radii.dialogShape,
            color = colors.panel,
            elevation = BossTheme.elevation.popover,
        ) {
            Column(
                modifier = Modifier.padding(space.xl),
            ) {
                // Header Row: Risk Badge & Title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .background(
                                    color = accentColor.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(4.dp),
                                ).padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = riskLabel,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Agent Action Required",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                }

                Spacer(modifier = Modifier.height(space.lg))

                // Details Card
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = colors.raised,
                ) {
                    Column(
                        modifier = Modifier.padding(space.md),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Tool:",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textSecondary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = request.toolName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary,
                            )
                        }

                        Spacer(modifier = Modifier.height(space.sm))

                        Text(
                            text = request.reason,
                            fontSize = 13.sp,
                            color = colors.textPrimary,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(space.md))

                // Guidance Notice
                Text(
                    text =
                        "An AI agent is requesting permission to perform this action." +
                            " Review carefully before approving.",
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                )

                Spacer(modifier = Modifier.height(space.xl))

                // Action Buttons: Deny (Default) & Approve
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedButton(
                        onClick = onDeny,
                        shape = RoundedCornerShape(radii.button),
                        colors =
                            ButtonDefaults.outlinedButtonColors(
                                contentColor = colors.textPrimary,
                            ),
                    ) {
                        Text("Deny", fontWeight = FontWeight.Medium)
                    }

                    Spacer(modifier = Modifier.width(space.sm))

                    Button(
                        onClick = onApprove,
                        colors =
                            ButtonDefaults.buttonColors(
                                backgroundColor = accentColor,
                                contentColor = Color.White,
                            ),
                        shape = RoundedCornerShape(radii.button),
                    ) {
                        Text("Approve", fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
