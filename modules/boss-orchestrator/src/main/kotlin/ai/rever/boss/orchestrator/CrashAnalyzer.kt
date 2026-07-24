package ai.rever.boss.orchestrator

import ai.rever.boss.ipc.proto.ProcessFailureReport
import ai.rever.boss.ipc.proto.ProcessManifest
import ai.rever.boss.ipc.proto.RepairStrategy

/**
 * Analyzes process failure reports and determines root cause + repair strategy.
 *
 * Step 1: Pattern match against manifest's repairHints (regex against stack trace / error).
 * Step 2: Classify by well-known error type (OOM, NPE, IOException, etc.).
 * Step 3 (future): AI analysis using source files from manifest (quine property).
 */
class CrashAnalyzer {
    fun analyze(
        report: ProcessFailureReport,
        manifest: ProcessManifest?,
    ): DiagnosticResult {
        // Step 1: check manifest repair hints
        if (manifest != null) {
            for (hint in manifest.repairHintsList) {
                val pattern = hint.failurePattern
                if (pattern.isBlank()) continue
                val regex =
                    try {
                        Regex(pattern)
                    } catch (_: Exception) {
                        continue
                    }
                val combined = "${report.errorType}\n${report.errorMessage}\n${report.stackTrace}"
                if (regex.containsMatchIn(combined)) {
                    return DiagnosticResult(
                        rootCause = hint.description.ifBlank { "Pattern matched: $pattern" },
                        strategy = hint.repairStrategy,
                        confidence = Confidence.HIGH,
                        suggestedFix = hint.suggestedFix.ifBlank { null },
                    )
                }
            }
        }

        // Step 2: classify by error type
        val errorType = report.errorType
        val stackTrace = report.stackTrace

        return when {
            "OutOfMemoryError" in errorType || "OutOfMemoryError" in stackTrace -> {
                DiagnosticResult(
                    rootCause = "Process ran out of heap memory",
                    strategy = RepairStrategy.REPAIR_STRATEGY_RESTART_TUNED,
                    confidence = Confidence.HIGH,
                    suggestedFix = "Increase JVM heap with -Xmx flag",
                )
            }

            "NullPointerException" in errorType || "NullPointerException" in stackTrace -> {
                DiagnosticResult(
                    rootCause = "Null pointer dereference",
                    strategy = RepairStrategy.REPAIR_STRATEGY_RESTART,
                    confidence = Confidence.MEDIUM,
                )
            }

            "IOException" in errorType || "IOException" in stackTrace -> {
                DiagnosticResult(
                    rootCause = "IO operation failed",
                    strategy = RepairStrategy.REPAIR_STRATEGY_RESTART,
                    confidence = Confidence.MEDIUM,
                )
            }

            "StackOverflowError" in errorType || "StackOverflowError" in stackTrace -> {
                DiagnosticResult(
                    rootCause = "Stack overflow — possible infinite recursion",
                    strategy = RepairStrategy.REPAIR_STRATEGY_RESTART,
                    confidence = Confidence.MEDIUM,
                )
            }

            "NoClassDefFoundError" in errorType || "NoClassDefFoundError" in stackTrace -> {
                DiagnosticResult(
                    rootCause = "Class definition not found — possible classpath issue",
                    strategy = RepairStrategy.REPAIR_STRATEGY_ESCALATE,
                    confidence = Confidence.HIGH,
                    suggestedFix = "Verify process classpath and dependencies",
                )
            }

            else -> {
                DiagnosticResult(
                    rootCause = "Unknown failure: ${errorType.ifBlank { report.errorMessage }}",
                    strategy = RepairStrategy.REPAIR_STRATEGY_RESTART,
                    confidence = Confidence.LOW,
                )
            }
        }
    }
}

data class DiagnosticResult(
    val rootCause: String,
    val strategy: RepairStrategy,
    val confidence: Confidence,
    val suggestedFix: String? = null,
)

enum class Confidence { LOW, MEDIUM, HIGH }
