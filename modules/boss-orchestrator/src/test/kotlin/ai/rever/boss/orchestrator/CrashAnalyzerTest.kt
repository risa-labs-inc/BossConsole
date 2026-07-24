package ai.rever.boss.orchestrator

import ai.rever.boss.ipc.proto.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CrashAnalyzerTest {
    private val analyzer = CrashAnalyzer()

    private fun report(
        errorType: String = "",
        errorMessage: String = "",
        stackTrace: String = "",
        consecutiveFailures: Int = 1,
    ): ProcessFailureReport =
        ProcessFailureReport
            .newBuilder()
            .setErrorType(errorType)
            .setErrorMessage(errorMessage)
            .setStackTrace(stackTrace)
            .setConsecutiveFailures(consecutiveFailures)
            .build()

    @Test
    fun `pattern matching from manifest repair hints returns HIGH confidence`() {
        val manifest =
            ProcessManifest
                .newBuilder()
                .addRepairHints(
                    RepairHint
                        .newBuilder()
                        .setFailurePattern(".*SupabaseException.*network.*")
                        .setRepairStrategy(RepairStrategy.REPAIR_STRATEGY_RESTART)
                        .setDescription("Network connectivity issue")
                        .setSuggestedFix("Check network")
                        .build(),
                ).build()

        val result = analyzer.analyze(report(errorMessage = "SupabaseException: network timeout"), manifest)

        assertEquals(RepairStrategy.REPAIR_STRATEGY_RESTART, result.strategy)
        assertEquals(Confidence.HIGH, result.confidence)
        assertEquals("Network connectivity issue", result.rootCause)
        assertEquals("Check network", result.suggestedFix)
    }

    @Test
    fun `pattern in stack trace is matched from manifest`() {
        val manifest =
            ProcessManifest
                .newBuilder()
                .addRepairHints(
                    RepairHint
                        .newBuilder()
                        .setFailurePattern(".*JWT.*expired.*")
                        .setRepairStrategy(RepairStrategy.REPAIR_STRATEGY_RESET_STATE)
                        .setDescription("JWT token expired")
                        .build(),
                ).build()

        val result =
            analyzer.analyze(
                report(stackTrace = "at auth.JwtValidator: JWT has expired"),
                manifest,
            )

        assertEquals(RepairStrategy.REPAIR_STRATEGY_RESET_STATE, result.strategy)
        assertEquals(Confidence.HIGH, result.confidence)
    }

    @Test
    fun `OOM detection returns RESTART_TUNED with HIGH confidence`() {
        val result =
            analyzer.analyze(
                report(errorType = "java.lang.OutOfMemoryError", errorMessage = "GC overhead limit exceeded"),
                null,
            )

        assertEquals(RepairStrategy.REPAIR_STRATEGY_RESTART_TUNED, result.strategy)
        assertEquals(Confidence.HIGH, result.confidence)
        assertNotNull(result.suggestedFix)
    }

    @Test
    fun `OOM in stack trace is also detected`() {
        val result =
            analyzer.analyze(
                report(stackTrace = "Caused by: java.lang.OutOfMemoryError: Java heap space"),
                null,
            )
        assertEquals(RepairStrategy.REPAIR_STRATEGY_RESTART_TUNED, result.strategy)
    }

    @Test
    fun `NPE classification returns RESTART with MEDIUM confidence`() {
        val result =
            analyzer.analyze(
                report(errorType = "java.lang.NullPointerException"),
                null,
            )

        assertEquals(RepairStrategy.REPAIR_STRATEGY_RESTART, result.strategy)
        assertEquals(Confidence.MEDIUM, result.confidence)
    }

    @Test
    fun `IOException classification returns RESTART with MEDIUM confidence`() {
        val result =
            analyzer.analyze(
                report(errorType = "java.io.IOException", errorMessage = "Connection refused"),
                null,
            )

        assertEquals(RepairStrategy.REPAIR_STRATEGY_RESTART, result.strategy)
        assertEquals(Confidence.MEDIUM, result.confidence)
    }

    @Test
    fun `StackOverflowError returns RESTART with MEDIUM confidence`() {
        val result =
            analyzer.analyze(
                report(errorType = "java.lang.StackOverflowError"),
                null,
            )

        assertEquals(RepairStrategy.REPAIR_STRATEGY_RESTART, result.strategy)
        assertEquals(Confidence.MEDIUM, result.confidence)
    }

    @Test
    fun `NoClassDefFoundError escalates with HIGH confidence`() {
        val result =
            analyzer.analyze(
                report(errorType = "java.lang.NoClassDefFoundError", errorMessage = "ai/rever/boss/Missing"),
                null,
            )

        assertEquals(RepairStrategy.REPAIR_STRATEGY_ESCALATE, result.strategy)
        assertEquals(Confidence.HIGH, result.confidence)
        assertNotNull(result.suggestedFix)
    }

    @Test
    fun `unknown error falls back to RESTART with LOW confidence`() {
        val result =
            analyzer.analyze(
                report(errorType = "com.example.WeirdCustomException", errorMessage = "Something unexpected"),
                null,
            )

        assertEquals(RepairStrategy.REPAIR_STRATEGY_RESTART, result.strategy)
        assertEquals(Confidence.LOW, result.confidence)
        assertNull(result.suggestedFix)
    }

    @Test
    fun `manifest with no matching hint falls through to error type classification`() {
        val manifest =
            ProcessManifest
                .newBuilder()
                .addRepairHints(
                    RepairHint
                        .newBuilder()
                        .setFailurePattern(".*very.specific.pattern.*")
                        .setRepairStrategy(RepairStrategy.REPAIR_STRATEGY_ROLLBACK)
                        .build(),
                ).build()

        // NPE doesn't match the specific pattern, so falls to error type classification
        val result =
            analyzer.analyze(
                report(errorType = "java.lang.NullPointerException"),
                manifest,
            )

        assertEquals(RepairStrategy.REPAIR_STRATEGY_RESTART, result.strategy)
        assertEquals(Confidence.MEDIUM, result.confidence)
    }
}
