package ai.rever.boss.app.browser

import ai.rever.boss.ipc.ChildProcessBootstrap
import ai.rever.boss.ipc.proto.*
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * Browser App Service — provides JxBrowser-backed web browsing in its own process.
 * Phase 5: Isolates JxBrowser + Chromium from the kernel process.
 */
fun main() {
    val logger = LoggerFactory.getLogger("BrowserServiceMain")
    logger.info("Browser Service starting...")

    val bootstrap = ChildProcessBootstrap()

    val manifest = ProcessManifest.newBuilder()
        .setProcessId(bootstrap.processId)
        .setProcessType(ProcessType.PROCESS_TYPE_SERVICE)
        .setDisplayName("BOSS Browser Service")
        .setVersion("1.0.0")
        .setMainClass("ai.rever.boss.app.browser.BrowserServiceMainKt")
        .setBehaviorSpec(
            "Provides Chromium-based web browsing via JxBrowser. Handles navigation, " +
            "JavaScript execution, and streaming navigation events."
        )
        .addAllSourceFiles(listOf(
            "boss-app-browser/src/main/kotlin/ai/rever/boss/app/browser/BrowserServiceMain.kt",
            "boss-app-browser/src/main/kotlin/ai/rever/boss/app/browser/BrowserServiceImpl.kt",
        ))
        .addAllExposedServices(listOf("boss.ipc.v1.services.BrowserService"))
        .addAllCapabilities(listOf(
            PluginCapability.newBuilder()
                .setAction("navigate")
                .setInputSchemaJson("""{"type":"object","properties":{"url":{"type":"string"}}}""")
                .setOutputSchemaJson("""{"type":"object","properties":{"finalUrl":{"type":"string"},"title":{"type":"string"}}}""")
                .setDescription("Navigate to a URL")
                .build(),
            PluginCapability.newBuilder()
                .setAction("execute_js")
                .setInputSchemaJson("""{"type":"object","properties":{"script":{"type":"string"}}}""")
                .setOutputSchemaJson("""{"type":"object","properties":{"result":{"type":"string"}}}""")
                .setDescription("Execute JavaScript in the current page")
                .build(),
            PluginCapability.newBuilder()
                .setAction("screenshot")
                .setInputSchemaJson("""{"type":"object"}""")
                .setOutputSchemaJson("""{"type":"object","properties":{"imageBytes":{"type":"string","format":"base64"}}}""")
                .setDescription("Capture a screenshot of the current page")
                .build(),
        ))
        .setHealthContract(
            HealthContract.newBuilder()
                .setHeartbeatIntervalMs(5000)
                .setStartupTimeoutMs(45000)
                .build()
        )
        .addAllRepairHints(listOf(
            RepairHint.newBuilder()
                .setFailurePattern(".*JxBrowser.*initialization.*failed.*")
                .setSeverity(FailureSeverity.FAILURE_SEVERITY_FATAL)
                .setRepairStrategy(RepairStrategy.REPAIR_STRATEGY_ESCALATE)
                .setDescription("JxBrowser Chromium engine failed to initialize")
                .setSuggestedFix("Check JxBrowser license key and Chromium binary installation")
                .build(),
            RepairHint.newBuilder()
                .setFailurePattern(".*OutOfMemoryError.*")
                .setSeverity(FailureSeverity.FAILURE_SEVERITY_DEGRADED)
                .setRepairStrategy(RepairStrategy.REPAIR_STRATEGY_RESTART_TUNED)
                .setDescription("Browser process ran out of memory (common with heavy pages)")
                .setSuggestedFix("Restart browser service with increased heap (-Xmx1g)")
                .build(),
        ))
        .build()

    runBlocking {
        val connection = bootstrap.connect(manifest)
        val browserService = BrowserServiceImpl()
        connection.processServer.addService(browserService)
        connection.startServer()
        logger.info("Browser Service running on: {}", bootstrap.processAddress)
        connection.awaitTermination()
    }
}
