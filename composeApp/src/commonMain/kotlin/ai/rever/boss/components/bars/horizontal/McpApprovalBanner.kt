package ai.rever.boss.components.bars.horizontal

import ai.rever.boss.mcp.ApprovalDecision
import ai.rever.boss.mcp.McpTelemetryRecorder
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Human-in-the-Loop floating approval banner.
 *
 * Appears when an AI agent attempts to execute an approval-gated tool call.
 * Allows the operator to approve, deny, or inspect the action before execution.
 */
@Composable
@Suppress("LongMethod") // Declarative Compose layout.
fun McpApprovalBanner(
    onInspectRequested: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val pending by McpTelemetryRecorder.pendingApprovals.collectAsState()
    val request = pending.firstOrNull()

    AnimatedVisibility(
        visible = request != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        if (request == null) return@AnimatedVisibility

        val colors = BossTheme.colors
        val radii = BossTheme.radius

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .shadow(elevation = 6.dp, shape = RoundedCornerShape(radii.card))
                    .clip(RoundedCornerShape(radii.card))
                    .background(colors.panel)
                    .border(1.dp, colors.warn.copy(alpha = 0.6f), RoundedCornerShape(radii.card))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Text(
                    text = "⚠️",
                    fontSize = 18.sp,
                    modifier = Modifier.padding(end = 10.dp),
                )
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Agent Approval Required: ",
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                        Text(
                            text = request.toolName,
                            color = colors.warn,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        )
                        if (pending.size > 1) {
                            Text(
                                text = " (+${pending.size - 1} more)",
                                color = colors.textSecondary,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                    Text(
                        text = "Args: ${request.arguments.replace("\n", " ").trim()}",
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onInspectRequested,
                    colors =
                        ButtonDefaults.outlinedButtonColors(
                            contentColor = colors.textSecondary,
                        ),
                    shape = RoundedCornerShape(radii.button),
                ) {
                    Text("Inspect", fontSize = 12.sp)
                }

                Button(
                    onClick = {
                        McpTelemetryRecorder.resolveApproval(
                            request.callId,
                            ApprovalDecision.Denied("Denied by operator via approval banner"),
                        )
                    },
                    colors =
                        ButtonDefaults.buttonColors(
                            backgroundColor = colors.alert,
                            contentColor = colors.signalText,
                        ),
                    shape = RoundedCornerShape(radii.button),
                ) {
                    Text("Deny", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = {
                        McpTelemetryRecorder.resolveApproval(
                            request.callId,
                            ApprovalDecision.Approved(),
                        )
                    },
                    colors =
                        ButtonDefaults.buttonColors(
                            backgroundColor = colors.ok,
                            contentColor = colors.signalText,
                        ),
                    shape = RoundedCornerShape(radii.button),
                ) {
                    Text("Approve", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
