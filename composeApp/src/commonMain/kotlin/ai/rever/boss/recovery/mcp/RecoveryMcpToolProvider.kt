package ai.rever.boss.recovery.mcp

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.recovery.models.AgentClaim
import ai.rever.boss.recovery.models.ClaimType
import ai.rever.boss.recovery.models.RecoveryResult
import ai.rever.boss.recovery.runtime.MissionRecoveryCoordinator
import java.io.File

/**
 * The MCP registry wraps every tool handler in a 60s `withTimeout`
 * (`McpToolRegistryImpl.invokeTimeoutMs`). The verifier's own process timeout must
 * stay strictly below that bound, otherwise the host timeout wins the race and the
 * verifier's UNKNOWN verdict is discarded as a plain tool timeout.
 */
internal const val DEFAULT_VERIFY_CLAIM_TIMEOUT_MS: Long = 45_000L
internal const val MAX_VERIFY_CLAIM_TIMEOUT_MS: Long = 55_000L

internal fun resolveVerifyClaimTimeoutMs(requested: Int?): Long =
    (requested?.takeIf { it > 0 }?.toLong() ?: DEFAULT_VERIFY_CLAIM_TIMEOUT_MS)
        .coerceAtMost(MAX_VERIFY_CLAIM_TIMEOUT_MS)

/**
 * Exposes workspace recovery and ground-truth verification tools to AI coding agents via the MCP protocol.
 */
