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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Exposes workspace recovery and ground-truth verification tools to AI coding agents via the MCP protocol.
 */
class RecoveryMcpToolProvider(
    private val coordinator: MissionRecoveryCoordinator,
) : McpToolProvider {

    override val providerId: String = "boss-recovery-provider"

    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    override fun tools(): List<McpToolDefinition> =
        listOf(
            createStartMissionTool(),
            createCheckpointTool(),
            createVerifyClaimTool(),
            createRewindTool(),
            createListCheckpointsTool(),
        )

    private fun createStartMissionTool(): McpToolDefinition =
        McpToolDefinition(
            name = "recovery_start_mission",
            description = "Initializes a recovery boundary and captures the pre-existing workspace baseline for a project.",
            handler =
                McpToolHandler { args: McpToolArgs ->
                    val projectPath = args.string("projectPath")
                        ?: coordinator.state.value.projectRootPath
                        ?: return@McpToolHandler McpToolResult("Missing required parameter: projectPath", isError = true)

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
        )

    private fun createCheckpointTool(): McpToolDefinition =
        McpToolDefinition(
            name = "recovery_create_checkpoint",
            description = "Creates a deterministic workspace checkpoint of the current file state.",
            handler =
                McpToolHandler { args: McpToolArgs ->
                    val label = args.string("label") ?: "Checkpoint"
                    try {
                        val cp = coordinator.createCheckpoint(label = label)
                        McpToolResult(
                            "Created checkpoint '${cp.checkpointId}' ('${cp.label}') tracking ${cp.manifest.files.size} files.",
                        )
                    } catch (e: Exception) {
                        McpToolResult("Failed to create checkpoint: ${e.message}", isError = true)
                    }
                },
        )

    private fun createVerifyClaimTool(): McpToolDefinition =
        McpToolDefinition(
            name = "recovery_verify_claim",
            description = "Executes an independent ground-truth verification command (e.g. build or test) and compares against the agent's claim.",
            handler =
                McpToolHandler { args: McpToolArgs ->
                    val command = args.string("command")
                        ?: return@McpToolHandler McpToolResult("Missing required parameter: command", isError = true)

                    val claimStatement = args.string("claimStatement")
                    val claimTypeStr = args.string("claimType")

                    val claimType =
                        when (claimTypeStr?.uppercase()) {
                            "BUILD_SUCCESS" -> ClaimType.BUILD_SUCCESS
                            "TESTS_PASSED" -> ClaimType.TESTS_PASSED
                            else -> ClaimType.CUSTOM
                        }

                    val agentClaim =
                        if (claimStatement != null) {
                            AgentClaim(claimType = claimType, statement = claimStatement)
                        } else {
                            null
                        }

                    try {
                        val result = coordinator.verifyClaim(command = command, agentClaim = agentClaim)
                        val summary = buildString {
                            appendLine("Verification Status: ${result.status}")
                            appendLine("Exit Code: ${result.exitCode}")
                            appendLine("Evidence: ${result.evidenceSummary}")
                            if (result.isDiscrepancy) {
                                appendLine("DISCREPANCY DETECTED: Agent claimed '${agentClaim?.statement}', but verification status was ${result.status}.")
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
        )

    private fun createRewindTool(): McpToolDefinition =
        McpToolDefinition(
            name = "recovery_rewind",
            description = "Rewinds the workspace to a target checkpoint while preserving pre-existing baseline work.",
            handler =
                McpToolHandler { args: McpToolArgs ->
                    val checkpointId = args.string("checkpointId")
                        ?: return@McpToolHandler McpToolResult("Missing required parameter: checkpointId", isError = true)

                    val allowOverwrite = args.boolean("allowOverwriteConflicts") ?: false

                    try {
                        when (val result = coordinator.rewindToCheckpoint(checkpointId, allowOverwriteConflicts = allowOverwrite)) {
                            is RecoveryResult.Success -> {
                                McpToolResult(
                                    "Successfully rewound workspace to checkpoint '${result.checkpointId}'. " +
                                        "Restored ${result.restoredFilesCount} file(s), removed ${result.removedFilesCount} mission-added file(s) in ${result.durationMs}ms.",
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
                                McpToolResult("Invalid checkpoint '${result.checkpointId}': ${result.reason}", isError = true)
                            }
                            is RecoveryResult.PartialFailure -> {
                                McpToolResult(
                                    "Partial rewind failure on checkpoint '${result.checkpointId}': ${result.reason}. " +
                                        "Failed on: ${result.failedFiles}",
                                    isError = true,
                                )
                            }
                        }
                    } catch (e: Exception) {
                        McpToolResult("Failed to rewind: ${e.message}", isError = true)
                    }
                },
        )

    private fun createListCheckpointsTool(): McpToolDefinition =
        McpToolDefinition(
            name = "recovery_list_checkpoints",
            description = "Lists all available checkpoints for the active mission.",
            handler =
                McpToolHandler { _: McpToolArgs ->
                    val state = coordinator.state.value
                    val missionId = state.activeMissionId
                        ?: return@McpToolHandler McpToolResult("No active mission started", isError = true)

                    val checkpoints = state.checkpoints
                    if (checkpoints.isEmpty()) {
                        McpToolResult("No checkpoints recorded yet for mission '$missionId'.")
                    } else {
                        val listing =
                            checkpoints.joinToString("\n") { cp ->
                                "• [${cp.checkpointId}] '${cp.label}' (${cp.manifest.files.size} files, at ${cp.timestamp})"
                            }
                        McpToolResult("Checkpoints for mission '$missionId':\n$listing")
                    }
                },
        )
}
