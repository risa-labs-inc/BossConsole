package ai.rever.boss.service.workspace

import ai.rever.boss.ipc.ChildProcessBootstrap
import ai.rever.boss.ipc.proto.*
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * Workspace Service — manages workspace lifecycle in its own process.
 * Phase 6: Extracted service for workspace data management.
 */
fun main() {
    val logger = LoggerFactory.getLogger("WorkspaceServiceMain")
    logger.info("Workspace Service starting...")

    val bootstrap = ChildProcessBootstrap()

    val manifest = ProcessManifest.newBuilder()
        .setProcessId(bootstrap.processId)
        .setProcessType(ProcessType.PROCESS_TYPE_SERVICE)
        .setDisplayName("BOSS Workspace Service")
        .setVersion("1.0.0")
        .setMainClass("ai.rever.boss.service.workspace.WorkspaceServiceMainKt")
        .setBehaviorSpec(
            "Manages workspace lifecycle: creation, loading, saving, and deletion. " +
            "Provides reactive state streaming for current workspace changes."
        )
        .addAllSourceFiles(listOf(
            "boss-service-workspace/src/main/kotlin/ai/rever/boss/service/workspace/WorkspaceServiceMain.kt",
            "boss-service-workspace/src/main/kotlin/ai/rever/boss/service/workspace/WorkspaceServiceImpl.kt",
        ))
        .addAllExposedServices(listOf("boss.ipc.v1.services.WorkspaceService"))
        .setHealthContract(
            HealthContract.newBuilder()
                .setHeartbeatIntervalMs(5000)
                .setStartupTimeoutMs(15000)
                .build()
        )
        .build()

    runBlocking {
        val connection = bootstrap.connect(manifest)
        val service = WorkspaceServiceImpl()
        connection.processServer.addService(service)
        connection.startServer()
        logger.info("Workspace Service running on: {}", bootstrap.processAddress)
        connection.awaitTermination()
    }
}
