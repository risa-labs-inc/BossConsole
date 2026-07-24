package ai.rever.boss.orchestrator

import ai.rever.boss.ipc.proto.ProcessFailureReport
import ai.rever.boss.ipc.proto.ProcessManifest
import ai.rever.boss.ipc.proto.RepairStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Executes repair strategies for failing processes.
 *
 * Strategy selection:
 * - HIGH/MEDIUM confidence from CrashAnalyzer → use analyzer's strategy directly.
 * - LOW confidence → walk the [defaultLadder] based on consecutive failure count.
 *
 * For PATCH_SOURCE and PATCH_CONFIG strategies, an optional [aiClient] is consulted
 * to generate AI-powered proposals. If [aiClient] is null or returns null, sensible
 * static descriptions are used instead.
 *
 * Restart requests are delegated to [onRequestRestart] (typically calls
 * KernelService.RequestShutdown so the kernel handles the actual process lifecycle).
 */
class RepairEngine(
    private val analyzer: CrashAnalyzer,
    private val snapshotManager: SnapshotManager,
    private val aiClient: AiRepairClient? = null,
    /** Root directory used to resolve relative source file paths from process manifests. */
    private val projectRoot: String = System.getProperty("user.dir") ?: ".",
    /** Called to request a restart. Kernel handles the actual re-spawn. */
    private val onRequestRestart: suspend (processId: String, jvmArgsOverride: List<String>) -> Unit = { _, _ -> },
) {
    private val logger = LoggerFactory.getLogger(RepairEngine::class.java)

    // Escalation ladder applied when analyzer confidence is LOW
    private val defaultLadder =
        listOf(
            RepairStrategy.REPAIR_STRATEGY_RESTART,
            RepairStrategy.REPAIR_STRATEGY_RESTART,
            RepairStrategy.REPAIR_STRATEGY_RESET_STATE,
            RepairStrategy.REPAIR_STRATEGY_PATCH_CONFIG,
            RepairStrategy.REPAIR_STRATEGY_ESCALATE,
        )

    suspend fun handleFailure(report: ProcessFailureReport): RepairOutcome =
        withContext(Dispatchers.IO) {
            val processId = report.processId
            val manifest: ProcessManifest? = if (report.hasManifest()) report.manifest else null
            val diagnostic = analyzer.analyze(report, manifest)

            logger.info(
                "Handling failure for {}: rootCause={}, strategy={}, confidence={}",
                processId,
                diagnostic.rootCause,
                diagnostic.strategy,
                diagnostic.confidence,
            )

            val strategy =
                if (diagnostic.confidence != Confidence.LOW) {
                    diagnostic.strategy
                } else {
                    val idx = (report.consecutiveFailures - 1).coerceIn(0, defaultLadder.lastIndex)
                    defaultLadder[idx]
                }

            executeStrategy(processId, strategy, report, manifest, diagnostic)
        }

    private suspend fun executeStrategy(
        processId: String,
        strategy: RepairStrategy,
        report: ProcessFailureReport,
        manifest: ProcessManifest?,
        diagnostic: DiagnosticResult,
    ): RepairOutcome =
        when (strategy) {
            RepairStrategy.REPAIR_STRATEGY_RESTART -> {
                try {
                    onRequestRestart(processId, emptyList())
                    logger.info("Restart requested for process: {}", processId)
                    RepairOutcome.Restarted(processId)
                } catch (e: Exception) {
                    logger.error("Failed to request restart for process: {}", processId, e)
                    RepairOutcome.Failed(processId, "Restart request failed: ${e.message}")
                }
            }

            RepairStrategy.REPAIR_STRATEGY_RESTART_TUNED -> {
                try {
                    onRequestRestart(processId, listOf("-Xmx512m"))
                    logger.info("Tuned restart requested for process: {}", processId)
                    RepairOutcome.Restarted(processId)
                } catch (e: Exception) {
                    logger.error("Failed to request tuned restart for process: {}", processId, e)
                    RepairOutcome.Failed(processId, "Tuned restart request failed: ${e.message}")
                }
            }

            RepairStrategy.REPAIR_STRATEGY_RESET_STATE -> {
                try {
                    snapshotManager.cleanup(processId, keepLast = 0)
                    onRequestRestart(processId, emptyList())
                    logger.info("State reset + restart requested for process: {}", processId)
                    RepairOutcome.StateReset(processId)
                } catch (e: Exception) {
                    logger.error("Failed to reset state for process: {}", processId, e)
                    RepairOutcome.Failed(processId, "State reset failed: ${e.message}")
                }
            }

            RepairStrategy.REPAIR_STRATEGY_PATCH_CONFIG -> {
                logger.info("Config patch for process {} — consulting AI client", processId)

                val aiProposal =
                    aiClient?.proposeConfigFix(
                        processId = processId,
                        rootCause = diagnostic.rootCause,
                        suggestedFix = diagnostic.suggestedFix,
                        errorMessage = report.errorMessage,
                    )

                val description =
                    aiProposal?.toDescription()
                        ?: diagnostic.suggestedFix
                        ?: "Manual config review required"

                RepairOutcome.ConfigPatched(processId, description)
            }

            RepairStrategy.REPAIR_STRATEGY_PATCH_SOURCE -> {
                logger.info("Source patch for process {} — consulting AI client", processId)

                val sourceFiles = readSourceFiles(manifest)
                val aiProposal =
                    aiClient?.proposeSourceFix(
                        rootCause = diagnostic.rootCause,
                        sourceFiles = sourceFiles,
                        stackTrace = report.stackTrace,
                        errorMessage = report.errorMessage,
                    )

                val diff =
                    aiProposal?.toSummary()
                        ?: "// AI analysis unavailable — manual review required for: ${diagnostic.rootCause}"

                RepairOutcome.CodeFixProposed(processId, diff)
            }

            else -> {
                logger.warn("Escalating failure for process: {}", processId)
                RepairOutcome.Escalated(processId, buildEscalationReport(processId, report, diagnostic))
            }
        }

    /**
     * Reads source files listed in the process manifest.
     * Paths are tried as absolute first, then relative to [projectRoot].
     * Files that cannot be read are silently omitted.
     */
    private fun readSourceFiles(manifest: ProcessManifest?): Map<String, String> {
        if (manifest == null || manifest.sourceFilesList.isEmpty()) return emptyMap()
        return manifest.sourceFilesList
            .associateWith { path ->
                try {
                    val file =
                        File(path).takeIf { it.isAbsolute && it.isFile }
                            ?: File(projectRoot, path).takeIf { it.isFile }
                    file?.readText() ?: ""
                } catch (_: Exception) {
                    ""
                }
            }.filterValues { it.isNotBlank() }
    }

    private fun buildEscalationReport(
        processId: String,
        report: ProcessFailureReport,
        diagnostic: DiagnosticResult,
    ): String =
        buildString {
            appendLine("=== ESCALATION REPORT: $processId ===")
            appendLine("Root Cause: ${diagnostic.rootCause}")
            appendLine("Error Type: ${report.errorType}")
            appendLine("Error Message: ${report.errorMessage}")
            appendLine("Consecutive Failures: ${report.consecutiveFailures}")
            appendLine("Suggested Fix: ${diagnostic.suggestedFix ?: "None"}")
            if (report.stackTrace.isNotBlank()) {
                appendLine("Stack Trace:")
                appendLine(report.stackTrace.take(2000))
            }
        }
}

sealed class RepairOutcome {
    data class Restarted(
        val processId: String,
    ) : RepairOutcome()

    data class StateReset(
        val processId: String,
    ) : RepairOutcome()

    data class ConfigPatched(
        val processId: String,
        val patchDescription: String,
    ) : RepairOutcome()

    data class CodeFixProposed(
        val processId: String,
        val diff: String,
    ) : RepairOutcome()

    data class Escalated(
        val processId: String,
        val report: String,
    ) : RepairOutcome()

    data class Failed(
        val processId: String,
        val reason: String,
    ) : RepairOutcome()
}
