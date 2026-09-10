package ai.rever.boss.recovery.runtime

import ai.rever.boss.recovery.baseline.WorkspaceBaselineCapturer
import ai.rever.boss.recovery.models.AgentClaim
import ai.rever.boss.recovery.models.MissionBaseline
import ai.rever.boss.recovery.models.RecoveryPlan
import ai.rever.boss.recovery.models.RecoveryResult
import ai.rever.boss.recovery.models.VerificationResult
import ai.rever.boss.recovery.models.WorkspaceCheckpoint
import ai.rever.boss.recovery.paths.SafePathResolver
import ai.rever.boss.recovery.reconciliation.WorkspaceReconciler
import ai.rever.boss.recovery.storage.WorkspaceCheckpointStorage
import ai.rever.boss.recovery.verification.IndependentVerifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/**
 * Events emitted by the recovery engine.
 */
sealed interface RecoveryEvent {
    val timestamp: Long

    data class BaselineCaptured(
        val baseline: MissionBaseline,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : RecoveryEvent

    data class CheckpointSaved(
        val checkpoint: WorkspaceCheckpoint,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : RecoveryEvent

    data class VerificationExecuted(
        val result: VerificationResult,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : RecoveryEvent

    data class RewindExecuted(
        val result: RecoveryResult,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : RecoveryEvent
}

/**
 * Live state of the recovery runtime for the active workspace.
 */
data class MissionRecoveryState(
    val activeMissionId: String? = null,
    val projectRootPath: String? = null,
    val baseline: MissionBaseline? = null,
    val checkpoints: List<WorkspaceCheckpoint> = emptyList(),
    val latestVerification: VerificationResult? = null,
    val lastRecoveryResult: RecoveryResult? = null,
    val isBusy: Boolean = false,
)

/**
 * Thread-safe coordinator for mission baseline capture, checkpointing, claim verification, and rewind.
 */
class MissionRecoveryCoordinator(
    val storage: WorkspaceCheckpointStorage = WorkspaceCheckpointStorage(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow(MissionRecoveryState())
    val state: StateFlow<MissionRecoveryState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RecoveryEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<RecoveryEvent> = _events.asSharedFlow()

    /**
     * Initializes a new mission baseline on [projectRoot].
     */
    suspend fun startMission(
        projectRoot: File,
        missionId: String = "mission-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}",
        gitBranch: String? = null,
        gitHeadCommit: String? = null,
        gitUncommittedFiles: List<String> = emptyList(),
    ): MissionBaseline =
        mutex.withLock {
            _state.update { it.copy(isBusy = true) }
            try {
                val rootCanonical = SafePathResolver.canonicalRoot(projectRoot)
                val baseline =
                    WorkspaceBaselineCapturer.captureBaseline(
                        missionId = missionId,
                        projectRoot = rootCanonical,
                        gitBranch = gitBranch,
                        gitHeadCommit = gitHeadCommit,
                        gitUncommittedFiles = gitUncommittedFiles,
                    )

                val existingCheckpoints = storage.listCheckpoints(missionId)

                _state.update {
                    it.copy(
                        activeMissionId = missionId,
                        projectRootPath = rootCanonical.path,
                        baseline = baseline,
                        checkpoints = existingCheckpoints,
                        latestVerification = null,
                        lastRecoveryResult = null,
                        isBusy = false,
                    )
                }

                _events.emit(RecoveryEvent.BaselineCaptured(baseline))
                baseline
            } catch (e: Exception) {
                _state.update { it.copy(isBusy = false) }
                throw e
            }
        }

    /**
     * Creates a named checkpoint from the current workspace files.
     */
    suspend fun createCheckpoint(
        label: String,
        checkpointId: String = "cp-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(6)}",
    ): WorkspaceCheckpoint =
        mutex.withLock {
            val currentState = _state.value
            val missionId = currentState.activeMissionId ?: throw IllegalStateException("No active mission started")
            val rootPath = currentState.projectRootPath ?: throw IllegalStateException("No active project root")

            _state.update { it.copy(isBusy = true) }
            try {
                val checkpoint =
                    storage.createCheckpoint(
                        missionId = missionId,
                        checkpointId = checkpointId,
                        label = label,
                        projectRoot = File(rootPath),
                    )

                val updatedCheckpoints = (listOf(checkpoint) + currentState.checkpoints).distinctBy { it.checkpointId }

                _state.update {
                    it.copy(
                        checkpoints = updatedCheckpoints,
                        isBusy = false,
                    )
                }

                _events.emit(RecoveryEvent.CheckpointSaved(checkpoint))
                checkpoint
            } catch (e: Exception) {
                _state.update { it.copy(isBusy = false) }
                throw e
            }
        }

    /**
     * Executes independent ground-truth verification.
     */
    suspend fun verifyClaim(
        command: String,
        agentClaim: AgentClaim? = null,
        timeoutMs: Long = 60_000L,
    ): VerificationResult {
        val currentState = _state.value
        val rootPath = currentState.projectRootPath ?: throw IllegalStateException("No active project root")

        _state.update { it.copy(isBusy = true) }
        val result =
            IndependentVerifier.verify(
                command = command,
                projectRoot = File(rootPath),
                agentClaim = agentClaim,
                timeoutMs = timeoutMs,
            )

        _state.update {
            it.copy(
                latestVerification = result,
                isBusy = false,
            )
        }

        _events.emit(RecoveryEvent.VerificationExecuted(result))
        return result
    }

    /**
     * Rewinds workspace files to the target checkpoint.
     */
    suspend fun rewindToCheckpoint(
        checkpointId: String,
        allowOverwriteConflicts: Boolean = false,
    ): RecoveryResult =
        mutex.withLock {
            val currentState = _state.value
            val missionId = currentState.activeMissionId ?: throw IllegalStateException("No active mission started")
            val rootPath = currentState.projectRootPath ?: throw IllegalStateException("No active project root")
            val baseline = currentState.baseline ?: throw IllegalStateException("No baseline captured for mission")

            val targetCheckpoint =
                storage.loadCheckpoint(missionId, checkpointId)
                    ?: return@withLock RecoveryResult.InvalidCheckpoint(
                        checkpointId = checkpointId,
                        reason = "Checkpoint not found in storage: $checkpointId",
                    )

            _state.update { it.copy(isBusy = true) }

            val result =
                WorkspaceReconciler.rewind(
                    baseline = baseline,
                    targetCheckpoint = targetCheckpoint,
                    projectRoot = File(rootPath),
                    storage = storage,
                    allowOverwriteConflicts = allowOverwriteConflicts,
                )

            _state.update {
                it.copy(
                    lastRecoveryResult = result,
                    isBusy = false,
                )
            }

            _events.emit(RecoveryEvent.RewindExecuted(result))
            result
        }

    /**
     * Generates a dry-run recovery plan for [checkpointId] without modifying any files.
     */
    suspend fun previewRecovery(
        checkpointId: String,
        allowOverwriteConflicts: Boolean = false,
    ): RecoveryPlan =
        mutex.withLock {
            val currentState = _state.value
            val missionId = currentState.activeMissionId ?: throw IllegalStateException("No active mission started")
            val rootPath = currentState.projectRootPath ?: throw IllegalStateException("No active project root")
            val baseline = currentState.baseline ?: throw IllegalStateException("No baseline captured for mission")

            val targetCheckpoint =
                storage.loadCheckpoint(missionId, checkpointId)
                    ?: return@withLock RecoveryPlan(
                        checkpointId = checkpointId,
                        filesToRestore = emptyList(),
                        filesToRemove = emptyList(),
                        filesToPreserve = emptyList(),
                        conflicts = emptyList(),
                        canRewind = false,
                        blockingReason = "Checkpoint not found in storage: $checkpointId",
                    )

            WorkspaceReconciler.createPlan(
                baseline = baseline,
                targetCheckpoint = targetCheckpoint,
                projectRoot = File(rootPath),
                storage = storage,
                allowOverwriteConflicts = allowOverwriteConflicts,
            )
        }
}
