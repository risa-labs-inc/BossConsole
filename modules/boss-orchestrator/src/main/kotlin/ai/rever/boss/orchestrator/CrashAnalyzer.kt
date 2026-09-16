package ai.rever.boss.orchestrator

import ai.rever.boss.ipc.proto.ProcessFailureReport
import ai.rever.boss.ipc.proto.ProcessManifest
import ai.rever.boss.ipc.proto.RepairStrategy
import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException
import org.slf4j.LoggerFactory

/**
 * Analyzes process failure reports and determines root cause + repair strategy.
 *
 * Step 1: Pattern match against manifest's repairHints (regex against stack trace / error).
 * Step 2: Classify by well-known error type (OOM, NPE, IOException, etc.).
 * Step 3 (future): AI analysis using source files from manifest (quine property).
 */
class CrashAnalyzer {
    private val logger = LoggerFactory.getLogger(CrashAnalyzer::class.java)

    fun analyze(
        report: ProcessFailureReport,
        manifest: ProcessManifest?,
    ): DiagnosticResult {
        val errorType = report.errorType.take(RepairLimits.ERROR_TYPE_CHARS)
        val errorMessage = report.errorMessage.take(RepairLimits.MESSAGE_CHARS)
        val stackTrace = report.stackTrace.take(RepairLimits.STACK_CHARS)
        val combined = "$errorType\n$errorMessage\n$stackTrace"
        // Step 1: check manifest repair hints
        if (manifest != null) {
            if (manifest.repairHintsCount > RepairLimits.HINT_COUNT) {
                logger.warn("Repair hint count exceeds {}; ignoring excess hints", RepairLimits.HINT_COUNT)
            }
            for (hint in manifest.repairHintsList.take(RepairLimits.HINT_COUNT)) {
                val pattern = hint.failurePattern
                if (pattern.isBlank()) continue
                if (pattern.length > RepairLimits.PATTERN_CHARS) {
                    logger.warn("Repair hint pattern exceeds the size limit; using other hints or error classification")
                    continue
                }
                if (hasCountedRepetition(pattern)) {
                    logger.warn("Counted repetitions are unsupported in repair hints; using error classification")
                    continue
                }
                val regex =
                    try {
                        Pattern.compile(pattern)
                    } catch (_: PatternSyntaxException) {
                        logger.warn("Unsupported repair hint regex; using other hints or error classification")
                        continue
                    }
                if (regex.programSize() > RepairLimits.REGEX_INSTRUCTIONS) {
                    logger.warn("Repair hint regex exceeds the complexity limit; using error classification")
                    continue
                }
                if (regex.matcher(combined).find()) {
                    return DiagnosticResult(
                        rootCause = hint.description.take(RepairLimits.MESSAGE_CHARS).ifBlank { "Repair hint matched" },
                        strategy = hint.repairStrategy,
                        confidence = Confidence.HIGH,
                        suggestedFix = hint.suggestedFix.take(RepairLimits.MESSAGE_CHARS).ifBlank { null },
                    )
                }
            }
        }

        // Step 2: classify by error type
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
                    rootCause = "Stack overflow - possible infinite recursion",
                    strategy = RepairStrategy.REPAIR_STRATEGY_RESTART,
                    confidence = Confidence.MEDIUM,
                )
            }

            "NoClassDefFoundError" in errorType || "NoClassDefFoundError" in stackTrace -> {
                DiagnosticResult(
                    rootCause = "Class definition not found - possible classpath issue",
                    strategy = RepairStrategy.REPAIR_STRATEGY_ESCALATE,
                    confidence = Confidence.HIGH,
                    suggestedFix = "Verify process classpath and dependencies",
                )
            }

            else -> {
                DiagnosticResult(
                    rootCause = "Unknown failure: ${errorType.ifBlank { errorMessage }}",
                    strategy = RepairStrategy.REPAIR_STRATEGY_RESTART,
                    confidence = Confidence.LOW,
                )
            }
        }
    }

    // RE2 bounds matching, but expands counted repetitions during compilation. Reject them before allocation.
    // Escape and character-class handling preserves literal braces, including patterns that match source code.
    private fun hasCountedRepetition(pattern: String): Boolean {
        var index = 0
        while (index < pattern.length) {
            index =
                when (pattern[index]) {
                    '\\' -> {
                        skipEscape(pattern, index)
                    }

                    '[' -> {
                        skipCharacterClass(pattern, index)
                    }

                    '{' -> {
                        if (pattern.getOrNull(index + 1) in '0'..'9') return true
                        index + 1
                    }

                    else -> {
                        index + 1
                    }
                }
        }
        return false
    }

    private fun skipEscape(
        pattern: String,
        index: Int,
    ): Int {
        if (pattern.getOrNull(index + 1) != 'Q') return index + 2
        val end = pattern.indexOf("\\E", index + 2)
        return if (end < 0) pattern.length else end + 2
    }

    private fun skipCharacterClass(
        pattern: String,
        start: Int,
    ): Int {
        var index = start + 1
        if (pattern.getOrNull(index) == '^') index++
        if (pattern.getOrNull(index) == ']') index++
        while (index < pattern.length) {
            when (pattern[index]) {
                '\\' -> index += 2
                ']' -> return index + 1
                else -> index++
            }
        }
        return index
    }
}

data class DiagnosticResult(
    val rootCause: String,
    val strategy: RepairStrategy,
    val confidence: Confidence,
    val suggestedFix: String? = null,
)

enum class Confidence { LOW, MEDIUM, HIGH }
