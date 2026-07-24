package ai.rever.boss.service.settings

import ai.rever.boss.ipc.ChildProcessBootstrap
import ai.rever.boss.ipc.proto.*
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * Settings Service — manages application settings in its own process.
 * Phase 6: Extracted service for persistent settings management.
 */
fun main() {
    val logger = LoggerFactory.getLogger("SettingsServiceMain")
    logger.info("Settings Service starting...")

    val bootstrap = ChildProcessBootstrap()

    val manifest =
        ProcessManifest
            .newBuilder()
            .setProcessId(bootstrap.processId)
            .setProcessType(ProcessType.PROCESS_TYPE_SERVICE)
            .setDisplayName("BOSS Settings Service")
            .setVersion("1.0.0")
            .setMainClass("ai.rever.boss.service.settings.SettingsServiceMainKt")
            .setBehaviorSpec(
                "Manages application settings with reactive streaming. " +
                    "Provides namespace-based key-value storage with watch support.",
            ).addAllSourceFiles(
                listOf(
                    "boss-service-settings/src/main/kotlin/ai/rever/boss/service/settings/SettingsServiceMain.kt",
                    "boss-service-settings/src/main/kotlin/ai/rever/boss/service/settings/SettingsServiceImpl.kt",
                ),
            ).addAllExposedServices(listOf("boss.ipc.v1.services.SettingsService"))
            .setHealthContract(
                HealthContract
                    .newBuilder()
                    .setHeartbeatIntervalMs(5000)
                    .setStartupTimeoutMs(10000)
                    .build(),
            ).build()

    runBlocking {
        val connection = bootstrap.connect(manifest)
        val service = SettingsServiceImpl()
        connection.processServer.addService(service)
        connection.startServer()
        logger.info("Settings Service running on: {}", bootstrap.processAddress)
        connection.awaitTermination()
    }
}
