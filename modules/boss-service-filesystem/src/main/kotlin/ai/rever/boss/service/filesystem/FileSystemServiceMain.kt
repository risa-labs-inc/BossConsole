package ai.rever.boss.service.filesystem

import ai.rever.boss.ipc.ChildProcessBootstrap
import ai.rever.boss.ipc.proto.*
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * FileSystem Service — provides file I/O operations in its own process.
 * Phase 6: Extracted service for file system operations.
 */
fun main() {
    val logger = LoggerFactory.getLogger("FileSystemServiceMain")
    logger.info("FileSystem Service starting...")

    val bootstrap = ChildProcessBootstrap()

    val manifest = ProcessManifest.newBuilder()
        .setProcessId(bootstrap.processId)
        .setProcessType(ProcessType.PROCESS_TYPE_SERVICE)
        .setDisplayName("BOSS FileSystem Service")
        .setVersion("1.0.0")
        .setMainClass("ai.rever.boss.service.filesystem.FileSystemServiceMainKt")
        .setBehaviorSpec(
            "Provides file system operations: directory scanning, file read/write, " +
            "rename, delete, and real-time change watching."
        )
        .addAllSourceFiles(listOf(
            "boss-service-filesystem/src/main/kotlin/ai/rever/boss/service/filesystem/FileSystemServiceMain.kt",
            "boss-service-filesystem/src/main/kotlin/ai/rever/boss/service/filesystem/FileSystemServiceImpl.kt",
        ))
        .addAllExposedServices(listOf("boss.ipc.v1.services.FileSystemService"))
        .addAllCapabilities(listOf(
            PluginCapability.newBuilder()
                .setAction("read_file")
                .setInputSchemaJson("""{"type":"object","properties":{"path":{"type":"string"}}}""")
                .setOutputSchemaJson("""{"type":"object","properties":{"content":{"type":"string"}}}""")
                .setDescription("Read file contents")
                .build(),
            PluginCapability.newBuilder()
                .setAction("write_file")
                .setInputSchemaJson("""{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}}}""")
                .setOutputSchemaJson("""{"type":"object","properties":{"success":{"type":"boolean"}}}""")
                .setDescription("Write content to a file")
                .build(),
        ))
        .setHealthContract(
            HealthContract.newBuilder()
                .setHeartbeatIntervalMs(5000)
                .setStartupTimeoutMs(10000)
                .build()
        )
        .build()

    runBlocking {
        val connection = bootstrap.connect(manifest)
        val service = FileSystemServiceImpl()
        connection.processServer.addService(service)
        connection.startServer()
        logger.info("FileSystem Service running on: {}", bootstrap.processAddress)
        connection.awaitTermination()
    }
}
