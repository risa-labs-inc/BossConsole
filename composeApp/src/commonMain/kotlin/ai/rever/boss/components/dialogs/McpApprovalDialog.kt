package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpApprovalRequest
import ai.rever.boss.mcp.McpArgumentSanitizer
import ai.rever.boss.mcp.McpMutatingToolCatalog
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Icon
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

/**
 * Interactive dialog prompted when an AI agent attempts to execute a tool
 * governed by an ASK policy.
 */
@Composable
@Suppress("LongMethod") // Declarative Compose layout.
fun McpApprovalDialog(
    request: McpApprovalRequest,
    pendingQueueSize: Int = 1,
    onApprove: (trustForSession: Boolean) -> Unit,
    onDeny: (reason: String) -> Unit,
) {
    val colors = BossTheme.colors
    val radii = BossTheme.radius
    val isMutating = remember(request.toolName) { McpMutatingToolCatalog.isMutating(request.toolName) }
    var rejectionReason by remember(request.id) { mutableStateOf("") }
    var showReasonInput by remember(request.id) { mutableStateOf(false) }

    BossDialog(
        // onDismissRequest is required by BossDialog; outside-click and back-press are disabled below
        // to enforce deliberate operator approval or denial.
        onDismissRequest = { onDeny("Dismissed by operator") },
        properties =
            DialogProperties(
                dismissOnClickOutside = false,
                dismissOnBackPress = false,
            ),
    ) {
        Surface(
            modifier =
                Modifier
                    .width(520.dp)
                    .wrapContentHeight(),
            shape = RoundedCornerShape(radii.dialog),
            color = colors.panel,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Warning,
                        contentDescription = "Security Alert",
                        tint = if (isMutating) colors.alert else colors.warn,
                        modifier = Modifier.size(26.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        val headerText =
                            if (pendingQueueSize > 1) {
                                "Agent Action Approval (1 of $pendingQueueSize pending)"
                            } else {
                                "Agent Action Approval"
                            }
                        Text(
                            text = headerText,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                        )
                        Text(
                            text = "An AI agent requested to invoke a governed tool.",
                            fontSize = 12.sp,
                            color = colors.textSecondary,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Tool details banner
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(colors.raised, RoundedCornerShape(radii.card))
                            .border(1.dp, colors.line, RoundedCornerShape(radii.card))
                            .padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Tool: ",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textSecondary,
                        )
                        Text(
                            text = request.toolName,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = colors.signal,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "[${request.providerId}]",
                            fontSize = 11.sp,
                            color = colors.textSecondary,
                        )
                    }

                    val sanitizedArguments =
                        remember(request.arguments) {
                            McpArgumentSanitizer.sanitize(request.arguments)
                        }

                    if (sanitizedArguments.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Arguments:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textSecondary,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 140.dp)
                                    .verticalScroll(rememberScrollState())
                                    .background(colors.raised, RoundedCornerShape(4.dp))
                                    .padding(8.dp),
                        ) {
                            sanitizedArguments.forEach { (k, v) ->
                                Text(
                                    text = "$k = $v",
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.textPrimary,
                                )
                            }
                        }
                    }
                }

                if (isMutating) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "⚠ This tool performs mutations or external execution.",
                        fontSize = 11.sp,
                        color = colors.alert,
                        fontWeight = FontWeight.Medium,
                    )
                }

                if (showReasonInput) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = rejectionReason,
                        onValueChange = { rejectionReason = it },
                        label = { Text("Rejection reason (sent to agent)", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 2,
                        colors =
                            TextFieldDefaults.outlinedTextFieldColors(
                                textColor = colors.textPrimary,
                                cursorColor = colors.signal,
                                focusedBorderColor = colors.alert,
                                unfocusedBorderColor = colors.line,
                            ),
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!showReasonInput) {
                        TextButton(
                            onClick = { showReasonInput = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = colors.textSecondary),
                        ) {
                            Text("Reject with Note...", fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    Button(
                        onClick = {
                            val reason = rejectionReason.ifBlank { "Operator declined this action" }
                            onDeny(reason)
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = colors.alert),
                    ) {
                        Text("Deny", color = colors.onSignal, fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    OutlinedButton(
                        onClick = { onApprove(true) },
                        border = ButtonDefaults.outlinedBorder,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textPrimary),
                    ) {
                        Text("Always this Session", fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = { onApprove(false) },
                        colors = ButtonDefaults.buttonColors(backgroundColor = colors.signal),
                    ) {
                        Text("Approve Once", color = colors.onSignal, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
