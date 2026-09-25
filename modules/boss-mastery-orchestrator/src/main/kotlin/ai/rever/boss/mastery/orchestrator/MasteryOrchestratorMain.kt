package ai.rever.boss.mastery.orchestrator

import ai.rever.boss.ipc.ChildProcessBootstrap
import ai.rever.boss.ipc.ChildProcessConnection
import ai.rever.boss.ipc.proto.*
import ai.rever.boss.mastery.MasteryExecutor
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * Entry point for the Mastery Orchestrator process.
 *
 * Connects to the kernel, registers itself, starts the MasteryService gRPC server,
 * and exposes workflow execution to the rest of the microkernel. Workflow nodes
 * resolve plugin capabilities through the kernel connection - the only process
 * registry that any registration ever lands in.
 */
fun main() {
    val logger = LoggerFactory.getLogger("MasteryOrchestratorMain")
    logger.info("Mastery Orchestrator starting...")

    val bootstrap = ChildProcessBootstrap()

    val manifest =
        ProcessManifest
            .newBuilder()
            .setProcessId(bootstrap.processId)
            .setProcessType(ProcessType.PROCESS_TYPE_SERVICE)
            .setDisplayName("BOSS Mastery Orchestrator")
            .setVersion("1.0.0")
            .setMainClass("ai.rever.boss.mastery.orchestrator.MasteryOrchestratorMainKt")
            .setBehaviorSpec(
                "Mastery workflow orchestrator. Executes DAG workflows that compose plugin capabilities. " +
                    "Stores mastery definitions in memory, resolves capabilities through the kernel that owns " +
                    "the process registry, and streams real-time progress events during execution.",
            ).addAllSourceFiles(
                listOf(
                    "boss-mastery-orchestrator/src/main/kotlin/ai/rever/boss/mastery/orchestrator/MasteryOrchestratorMain.kt",
                    "boss-mastery-orchestrator/src/main/kotlin/ai/rever/boss/mastery/orchestrator/MasteryServiceImpl.kt",
                    "boss-mastery-orchestrator/src/main/kotlin/ai/rever/boss/mastery/orchestrator/" +
                        "KernelCapabilityResolver.kt",
                ),
            ).addAllExposedServices(listOf("boss.ipc.v1.MasteryService"))
            .setHealthContract(
                HealthContract
                    .newBuilder()
                    .setHeartbeatIntervalMs(5000)
                    .setStartupTimeoutMs(20000)
                    .build(),
            ).build()

    runBlocking {
        val connection = bootstrap.connect(manifest)

        val masteryExecutor = createMasteryExecutor(connection)
        val masteryService = MasteryServiceImpl(masteryExecutor)

        connection.processServer.addService(masteryService)
        connection.startServer()

        logger.info("Mastery Orchestrator running on: {}", bootstrap.processAddress)
        connection.awaitTermination()
    }
}

/**
 * Wire the executor so its nodes resolve plugin capabilities through [connection]'s
 * kernel stub.
 *
 * The kernel owns the only ProcessRegistry that is ever populated: children register
 * with it, and it brokers every capability invocation because peers cannot reach
 * each other directly. A resolver built on a local ProcessRegistry in this JVM - as
 * this orchestrator once did - can never see a registration, so every execution
 * failed "Process not found" at node time (#1061).
 * [KernelCapabilityResolver.verifyKernelMediation] turns that silent misconfiguration
 * into a loud startup failure instead of a feature-dead service.
 */
internal suspend fun createMasteryExecutor(connection: ChildProcessConnection): MasteryExecutor {
    val resolver = KernelCapabilityResolver(connection.kernelStub)
    resolver.verifyKernelMediation()
    return MasteryExecutor(resolver)
}
