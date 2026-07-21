package ai.rever.boss.orchestrator

import ai.rever.boss.ipc.ChildProcessBootstrap
import ai.rever.boss.ipc.proto.*
import ai.rever.boss.ipc.proto.KernelServiceGrpcKt
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Entry point for the Orchestrator process.
 *
 * Connects to the kernel, registers itself, starts the OrchestratorService gRPC server,
 * and drives self-healing via the RepairEngine.
 *
 * C2 fix: No local ProcessRegistry or ProcessSpawner — the orchestrator asks the kernel
 * to restart processes via KernelService.RequestShutdown, and the kernel's auto-respawn
 * handles the actual re-spawn.
 */
fun main() {
    val logger = LoggerFactory.getLogger("OrchestratorMain")
    logger.info("Orchestrator starting...")

    val bootstrap = ChildProcessBootstrap()
    val dataDir = File(
        System.getenv("BOSS_DATA_DIR") ?: "${System.getProperty("user.home")}/.boss"
    )

    val manifest = ProcessManifest.newBuilder()
        .setProcessId(bootstrap.processId)
        .setProcessType(ProcessType.PROCESS_TYPE_ORCHESTRATOR)
        .setDisplayName("BOSS Orchestrator")
        .setVersion("1.0.0")
        .setMainClass("ai.rever.boss.orchestrator.OrchestratorMainKt")
        .setBehaviorSpec(
            "AI-powered self-healing orchestrator. Monitors all processes, diagnoses failures " +
            "using manifest repair hints and error pattern matching, and executes repair strategies " +
            "including restart, state reset, config patch, source patch, and escalation."
        )
        .addAllSourceFiles(listOf(
            "boss-orchestrator/src/main/kotlin/ai/rever/boss/orchestrator/OrchestratorMain.kt",
            "boss-orchestrator/src/main/kotlin/ai/rever/boss/orchestrator/OrchestratorServiceImpl.kt",
            "boss-orchestrator/src/main/kotlin/ai/rever/boss/orchestrator/RepairEngine.kt",
            "boss-orchestrator/src/main/kotlin/ai/rever/boss/orchestrator/CrashAnalyzer.kt",
            "boss-orchestrator/src/main/kotlin/ai/rever/boss/orchestrator/SnapshotManager.kt",
        ))
        .addAllExposedServices(listOf("boss.ipc.v1.OrchestratorService"))
        .setHealthContract(
            HealthContract.newBuilder()
                .setHeartbeatIntervalMs(5000)
                .setStartupTimeoutMs(20000)
                .build()
        )
        .build()

    runBlocking {
        val connection = bootstrap.connect(manifest)
        val kernelStub = connection.kernelStub

        val snapshotManager = SnapshotManager(dataDir)
        val analyzer = CrashAnalyzer()

        // Use AI-powered repairs when AI_REPAIR_API_KEY or OPENAI_API_KEY is configured
        val repairApiKey = System.getenv("AI_REPAIR_API_KEY")
            ?: System.getenv("OPENAI_API_KEY")
        val aiClient: AiRepairClient? = if (!repairApiKey.isNullOrBlank()) {
            logger.info("AI repair client enabled (model={})", System.getenv("AI_REPAIR_MODEL") ?: "gpt-5.4")
            HttpAiRepairClient()
        } else {
            logger.info("AI_REPAIR_API_KEY / OPENAI_API_KEY not set — AI repair proposals disabled")
            null
        }

        // C2 fix: restart callback delegates to kernel via RequestShutdown.
        // The kernel's auto-respawn handles re-spawning processes with ON_FAILURE policy.
        val repairEngine = RepairEngine(
            analyzer = analyzer,
            snapshotManager = snapshotManager,
            aiClient = aiClient,
            onRequestRestart = { processId, _ ->
                kernelStub.requestShutdown(
                    ShutdownRequest.newBuilder()
                        .setProcessId(processId)
                        .setForce(false)
                        .setReason("RESTART_REQUESTED_BY_ORCHESTRATOR")
                        .build()
                )
                logger.info("Sent restart request to kernel for process: {}", processId)
            }
        )

        // C3 fix: approved repairs are executed via the repair engine
        val orchestratorService = OrchestratorServiceImpl(
            repairEngine = repairEngine,
            processRegistry = null,  // orchestrator doesn't have a local registry (C2 fix)
            onRepairApproved = { action ->
                logger.info("Executing approved repair: {}", action.repairId)
                // Re-run the repair strategy that was approved
                when (action.strategyValue) {
                    RepairStrategy.REPAIR_STRATEGY_RESTART_VALUE,
                    RepairStrategy.REPAIR_STRATEGY_RESTART_TUNED_VALUE -> {
                        val processId = when {
                            action.hasRestart() -> action.restart.let { action.repairId }
                            action.hasResetState() -> action.repairId
                            else -> action.repairId
                        }
                        kernelStub.requestShutdown(
                            ShutdownRequest.newBuilder()
                                .setProcessId(processId)
                                .setForce(false)
                                .setReason("APPROVED_REPAIR")
                                .build()
                        )
                    }
                    else -> logger.warn("Approved repair strategy {} not auto-executable", action.strategy)
                }
            }
        )
        connection.processServer.addService(orchestratorService)
        connection.startServer()
        logger.info("Orchestrator running on: {}", bootstrap.processAddress)

        connection.awaitTermination()
    }
}
