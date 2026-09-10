package ai.rever.boss.recovery.models

import kotlinx.serialization.Serializable

/**
 * Verification status determined by the independent verifier.
 * INVARIANT: UNKNOWN is never treated as PASS.
 */
@Serializable
enum class VerificationStatus {
    PASS,
    FAIL,
    UNKNOWN,
}

/**
 * Type of claim asserted by an AI agent.
 */
@Serializable
enum class ClaimType {
    BUILD_SUCCESS,
    TESTS_PASSED,
    CUSTOM,
}

/**
 * Metadata snapshot for an individual tracked file.
 */
@Serializable
data class FileSnapshotMeta(
    val relativePath: String, // Always normalized with forward slashes '/'
    val sha256: String,
    val sizeBytes: Long,
    val lastModified: Long,
)

/**
 * Snapshot of the workspace baseline captured at the start of a mission.
 * Preserves pre-existing user state and provides the boundary for attributing mutations.
 */
@Serializable
data class MissionBaseline(
    val missionId: String,
    val projectRoot: String,
    val capturedAt: Long = System.currentTimeMillis(),
    val baselineFiles: Map<String, FileSnapshotMeta>,
    val gitBranch: String? = null,
    val gitHeadCommit: String? = null,
    val gitUncommittedFiles: List<String> = emptyList(),
)

/**
 * Manifest containing the tracked file state for a specific checkpoint.
 */
@Serializable
data class CheckpointManifest(
    val checkpointId: String,
    val missionId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val files: Map<String, FileSnapshotMeta>,
    val excludedPatterns: Set<String> = DEFAULT_EXCLUDED_PATTERNS,
) {
    companion object {
        val DEFAULT_EXCLUDED_PATTERNS =
            setOf(
                ".git",
                "node_modules",
                ".gradle",
                "build",
                ".next",
                "dist",
                "target",
                ".idea",
                ".boss",
                ".fleet",
                ".vscode",
            )
    }
}

/**
 * A named recovery boundary captured during a mission.
 */
@Serializable
data class WorkspaceCheckpoint(
    val checkpointId: String,
    val missionId: String,
    val label: String,
    val timestamp: Long = System.currentTimeMillis(),
    val manifest: CheckpointManifest,
    val latestVerification: VerificationResult? = null,
)

/**
 * An unverified claim asserted by an AI agent.
 */
@Serializable
data class AgentClaim(
    val claimType: ClaimType,
    val statement: String,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Ground-truth verification result from executing an independent verification command.
 */
@Serializable
data class VerificationResult(
    val status: VerificationStatus,
    val command: String,
    val exitCode: Int?,
    val stdoutSnippet: String = "",
    val stderrSnippet: String = "",
    val durationMs: Long = 0L,
    val agentClaim: AgentClaim? = null,
    val evidenceSummary: String = "",
    val timestamp: Long = System.currentTimeMillis(),
) {
    /**
     * True if the agent claimed success but the ground truth was not PASS.
     */
    val isDiscrepancy: Boolean
        get() = (agentClaim?.claimType == ClaimType.TESTS_PASSED || agentClaim?.claimType == ClaimType.BUILD_SUCCESS) &&
            status != VerificationStatus.PASS
}

/**
 * Dry-run recovery plan detailing exactly what will change upon rollback,
 * with zero destructive filesystem writes performed during planning.
 */
@Serializable
data class RecoveryPlan(
    val checkpointId: String,
    val filesToRestore: List<String>,
    val filesToRemove: List<String>,
    val filesToPreserve: List<String>,
    val conflicts: List<String>,
    val canRewind: Boolean,
    val blockingReason: String? = null,
)

/**
 * Explicit outcome of attempting a bounded workspace rollback.
 * INVARIANT:
 * - PARTIAL != SUCCESS
 * - CONFLICT != SUCCESS
 * - INVALID_CHECKPOINT != SUCCESS
 */
@Serializable
sealed interface RecoveryResult {
    val checkpointId: String
    val isSuccessful: Boolean

    @Serializable
    data class Success(
        override val checkpointId: String,
        val restoredFilesCount: Int,
        val removedFilesCount: Int,
        val durationMs: Long,
    ) : RecoveryResult {
        override val isSuccessful: Boolean get() = true
    }

    @Serializable
    data class Conflict(
        override val checkpointId: String,
        val conflictingFiles: List<String>,
        val reason: String,
    ) : RecoveryResult {
        override val isSuccessful: Boolean get() = false
    }

    @Serializable
    data class InvalidCheckpoint(
        override val checkpointId: String,
        val reason: String,
    ) : RecoveryResult {
        override val isSuccessful: Boolean get() = false
    }

    @Serializable
    data class PartialFailure(
        override val checkpointId: String,
        val restoredFiles: List<String>,
        val failedFiles: Map<String, String>, // relativePath -> errorMessage
        val reason: String,
    ) : RecoveryResult {
        override val isSuccessful: Boolean get() = false
    }
}
