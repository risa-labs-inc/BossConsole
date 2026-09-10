package ai.rever.boss.recovery.ui

import ai.rever.boss.recovery.models.AgentClaim
import ai.rever.boss.recovery.models.ClaimType
import ai.rever.boss.recovery.models.RecoveryPlan
import ai.rever.boss.recovery.models.RecoveryResult
import ai.rever.boss.recovery.models.VerificationStatus
import ai.rever.boss.recovery.runtime.MissionRecoveryCoordinator
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun RecoveryView(
    coordinator: MissionRecoveryCoordinator,
    modifier: Modifier = Modifier,
) {
    val state by coordinator.state.collectAsState()
    val scope = rememberCoroutineScope()

    var customCommand by remember { mutableStateOf("./gradlew test") }
    var checkpointLabel by remember { mutableStateOf("Stable State") }
    var userClaimText by remember { mutableStateOf("All tests pass") }
    var activePlan by remember { mutableStateOf<RecoveryPlan?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
            .padding(16.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = "Recovery & Verification",
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(28.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Workspace Recovery & Ground-Truth Verification",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }

            if (state.isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color(0xFF64B5F6),
                    strokeWidth = 2.dp,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Mission Info Strip
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF2D2D2D))
                .padding(12.dp),
        ) {
            Column {
                Text(
                    text = "Active Mission: ${state.activeMissionId ?: "None (Capture baseline to start)"}",
                    color = Color(0xFFE0E0E0),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (state.projectRootPath != null) {
                    Text(
                        text = "Project Root: ${state.projectRootPath}",
                        color = Color(0xFFAAAAAA),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (state.baseline != null) {
                    Text(
                        text = "Baseline Protected: ${state.baseline?.baselineFiles?.size ?: 0} pre-existing files preserved",
                        color = Color(0xFF81C784),
                        fontSize = 12.sp,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Main 2-Column Split
        Row(modifier = Modifier.fillMaxSize().weight(1f)) {
            // Left Column: Ground-Truth Claim Verification Deck
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF252526))
                    .padding(12.dp),
            ) {
                Text(
                    text = "Claim Verification & Ground Truth",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Action Controls
                OutlinedTextField(
                    value = customCommand,
                    onValueChange = { customCommand = it },
                    label = { Text("Verification Command", color = Color(0xFFAAAAAA)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = userClaimText,
                    onValueChange = { userClaimText = it },
                    label = { Text("Agent Claimed Statement", color = Color(0xFFAAAAAA)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        scope.launch {
                            val claim = AgentClaim(ClaimType.TESTS_PASSED, userClaimText)
                            coordinator.verifyClaim(customCommand, agentClaim = claim)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2E7D32)),
                ) {
                    Text("Execute Ground-Truth Verification", color = Color.White)
                }

                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = Color(0xFF3E3E42))
                Spacer(modifier = Modifier.height(12.dp))

                // Verification Result Card
                val latest = state.latestVerification
                if (latest != null) {
                    val isPass = latest.status == VerificationStatus.PASS
                    val isDiscrepancy = latest.isDiscrepancy

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .border(
                                width = 1.dp,
                                color = if (isDiscrepancy) Color(0xFFE53935) else if (isPass) Color(0xFF4CAF50) else Color(0xFFFFA726),
                                shape = RoundedCornerShape(6.dp),
                            )
                            .background(if (isDiscrepancy) Color(0x33E53935) else Color(0x22000000))
                            .padding(10.dp),
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isPass) Icons.Default.CheckCircle else Icons.Default.Error,
                                    contentDescription = "Status",
                                    tint = if (isPass) Color(0xFF4CAF50) else Color(0xFFE53935),
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Status: ${latest.status} (Exit Code: ${latest.exitCode ?: "N/A"})",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                )
                            }

                            if (isDiscrepancy) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "🚨 CLAIM DISCREPANCY DETECTED!",
                                    color = Color(0xFFFF5252),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                )
                                Text(
                                    text = "Agent asserted: '${latest.agentClaim?.statement}' but ground-truth failed.",
                                    color = Color(0xFFFFCDD2),
                                    fontSize = 11.sp,
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = latest.evidenceSummary,
                                color = Color(0xFFE0E0E0),
                                fontSize = 12.sp,
                            )

                            if (latest.stderrSnippet.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = latest.stderrSnippet.take(300),
                                    color = Color(0xFFFF8A80),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = "No verification executed yet. Run verification to compare against agent claims.",
                        color = Color(0xFF888888),
                        fontSize = 12.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Right Column: Checkpoints & Bounded Rewind Deck
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF252526))
                    .padding(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Checkpoints & Recovery",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
                    Text(
                        text = "${state.checkpoints.size} Checkpoints",
                        color = Color(0xFFAAAAAA),
                        fontSize = 12.sp,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = checkpointLabel,
                        onValueChange = { checkpointLabel = it },
                        label = { Text("Checkpoint Label", color = Color(0xFFAAAAAA)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                coordinator.createCheckpoint(checkpointLabel)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1565C0)),
                    ) {
                        Text("+ Create", color = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = Color(0xFF3E3E42))
                Spacer(modifier = Modifier.height(8.dp))

                // Checkpoint List
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.checkpoints) { cp ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF2D2D30))
                                .padding(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = cp.label,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                    )
                                    Text(
                                        text = "${cp.checkpointId} • ${cp.manifest.files.size} files",
                                        color = Color(0xFF9E9E9E),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                activePlan = coordinator.previewRecovery(cp.checkpointId)
                                            }
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF64B5F6)),
                                    ) {
                                        Text("Preview", fontSize = 12.sp)
                                    }

                                    Spacer(modifier = Modifier.width(6.dp))

                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                coordinator.rewindToCheckpoint(cp.checkpointId)
                                            }
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFB74D)),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Restore,
                                            contentDescription = "Rewind",
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Rewind", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Dry-run Recovery Plan Inspector Card
        val currentPlan = activePlan
        if (currentPlan != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF252528))
                    .border(1.dp, if (currentPlan.canRewind) Color(0xFF2E7D32) else Color(0xFFC62828), RoundedCornerShape(6.dp))
                    .padding(12.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Recovery Preview: ${currentPlan.checkpointId} (${if (currentPlan.canRewind) "SAFE TO REWIND" else "BLOCKED"})",
                            color = if (currentPlan.canRewind) Color(0xFF81C784) else Color(0xFFEF5350),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                        OutlinedButton(
                            onClick = { activePlan = null },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFAAAAAA)),
                        ) {
                            Text("Dismiss", fontSize = 11.sp)
                        }
                    }
                    if (currentPlan.blockingReason != null) {
                        Text(
                            text = "Reason: ${currentPlan.blockingReason}",
                            color = Color(0xFFFF8A80),
                            fontSize = 11.sp,
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Restore: ${currentPlan.filesToRestore.size} files • Remove: ${currentPlan.filesToRemove.size} files • Preserve: ${currentPlan.filesToPreserve.size} files • Conflicts: ${currentPlan.conflicts.size}",
                        color = Color(0xFFCCCCCC),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        // Bottom Recovery Status Banner
        val lastRecovery = state.lastRecoveryResult
        if (lastRecovery != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (lastRecovery.isSuccessful) Color(0xFF1B5E20) else Color(0xFFB71C1C))
                    .padding(8.dp),
            ) {
                Text(
                    text = when (lastRecovery) {
                        is RecoveryResult.Success -> "✓ Rewind Successful: Restored ${lastRecovery.restoredFilesCount} files, removed ${lastRecovery.removedFilesCount} mission-added files in ${lastRecovery.durationMs}ms."
                        is RecoveryResult.Conflict -> "⚠ Rewind Conflict: ${lastRecovery.reason}"
                        is RecoveryResult.InvalidCheckpoint -> "❌ Invalid Checkpoint: ${lastRecovery.reason}"
                        is RecoveryResult.PartialFailure -> "❌ Partial Failure: ${lastRecovery.reason} (${lastRecovery.failedFiles.size} locked)"
                    },
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
