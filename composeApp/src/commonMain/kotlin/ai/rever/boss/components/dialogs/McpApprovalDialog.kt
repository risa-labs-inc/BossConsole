package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpApprovalRequest
import ai.rever.boss.mcp.McpMutatingToolCatalog
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.runtime.*
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
fun McpApprovalDialog(
    request: McpApprovalRequest,
    onApprove: (trustForSession: Boolean) -> Unit,
    onDeny: (reason: String) -> Unit,
) {
    val colors = BossTheme.colors
    val radii = BossTheme.radius
    val isMutating = remember(request.toolName) { McpMutatingToolCatalog.isMutating(request.toolName) }
    var rejectionReason by remember { mutableStateOf("") }
    var showReasonInput by remember { mutableStateOf(false) }

    BossDialog(
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
                        Text(
                            text = "Agent Action Approval",
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
                            .background(colors.surface, RoundedCornerShape(radii.card))
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
                            color = colors.primary,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "[${request.providerId}]",
                            fontSize = 11.sp,
                            color = colors.textSecondary,
                        )
                    }

                    if (request.arguments.isNotEmpty()) {
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
                                    .background(colors.editorBackground, RoundedCornerShape(4.dp))
                                    .padding(8.dp),
                        ) {
                            request.arguments.forEach { (k, v) ->
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
                                cursorColor = colors.primary,
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
                        Text("Deny", color = colors.onPrimary, fontSize = 12.sp)
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
                        colors = ButtonDefaults.buttonColors(backgroundColor = colors.primary),
                    ) {
                        Text("Approve Once", color = colors.onPrimary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
