package ai.rever.boss.mastery.orchestrator

import ai.rever.boss.ipc.ChildProcessBootstrap
import ai.rever.boss.ipc.proto.*
import ai.rever.boss.mastery.MasteryExecutor
import ai.rever.boss.process.ProcessRegistry
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * Entry point for the Mastery Orchestrator process.
 *
 * Connects to the kernel, registers itself, starts the MasteryService gRPC server,
 * and exposes workflow execution to the rest of the microkernel.
 */
fun main() {
    val logger = LoggerFactory.getLogger("MasteryOrchestratorMain")
    logger.info("Mastery Orchestrator starting...")

    val bootstrap = ChildProcessBootstrap()

    val manifest = ProcessManifest.newBuilder()
        .setProcessId(bootstrap.processId)
        .setProcessType(ProcessType.PROCESS_TYPE_SERVICE)
        .setDisplayName("BOSS Mastery Orchestrator")
        .setVersion("1.0.0")
        .setMainClass("ai.rever.boss.mastery.orchestrator.MasteryOrchestratorMainKt")
        .setBehaviorSpec(
            "Mastery workflow orchestrator. Executes DAG workflows that compose plugin capabilities. " +
            "Stores mastery definitions in memory, resolves capabilities from registered plugin processes, " +
            "and streams real-time progress events during execution."
        )
        .addAllSourceFiles(listOf(
            "boss-mastery-orchestrator/src/main/kotlin/ai/rever/boss/mastery/orchestrator/MasteryOrchestratorMain.kt",
            "boss-mastery-orchestrator/src/main/kotlin/ai/rever/boss/mastery/orchestrator/MasteryServiceImpl.kt",
            "boss-mastery-orchestrator/src/main/kotlin/ai/rever/boss/mastery/orchestrator/ProcessRegistryCapabilityResolver.kt",
        ))
        .addAllExposedServices(listOf("boss.ipc.v1.MasteryService"))
        .setHealthContract(
            HealthContract.newBuilder()
                .setHeartbeatIntervalMs(5000)
                .setStartupTimeoutMs(20000)
                .build()
        )
        .build()

    runBlocking {
        val connection = bootstrap.connect(manifest)

        val processRegistry = ProcessRegistry()
        val capabilityResolver = ProcessRegistryCapabilityResolver(processRegistry)
        val masteryExecutor = MasteryExecutor(capabilityResolver)
        val masteryService = MasteryServiceImpl(masteryExecutor)

        connection.processServer.addService(masteryService)
        connection.startServer()

        logger.info("Mastery Orchestrator running on: {}", bootstrap.processAddress)
        connection.awaitTermination()
    }
}
