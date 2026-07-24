package ai.rever.boss.app.terminal

import ai.rever.boss.ipc.ChildProcessBootstrap
import ai.rever.boss.ipc.proto.*
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * Terminal App Service — provides PTY-backed terminal sessions in its own process.
 * Phase 5: Process isolation for terminal/editor/browser heavy components.
 */
fun main() {
    val logger = LoggerFactory.getLogger("TerminalServiceMain")
    logger.info("Terminal Service starting...")

    val bootstrap = ChildProcessBootstrap()

    val manifest =
        ProcessManifest
            .newBuilder()
            .setProcessId(bootstrap.processId)
            .setProcessType(ProcessType.PROCESS_TYPE_SERVICE)
            .setDisplayName("BOSS Terminal Service")
            .setVersion("1.0.0")
            .setMainClass("ai.rever.boss.app.terminal.TerminalServiceMainKt")
            .setBehaviorSpec(
                "Provides interactive PTY-backed terminal sessions with full ANSI support. " +
                    "Manages session lifecycle (create, resize, close) and streams I/O.",
            ).addAllSourceFiles(
                listOf(
                    "boss-app-terminal/src/main/kotlin/ai/rever/boss/app/terminal/TerminalServiceMain.kt",
                    "boss-app-terminal/src/main/kotlin/ai/rever/boss/app/terminal/TerminalServiceImpl.kt",
                ),
            ).addAllExposedServices(listOf("boss.ipc.v1.services.TerminalService"))
            .addAllCapabilities(
                listOf(
                    PluginCapability
                        .newBuilder()
                        .setAction("run_command")
                        .setInputSchemaJson(
                            """{"type":"object","properties":{"command":{"type":"string"},"workingDirectory":{"type":"string"}}}""",
                        ).setOutputSchemaJson(
                            """{"type":"object","properties":{"exitCode":{"type":"integer"},"sessionId":{"type":"string"}}}""",
                        ).setDescription("Run a command in a terminal session")
                        .build(),
                    PluginCapability
                        .newBuilder()
                        .setAction("create_session")
                        .setInputSchemaJson("""{"type":"object","properties":{"workingDirectory":{"type":"string"}}}""")
                        .setOutputSchemaJson("""{"type":"object","properties":{"sessionId":{"type":"string"}}}""")
                        .setDescription("Create a new interactive terminal session")
                        .build(),
                    PluginCapability
                        .newBuilder()
                        .setAction("send_input")
                        .setInputSchemaJson("""{"type":"object","properties":{"sessionId":{"type":"string"},"text":{"type":"string"}}}""")
                        .setOutputSchemaJson("""{"type":"object"}""")
                        .setDescription("Send input to an existing terminal session")
                        .build(),
                ),
            ).setHealthContract(
                HealthContract
                    .newBuilder()
                    .setHeartbeatIntervalMs(5000)
                    .setStartupTimeoutMs(20000)
                    .build(),
            ).addAllRepairHints(
                listOf(
                    RepairHint
                        .newBuilder()
                        .setFailurePattern(".*IOException.*broken.pipe.*")
                        .setSeverity(FailureSeverity.FAILURE_SEVERITY_TRANSIENT)
                        .setRepairStrategy(RepairStrategy.REPAIR_STRATEGY_RESTART)
                        .setDescription("PTY pipe disconnected — terminal sessions dropped")
                        .setSuggestedFix("Restart terminal service; active sessions will need to be recreated")
                        .build(),
                    RepairHint
                        .newBuilder()
                        .setFailurePattern(".*OutOfMemoryError.*")
                        .setSeverity(FailureSeverity.FAILURE_SEVERITY_DEGRADED)
                        .setRepairStrategy(RepairStrategy.REPAIR_STRATEGY_RESTART_TUNED)
                        .setDescription("Terminal service ran out of memory")
                        .setSuggestedFix("Restart with increased heap (-Xmx512m) and close idle sessions")
                        .build(),
                ),
            ).build()

    runBlocking {
        val connection = bootstrap.connect(manifest)
        val terminalService = TerminalServiceImpl()
        connection.processServer.addService(terminalService)
        connection.startServer()
        logger.info("Terminal Service running on: {}", bootstrap.processAddress)
        connection.awaitTermination()
    }
}
