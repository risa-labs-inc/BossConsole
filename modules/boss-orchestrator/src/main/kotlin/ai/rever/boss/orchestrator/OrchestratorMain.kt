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
    val dataDir =
        File(
            System.getenv("BOSS_DATA_DIR") ?: "${System.getProperty("user.home")}/.boss",
        )

    val manifest =
        ProcessManifest
            .newBuilder()
            .setProcessId(bootstrap.processId)
            .setProcessType(ProcessType.PROCESS_TYPE_ORCHESTRATOR)
            .setDisplayName("BOSS Orchestrator")
            .setVersion("1.0.0")
            .setMainClass("ai.rever.boss.orchestrator.OrchestratorMainKt")
            .setBehaviorSpec(
                "AI-powered self-healing orchestrator. Monitors all processes, diagnoses failures " +
                    "using manifest repair hints and error pattern matching, and executes repair strategies " +
                    "including restart, state reset, config patch, source patch, and escalation.",
            ).addAllSourceFiles(
                listOf(
                    "boss-orchestrator/src/main/kotlin/ai/rever/boss/orchestrator/OrchestratorMain.kt",
                    "boss-orchestrator/src/main/kotlin/ai/rever/boss/orchestrator/OrchestratorServiceImpl.kt",
                    "boss-orchestrator/src/main/kotlin/ai/rever/boss/orchestrator/RepairEngine.kt",
                    "boss-orchestrator/src/main/kotlin/ai/rever/boss/orchestrator/CrashAnalyzer.kt",
                    "boss-orchestrator/src/main/kotlin/ai/rever/boss/orchestrator/SnapshotManager.kt",
                ),
            ).addAllExposedServices(listOf("boss.ipc.v1.OrchestratorService"))
            .setHealthContract(
                HealthContract
                    .newBuilder()
                    .setHeartbeatIntervalMs(5000)
                    .setStartupTimeoutMs(20000)
                    .build(),
            ).build()

    runBlocking {
        val connection = bootstrap.connect(manifest)
        val kernelStub = connection.kernelStub

        val snapshotManager = SnapshotManager(dataDir)
        val analyzer = CrashAnalyzer()

        // AI repair sends the crashing process's source to a third-party model. That is a
        // data-egress decision, so it takes an explicit opt-in AND a named root to read from —
        // an API key sitting in the environment for some other purpose must never be enough to
        // start uploading source, which is exactly what keying off the key alone did.
        val repairApiKey =
            System.getenv("AI_REPAIR_API_KEY")
                ?: System.getenv("OPENAI_API_KEY")
        val aiRepair =
            aiRepairSettings(
                optIn = System.getenv("BOSS_AI_REPAIR"),
                projectRoot = System.getenv("BOSS_REPAIR_PROJECT_ROOT"),
                apiKey = repairApiKey,
            )

        val aiClient: AiRepairClient? =
            if (aiRepair.enabled) {
                val repairConfig = aiRepairConfigFromEnvironment()
                logger.warn(
                    "AI repair ENABLED (endpoint={}, model={}, source root={}) — " +
                        "crash source files will be sent off this machine",
                    repairConfig.endpoint,
                    repairConfig.model,
                    aiRepair.projectRoot,
                )
                HttpAiRepairClient(repairConfig)
            } else {
                logger.info(
                    "AI repair disabled (opt-in={}, root named={}, key present={}) — no source leaves this machine",
                    System.getenv("BOSS_AI_REPAIR") ?: "unset",
                    System.getenv("BOSS_REPAIR_PROJECT_ROOT") != null,
                    !repairApiKey.isNullOrBlank(),
                )
                null
            }

        // C2 fix: restart callback delegates to kernel via RequestShutdown.
        // The kernel's auto-respawn handles re-spawning processes with ON_FAILURE policy.
        val repairEngine =
            RepairEngine(
                analyzer = analyzer,
                snapshotManager = snapshotManager,
                aiClient = aiClient,
                // Stated rather than defaulted: this process has no project directory to
                // offer — its working directory is whatever the kernel spawned it with —
                // and manifest source files go to a third-party model, so nothing is read
                // until a host names a directory it is willing to send. Only honoured when
                // AI repair is switched on; a root without a client reads files nothing uses.
                projectRoot = aiRepair.projectRoot,
                onRequestRestart = { processId, _ ->
                    kernelStub.requestShutdown(
                        ShutdownRequest
                            .newBuilder()
                            .setProcessId(processId)
                            .setForce(false)
                            .setReason("RESTART_REQUESTED_BY_ORCHESTRATOR")
                            .build(),
                    )
                    logger.info("Sent restart request to kernel for process: {}", processId)
                },
            )

        // C3 fix: approved repairs are executed via the repair engine
        val orchestratorService =
            OrchestratorServiceImpl(
                repairEngine = repairEngine,
                processRegistry = null, // orchestrator doesn't have a local registry (C2 fix)
                onRepairApproved = { processId, action ->
                    applyApprovedRepair(processId, action) { target ->
                        kernelStub.requestShutdown(
                            ShutdownRequest
                                .newBuilder()
                                .setProcessId(target)
                                .setForce(false)
                                .setReason("APPROVED_REPAIR")
                                .build(),
                        )
                    }
                },
            )
        connection.processServer.addService(orchestratorService)
        connection.startServer()
        logger.info("Orchestrator running on: {}", bootstrap.processAddress)

        connection.awaitTermination()
    }
}

/**
 * Carries out a repair the operator approved, and says what happened to it.
 *
 * [processId] is the process the parked repair was reported for, which
 * [OrchestratorServiceImpl] keeps alongside the action: `RepairAction` has a `repair_id` and
 * no `process_id`, and a repair id is not something the kernel can act on.
 *
 * A restart is asked of the kernel through [requestShutdown], whose auto-respawn brings the
 * process back — the same single path an automatic restart takes. Every other strategy is
 * refused here rather than reported as done: applying a source patch, a config patch or a
 * rollback means writing to the machine, which this process does not do.
 *
 * Only PATCH_SOURCE sets `requires_user_approval` today, so in practice the refusal is the
 * branch that runs and the restart branch is there for a strategy that starts being parked.
 */
internal suspend fun applyApprovedRepair(
    processId: String,
    action: RepairAction,
    requestShutdown: suspend (processId: String) -> Unit,
): ApprovalResult =
    when (action.strategy) {
        RepairStrategy.REPAIR_STRATEGY_RESTART,
        RepairStrategy.REPAIR_STRATEGY_RESTART_TUNED,
        -> {
            requestShutdown(processId)
            ApprovalResult.Applied("Restart requested from the kernel for process $processId")
        }

        else -> {
            ApprovalResult.Refused(
                "Approved repair strategy ${action.strategy} is not something this process " +
                    "applies, so the repair for process $processId was recorded and not applied",
            )
        }
    }
