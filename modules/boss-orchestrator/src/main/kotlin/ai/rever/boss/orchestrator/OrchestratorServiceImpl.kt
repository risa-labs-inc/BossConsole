package ai.rever.boss.orchestrator

import ai.rever.boss.ipc.proto.*
import ai.rever.boss.process.ProcessRegistry
import io.grpc.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Semaphore
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * gRPC implementation of OrchestratorService.
 *
 * Receives failure reports, runs the repair engine, keeps repair history in memory,
 * and streams health events to connected watchers.
 */
class OrchestratorServiceImpl(
    private val repairEngine: RepairEngine,
    /** Optional registry — null when orchestrator runs out-of-process (C2 fix). */
    private val processRegistry: ProcessRegistry? = null,
    /**
     * Applies a repair the operator approved, and answers what happened to it.
     *
     * A host that installs nothing here gets the default, which refuses: an approval that
     * nothing acted on must not be reported to the caller as applied.
     */
    private val onRepairApproved: suspend (processId: String, action: RepairAction) -> ApprovalResult =
        { processId, action -> ApprovalResult.Refused(noApprovalSinkReason(processId, action)) },
    private val historyLimit: Int = 256,
    pendingLimit: Int = 64,
    analysisLimit: Int = 4,
) : OrchestratorServiceGrpcKt.OrchestratorServiceCoroutineImplBase() {
    private val logger = LoggerFactory.getLogger(OrchestratorServiceImpl::class.java)

    init {
        require(historyLimit >= pendingLimit && pendingLimit > 0 && analysisLimit > 0)
    }

    private val stateLock = Any()
    private val repairHistory = LinkedHashMap<String, RepairHistoryEntry>()
    private val pendingRepairs = mutableMapOf<String, PendingRepair>()
    private val pendingSlots = Semaphore(pendingLimit)
    private val analysisSlots = Semaphore(analysisLimit)
    private val _healthEvents = MutableSharedFlow<HealthEvent>(extraBufferCapacity = 64)

    override suspend fun reportFailure(request: ProcessFailureReport): RepairAction {
        if (!analysisSlots.tryAcquire()) throw exhausted("Concurrent repair analysis limit reached; retry later")
        var pendingReserved = false
        var parked = false
        try {
            if (!pendingSlots.tryAcquire()) throw exhausted("Resolve pending repairs before submitting more")
            pendingReserved = true
            val action = analyzeFailure(request)
            parked = action.requiresUserApproval
            return action
        } finally {
            if (pendingReserved && !parked) pendingSlots.release()
            analysisSlots.release()
        }
    }

    private suspend fun analyzeFailure(request: ProcessFailureReport): RepairAction {
        logger.info("Received failure report for process: {}", request.processId)

        val outcome = repairEngine.handleFailure(request)
        val repairId = UUID.randomUUID().toString()
        val strategy = outcomeToStrategy(outcome)
        val action = buildRepairAction(repairId, strategy, outcome, request)
        if (action.serializedSize > 131_072) throw exhausted("Repair proposal exceeds the size limit")

        val entry =
            RepairHistoryEntry
                .newBuilder()
                .setRepairId(repairId)
                .setProcessId(request.processId)
                .setStrategy(strategy)
                .setSuccess(outcome !is RepairOutcome.Failed)
                .setDescription(action.description.take(RepairLimits.MESSAGE_CHARS))
                .setTimestamp(System.currentTimeMillis())
                .build()

        synchronized(stateLock) {
            if (action.requiresUserApproval) {
                pendingRepairs[repairId] = PendingRepair(request.processId, action)
            }
            repairHistory[repairId] = entry
            // Pending and executing approvals are active work, so only completed history is evicted.
            while (repairHistory.size > historyLimit) {
                val oldestCompleted = repairHistory.keys.first { it !in pendingRepairs }
                repairHistory.remove(oldestCompleted)
            }
        }

        _healthEvents.tryEmit(
            HealthEvent
                .newBuilder()
                .setProcessId(request.processId)
                .setTimestamp(System.currentTimeMillis())
                .setRepairInitiated(action)
                .build(),
        )

        return action
    }

    override suspend fun getHealthDashboard(request: Empty): HealthDashboard {
        val processes = processRegistry?.getAllProcesses() ?: emptyList()
        val statuses =
            processes.map { proc ->
                ProcessHealthStatus
                    .newBuilder()
                    .setProcessId(proc.config.processId)
                    .setDisplayName(proc.config.displayName)
                    .setState(proc.state.value)
                    .setUptimeMs(System.currentTimeMillis() - proc.startTime)
                    .setRestartCount(proc.restartCount)
                    .apply { proc.lastError?.let { setLastError(it) } }
                    .setLastErrorTimestamp(proc.lastErrorTimestamp)
                    .build()
            }
        val healthyCount =
            processes.count {
                it.state.value == ProcessState.PROCESS_STATE_RUNNING
            }
        val crashedCount =
            processes.count {
                it.state.value == ProcessState.PROCESS_STATE_CRASHED
            }
        return HealthDashboard
            .newBuilder()
            .addAllProcesses(statuses)
            .setTotalProcesses(processes.size)
            .setHealthyCount(healthyCount)
            .setUnhealthyCount(processes.size - healthyCount - crashedCount)
            .setCrashedCount(crashedCount)
            .build()
    }

    override suspend fun getRepairHistory(request: RepairHistoryRequest): RepairHistoryResponse {
        val entries =
            synchronized(stateLock) { repairHistory.values.toList() }
                .let { all ->
                    if (request.processId.isNotBlank()) {
                        all.filter { it.processId == request.processId }
                    } else {
                        all.toList()
                    }
                }.sortedByDescending { it.timestamp }
                .let { if (request.limit > 0) it.take(request.limit) else it }
        return RepairHistoryResponse
            .newBuilder()
            .addAllEntries(entries)
            .build()
    }

    override suspend fun approveRepair(request: RepairApproval): RepairApprovalResponse {
        val pending =
            synchronized(stateLock) {
                pendingRepairs[request.repairId]?.takeUnless { it.claimed }?.also { it.claimed = true }
            }
                ?: return RepairApprovalResponse
                    .newBuilder()
                    .setApplied(false)
                    .setResultMessage("No pending repair found: ${request.repairId}")
                    .build()

        return try {
            executeApproval(request, pending)
        } finally {
            synchronized(stateLock) { pendingRepairs.remove(request.repairId) }
            pendingSlots.release()
        }
    }

    private suspend fun executeApproval(
        request: RepairApproval,
        pending: PendingRepair,
    ): RepairApprovalResponse =
        if (request.approved) {
            logger.info("Repair {} for process {} approved by user", request.repairId, pending.processId)
            // The response says what happened to the repair, not that it was approved: a
            // caller cannot tell an applied repair from a dropped one otherwise.
            val result =
                try {
                    onRepairApproved(pending.processId, pending.action)
                } catch (e: CancellationException) {
                    // Before the Exception arm: CancellationException *is* an Exception, so
                    // catching it here would report a refusal when the caller hung up — the
                    // same dishonest reporting this method was rewritten to remove — and
                    // swallow the cancellation the coroutine machinery needs to see.
                    throw e
                } catch (e: Exception) {
                    logger.error("Failed to execute approved repair {}: {}", request.repairId, e.message)
                    ApprovalResult.Refused("Repair execution failed: ${e.message}")
                }
            when (result) {
                is ApprovalResult.Applied -> {
                    RepairApprovalResponse
                        .newBuilder()
                        .setApplied(true)
                        .setResultMessage(result.message)
                        .build()
                }

                is ApprovalResult.Refused -> {
                    logger.warn("Approved repair {} was not applied: {}", request.repairId, result.reason)
                    RepairApprovalResponse
                        .newBuilder()
                        .setApplied(false)
                        .setResultMessage(result.reason)
                        .build()
                }
            }
        } else {
            logger.info("Repair {} rejected by user: {}", request.repairId, request.userNotes)
            RepairApprovalResponse
                .newBuilder()
                .setApplied(false)
                .setResultMessage("Repair rejected: ${request.userNotes}")
                .build()
        }

    private fun exhausted(message: String) = Status.RESOURCE_EXHAUSTED.withDescription(message).asRuntimeException()

    override fun watchHealth(request: Empty): Flow<HealthEvent> = _healthEvents.asSharedFlow()

    private fun outcomeToStrategy(outcome: RepairOutcome): RepairStrategy =
        when (outcome) {
            is RepairOutcome.Restarted -> RepairStrategy.REPAIR_STRATEGY_RESTART
            is RepairOutcome.StateReset -> RepairStrategy.REPAIR_STRATEGY_RESET_STATE
            is RepairOutcome.ConfigPatched -> RepairStrategy.REPAIR_STRATEGY_PATCH_CONFIG
            is RepairOutcome.CodeFixProposed -> RepairStrategy.REPAIR_STRATEGY_PATCH_SOURCE
            is RepairOutcome.Escalated -> RepairStrategy.REPAIR_STRATEGY_ESCALATE
            is RepairOutcome.Failed -> RepairStrategy.REPAIR_STRATEGY_ESCALATE
        }

    private fun buildRepairAction(
        repairId: String,
        strategy: RepairStrategy,
        outcome: RepairOutcome,
        report: ProcessFailureReport,
    ): RepairAction {
        val builder =
            RepairAction
                .newBuilder()
                .setRepairId(repairId)
                .setStrategy(strategy)

        when (outcome) {
            is RepairOutcome.Restarted -> {
                builder
                    .setDescription("Process ${outcome.processId} restarted")
                    .setRestart(RestartAction.getDefaultInstance())
            }

            is RepairOutcome.StateReset -> {
                builder
                    .setDescription("Process ${outcome.processId} state reset")
                    .setResetState(
                        ResetStateAction
                            .newBuilder()
                            .setRestoreSnapshot(outcome.restoredSnapshotId != null)
                            .setSnapshotId(outcome.restoredSnapshotId ?: "")
                            .build(),
                    )
            }

            is RepairOutcome.ConfigPatched -> {
                builder
                    .setDescription(outcome.patchDescription)
                    .setPatchConfig(
                        PatchConfigAction
                            .newBuilder()
                            .setExplanation(outcome.patchDescription)
                            .build(),
                    )
            }

            is RepairOutcome.CodeFixProposed -> {
                builder
                    .setDescription("Code fix proposed for ${outcome.processId}")
                    .setRequiresUserApproval(true)
                    .setPatchSource(
                        PatchSourceAction
                            .newBuilder()
                            .setExplanation(outcome.diff)
                            .build(),
                    )
            }

            is RepairOutcome.Escalated -> {
                builder
                    .setDescription("Escalated: manual intervention required")
                    .setEscalate(
                        EscalateAction
                            .newBuilder()
                            .setReport(
                                DiagnosticReport
                                    .newBuilder()
                                    .setProcessId(report.processId)
                                    .setRootCauseAnalysis(outcome.report)
                                    .build(),
                            ).build(),
                    )
            }

            is RepairOutcome.Failed -> {
                builder
                    .setDescription("Repair failed: ${outcome.reason}")
                    .setEscalate(
                        EscalateAction
                            .newBuilder()
                            .setReport(
                                DiagnosticReport
                                    .newBuilder()
                                    .setProcessId(report.processId)
                                    .setRootCauseAnalysis(outcome.reason)
                                    .build(),
                            ).build(),
                    )
            }
        }

        return builder.build()
    }
}

/**
 * What became of a repair the operator approved.
 *
 * The approval RPC answers with `applied`, so whatever applies a repair has to be able to say
 * that it did not: a source patch or a rollback is not something this process can carry out on
 * its own, and a host that has wired nothing to carry it out must not look like a host that
 * did.
 */
sealed class ApprovalResult {
    /** The repair reached something that acted on it. [message] is returned to the caller. */
    data class Applied(
        val message: String,
    ) : ApprovalResult()

    /** Nothing acted on the repair. [reason] is returned to the caller. */
    data class Refused(
        val reason: String,
    ) : ApprovalResult()
}

/** A repair parked for approval, together with the process it is about. */
private data class PendingRepair(
    val processId: String,
    val action: RepairAction,
    var claimed: Boolean = false,
)

private fun noApprovalSinkReason(
    processId: String,
    action: RepairAction,
): String =
    "The approval was recorded but nothing applied it: this process has nothing wired to apply " +
        "a ${action.strategy} repair, so the proposal for process $processId is a proposal only"