class RecoveryMcpToolProvider(
    private val coordinator: MissionRecoveryCoordinator,
) : McpToolProvider {
    override val providerId: String = "boss-recovery-provider"

    override fun tools(): List<McpToolDefinition> =
        listOf(
            createStartMissionTool(),
            createCheckpointTool(),
            createVerifyClaimTool(),
            createPreviewRewindTool(),
            createRewindTool(),
            createListCheckpointsTool(),
        )

    @Suppress("TooGenericExceptionCaught") // Handler boundary: any failure becomes an error result.
    private fun createStartMissionTool(): McpToolDefinition =
        McpToolDefinition(
            name = "recovery_start_mission",
            description =
                "Initializes a recovery boundary and captures the pre-existing " +
                    "workspace baseline for a project.",
            handler =
                McpToolHandler { args: McpToolArgs ->
                    val projectPath =
                        args.string("projectPath")
                            ?: coordinator.state.value.projectRootPath
                            ?: return@McpToolHandler McpToolResult(
                                "Missing required parameter: projectPath",
                                isError = true,
                            )

                    val missionId = args.string("missionId")
                    val projectFile = File(projectPath)

                    try {
                        val baseline =
                            if (missionId != null) {
                                coordinator.startMission(projectFile, missionId = missionId)
                            } else {
                                coordinator.startMission(projectFile)
                            }

                        McpToolResult(
                            "Mission baseline captured successfully for mission '${baseline.missionId}'. " +
                                "Tracked ${baseline.baselineFiles.size} baseline files.",
                        )
                    } catch (e: Exception) {
                        McpToolResult("Failed to capture baseline: ${e.message}", isError = true)
                    }
                },
        ).apply {
            requiresAdmin = true
        }

    @Suppress("TooGenericExceptionCaught") // Handler boundary: any failure becomes an error result.
    private fun createCheckpointTool(): McpToolDefinition =
        McpToolDefinition(
            name = "recovery_create_checkpoint",
            description = "Creates a deterministic workspace checkpoint of the current file state.",
            handler =
                McpToolHandler { args: McpToolArgs ->
                    val label = args.string("label") ?: "Checkpoint"
                    try {
                        val cp = coordinator.createCheckpoint(label = label)
                        val checkpointSummary =
                            "Created checkpoint '${cp.checkpointId}' ('${cp.label}') " +
                                "tracking ${cp.manifest.files.size} files."
                        McpToolResult(checkpointSummary)
                    } catch (e: Exception) {
                        McpToolResult("Failed to create checkpoint: ${e.message}", isError = true)
                    }
                },
        ).apply {
            requiresAdmin = true
        }

    @Suppress("TooGenericExceptionCaught") // Handler boundary: any failure becomes an error result.
    private fun createVerifyClaimTool(): McpToolDefinition =
        McpToolDefinition(
            name = "recovery_verify_claim",
            description =
                "Executes an operator/agent-supplied verification command in the workspace (with host authority " +
                    "and bounded timeout) and records independent ground-truth evidence. Optional 'timeoutMs' " +
                    "(default 45000, capped at 55000, strictly below the host's 60s invoke timeout) bounds the " +
                    "verification process so the verifier's own timeout verdict is what the caller sees.",
            handler =
                McpToolHandler { args: McpToolArgs ->
                    val command =
                        args.string("command")
                            ?: return@McpToolHandler McpToolResult(
                                "Missing required parameter: command",
                                isError = true,
                            )

                    val agentClaim = args.claimOrNull()
                    val timeoutMs = resolveVerifyClaimTimeoutMs(args.int("timeoutMs"))

                    try {
                        val result =
                            coordinator.verifyClaim(
                                command = command,
                                agentClaim = agentClaim,
                                timeoutMs = timeoutMs,
                            )
                        val summary =
                            buildString {
                                appendLine("Verification Status: ${result.status}")
                                appendLine("Exit Code: ${result.exitCode}")
                                appendLine("Evidence: ${result.evidenceSummary}")
                                if (result.isDiscrepancy) {
                                    val discrepancy =
                                        "DISCREPANCY DETECTED: Agent claimed '${agentClaim?.statement}', " +
                                            "but verification status was ${result.status}."
                                    appendLine(discrepancy)
                                }
                                if (result.stderrSnippet.isNotBlank()) {
                                    appendLine("Stderr: ${result.stderrSnippet.take(500)}")
                                }
                            }
                        McpToolResult(summary)
                    } catch (e: Exception) {
                        McpToolResult("Verification failed: ${e.message}", isError = true)
                    }
                },
        ).apply {
            // Arbitrary command execution with host authority: admin-gated like recovery_rewind.
            requiresAdmin = true
        }

    private fun McpToolArgs.claimOrNull(): AgentClaim? {
        val claimStatement = string("claimStatement") ?: return null
        val claimType =
            when (string("claimType")?.uppercase()) {
                "BUILD_SUCCESS" -> ClaimType.BUILD_SUCCESS
                "TESTS_PASSED" -> ClaimType.TESTS_PASSED
                else -> ClaimType.CUSTOM
            }
        return AgentClaim(claimType = claimType, statement = claimStatement)
    }

    @Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught") // Report formatting + handler boundary.
    private fun createPreviewRewindTool(): McpToolDefinition =
        McpToolDefinition(
            name = "recovery_preview_rewind",
            description =
                "Generates a non-destructive dry-run recovery plan detailing which files will be restored, removed, " +
                    "preserved, or flagged as conflicting before performing any rollback.",
            handler =
                McpToolHandler { args: McpToolArgs ->
                    val checkpointId =
                        args.string("checkpointId")
                            ?: return@McpToolHandler McpToolResult(
                                "Missing required parameter: checkpointId",
                                isError = true,
                            )

                    val allowOverwrite = args.boolean("allowOverwriteConflicts") ?: false

                    try {
                        val plan = coordinator.previewRecovery(checkpointId, allowOverwriteConflicts = allowOverwrite)
                        val summary =
                            buildString {
                                appendLine("=== RECOVERY PREVIEW (Dry Run) ===")
                                appendLine("Target Checkpoint: ${plan.checkpointId}")
                                appendLine("Safe To Rewind: ${if (plan.canRewind) "YES" else "NO (BLOCKED)"}")
                                if (plan.blockingReason != null) {
                                    appendLine("Blocking Reason: ${plan.blockingReason}")
                                }
                                appendLine("\nFiles To Restore (${plan.filesToRestore.size}):")
                                if (plan.filesToRestore.isEmpty()) {
                                    appendLine("  (None)")
                                } else {
                                    plan.filesToRestore.forEach { appendLine("  ↺ $it") }
                                }

                                appendLine("\nFiles To Remove (${plan.filesToRemove.size}):")
                                if (plan.filesToRemove.isEmpty()) {
                                    appendLine("  (None)")
                                } else {
                                    plan.filesToRemove.forEach { appendLine("  ✕ $it") }
                                }

                                appendLine("\nFiles To Preserve (${plan.filesToPreserve.size}):")
                                if (plan.filesToPreserve.isEmpty()) {
                                    appendLine("  (None)")
                                } else {
                                    plan.filesToPreserve.take(10).forEach { appendLine("  ✓ $it") }
                                }
                                if (plan.filesToPreserve.size > 10) {
                                    val remaining = plan.filesToPreserve.size - 10
                                    appendLine("  ... and $remaining more")
                                }

                                if (plan.conflicts.isNotEmpty()) {
                                    appendLine("\nConflicts Detected (${plan.conflicts.size}):")
                                    plan.conflicts.forEach { appendLine("  ⚠ $it") }
                                }
                            }
                        McpToolResult(summary)
                    } catch (e: Exception) {
                        McpToolResult("Failed to generate recovery preview: ${e.message}", isError = true)
                    }
                },
        )

    @Suppress("TooGenericExceptionCaught") // Handler boundary: any failure becomes an error result.
    private fun createRewindTool(): McpToolDefinition =
        McpToolDefinition(
            name = "recovery_rewind",
            description =
                "Rewinds the workspace to a target checkpoint while preserving pre-existing baseline work. " +
                    "Mutating rollback operation requiring explicit authorization.",
            handler =
                McpToolHandler { args: McpToolArgs ->
                    val checkpointId =
                        args.string("checkpointId")
                            ?: return@McpToolHandler McpToolResult(
                                "Missing required parameter: checkpointId",
                                isError = true,
                            )

                    val allowOverwrite = args.boolean("allowOverwriteConflicts") ?: false

                    try {
                        val result =
                            coordinator.rewindToCheckpoint(
                                checkpointId,
                                allowOverwriteConflicts = allowOverwrite,
                            )
                        when (result) {
                            is RecoveryResult.Success -> {
                                McpToolResult(
                                    "Successfully rewound workspace to checkpoint '${result.checkpointId}'. " +
                                        "Restored ${result.restoredFilesCount} files, " +
                                        "removed ${result.removedFilesCount} in ${result.durationMs}ms.",
                                )
                            }

                            is RecoveryResult.Conflict -> {
                                McpToolResult(
                                    "Rewind conflict on checkpoint '${result.checkpointId}': ${result.reason}. " +
                                        "Conflicting files: ${result.conflictingFiles.joinToString()}",
                                    isError = true,
                                )
                            }

                            is RecoveryResult.InvalidCheckpoint -> {
                                McpToolResult(
                                    "Invalid checkpoint '${result.checkpointId}': ${result.reason}",
                                    isError = true,
                                )
                            }

                            is RecoveryResult.PartialFailure -> {
                                McpToolResult(
                                    "Partial rewind failure on checkpoint '${result.checkpointId}': " +
                                        "${result.reason}. Failed on: ${result.failedFiles}",
                                    isError = true,
                                )
                            }
                        }
                    } catch (e: Exception) {
                        McpToolResult("Failed to rewind: ${e.message}", isError = true)
                    }
                },
        ).apply {
            requiresAdmin = true
        }

    private fun createListCheckpointsTool(): McpToolDefinition =
        McpToolDefinition(
            name = "recovery_list_checkpoints",
            description = "Lists all available checkpoints for the active mission.",
            handler =
                McpToolHandler { _: McpToolArgs ->
                    val state = coordinator.state.value
                    val missionId =
                        state.activeMissionId
                            ?: return@McpToolHandler McpToolResult("No active mission started", isError = true)

                    val checkpoints = state.checkpoints
                    if (checkpoints.isEmpty()) {
                        McpToolResult("No checkpoints recorded yet for mission '$missionId'.")
                    } else {
                        val listing =
                            checkpoints.joinToString("\n") { cp ->
                                "• [${cp.checkpointId}] '${cp.label}' (${cp.manifest.files.size}, at ${cp.timestamp})"
                            }
                        McpToolResult("Checkpoints for mission '$missionId':\n$listing")
                    }
                },
        )
}
