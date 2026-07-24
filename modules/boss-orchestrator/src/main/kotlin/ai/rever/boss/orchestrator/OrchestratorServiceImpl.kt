package ai.rever.boss.orchestrator

import ai.rever.boss.ipc.proto.*
import ai.rever.boss.process.ProcessRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
    /** Called after a user-approved repair action (C3 fix). */
    private val onRepairApproved: suspend (RepairAction) -> Unit = {},
) : OrchestratorServiceGrpcKt.OrchestratorServiceCoroutineImplBase() {
    private val logger = LoggerFactory.getLogger(OrchestratorServiceImpl::class.java)

    private val repairHistory = ConcurrentHashMap<String, RepairHistoryEntry>()
    private val pendingRepairs = ConcurrentHashMap<String, RepairAction>()
    private val _healthEvents = MutableSharedFlow<HealthEvent>(extraBufferCapacity = 64)

    override suspend fun reportFailure(request: ProcessFailureReport): RepairAction {
        logger.info("Received failure report for process: {}", request.processId)

        val outcome = repairEngine.handleFailure(request)
        val repairId = UUID.randomUUID().toString()
        val strategy = outcomeToStrategy(outcome)
        val action = buildRepairAction(repairId, strategy, outcome, request)

        repairHistory[repairId] =
            RepairHistoryEntry
                .newBuilder()
                .setRepairId(repairId)
                .setProcessId(request.processId)
                .setStrategy(strategy)
                .setSuccess(outcome !is RepairOutcome.Failed)
                .setDescription(action.description)
                .setTimestamp(System.currentTimeMillis())
                .build()

        if (action.requiresUserApproval) {
            pendingRepairs[repairId] = action
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
            repairHistory.values
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
            pendingRepairs.remove(request.repairId)
                ?: return RepairApprovalResponse
                    .newBuilder()
                    .setApplied(false)
                    .setResultMessage("No pending repair found: ${request.repairId}")
                    .build()

        return if (request.approved) {
            logger.info("Repair {} approved by user", request.repairId)
            try {
                onRepairApproved(pending)
            } catch (e: Exception) {
                logger.error("Failed to execute approved repair {}: {}", request.repairId, e.message)
            }
            RepairApprovalResponse
                .newBuilder()
                .setApplied(true)
                .setResultMessage("Repair approved and execution initiated")
                .build()
        } else {
            logger.info("Repair {} rejected by user: {}", request.repairId, request.userNotes)
            RepairApprovalResponse
                .newBuilder()
                .setApplied(false)
                .setResultMessage("Repair rejected: ${request.userNotes}")
                .build()
        }
    }

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
                    .setResetState(ResetStateAction.getDefaultInstance())
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
