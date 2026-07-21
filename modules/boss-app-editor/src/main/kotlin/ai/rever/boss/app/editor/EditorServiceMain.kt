package ai.rever.boss.app.editor

import ai.rever.boss.ipc.ChildProcessBootstrap
import ai.rever.boss.ipc.proto.*
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * Editor App Service — provides code editing capabilities in its own process.
 * Phase 5: Isolates BossEditor + LSP + PSI from the main kernel process.
 */
fun main() {
    val logger = LoggerFactory.getLogger("EditorServiceMain")
    logger.info("Editor Service starting...")

    val bootstrap = ChildProcessBootstrap()

    val manifest = ProcessManifest.newBuilder()
        .setProcessId(bootstrap.processId)
        .setProcessType(ProcessType.PROCESS_TYPE_SERVICE)
        .setDisplayName("BOSS Editor Service")
        .setVersion("1.0.0")
        .setMainClass("ai.rever.boss.app.editor.EditorServiceMainKt")
        .setBehaviorSpec(
            "Provides code editing, LSP integration, PSI code analysis, and go-to-definition " +
            "navigation. Hosts BossEditor with language server support."
        )
        .addAllSourceFiles(listOf(
            "boss-app-editor/src/main/kotlin/ai/rever/boss/app/editor/EditorServiceMain.kt",
            "boss-app-editor/src/main/kotlin/ai/rever/boss/app/editor/EditorServiceImpl.kt",
        ))
        .addAllExposedServices(listOf("boss.ipc.v1.services.EditorService"))
        .addAllCapabilities(listOf(
            PluginCapability.newBuilder()
                .setAction("open_file")
                .setInputSchemaJson("""{"type":"object","properties":{"path":{"type":"string"}}}""")
                .setOutputSchemaJson("""{"type":"object","properties":{"content":{"type":"string"},"language":{"type":"string"}}}""")
                .setDescription("Open a file in the editor")
                .build(),
            PluginCapability.newBuilder()
                .setAction("write_file")
                .setInputSchemaJson("""{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}}}""")
                .setOutputSchemaJson("""{"type":"object","properties":{"success":{"type":"boolean"}}}""")
                .setDescription("Write content to a file")
                .build(),
            PluginCapability.newBuilder()
                .setAction("detect_main_functions")
                .setInputSchemaJson("""{"type":"object","properties":{"path":{"type":"string"}}}""")
                .setOutputSchemaJson("""{"type":"object","properties":{"functions":{"type":"array"}}}""")
                .setDescription("Detect main/entry-point functions using PSI analysis")
                .build(),
        ))
        .setHealthContract(
            HealthContract.newBuilder()
                .setHeartbeatIntervalMs(5000)
                .setStartupTimeoutMs(30000)
                .build()
        )
        .addAllRepairHints(listOf(
            RepairHint.newBuilder()
                .setFailurePattern(".*PSI.*initialization.*failed.*")
                .setSeverity(FailureSeverity.FAILURE_SEVERITY_DEGRADED)
                .setRepairStrategy(RepairStrategy.REPAIR_STRATEGY_RESTART)
                .setDescription("PSI/compiler subsystem failed to initialize")
                .setSuggestedFix("Restart editor service; code navigation will be unavailable until restart completes")
                .build(),
        ))
        .build()

    runBlocking {
        val connection = bootstrap.connect(manifest)
        val editorService = EditorServiceImpl()
        connection.processServer.addService(editorService)
        connection.startServer()
        logger.info("Editor Service running on: {}", bootstrap.processAddress)
        connection.awaitTermination()
    }
}
