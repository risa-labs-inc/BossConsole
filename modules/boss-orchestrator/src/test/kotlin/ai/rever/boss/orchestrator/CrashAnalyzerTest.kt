package ai.rever.boss.orchestrator

import ai.rever.boss.ipc.proto.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrashAnalyzerTest {
    private val analyzer = CrashAnalyzer()

    @org.junit.Test(timeout = 5000)
    fun `counted repetition expansion is rejected before compiling`() {
        for (pattern in listOf("((a{1000}){1000}){1000}", "\\Q[\\E((a{1000}){1000}){1000}")) {
            val manifest =
                ProcessManifest
                    .newBuilder()
                    .addRepairHints(
                        RepairHint.newBuilder().setFailurePattern(pattern),
                    ).build()
            assertEquals(Confidence.LOW, analyzer.analyze(report(errorMessage = "a"), manifest).confidence)
        }
    }

    @Test
    fun `literal braces remain supported`() {
        for (pattern in listOf("\\{123}", "[{]123}", "\\Q{123}\\E")) {
            val manifest =
                ProcessManifest
                    .newBuilder()
                    .addRepairHints(
                        RepairHint.newBuilder().setFailurePattern(pattern).setDescription("literal"),
                    ).build()
            assertEquals("literal", analyzer.analyze(report(errorMessage = "{123}"), manifest).rootCause)
        }
    }

    @org.junit.Test(timeout = 5000)
    fun `nested repetitions cannot monopolize the crash analyzer`() {
        val manifest =
            ProcessManifest
                .newBuilder()
                .addRepairHints(
                    RepairHint
                        .newBuilder()
                        .setFailurePattern("(a+)+$")
                        .setRepairStrategy(RepairStrategy.REPAIR_STRATEGY_ROLLBACK),
                ).build()
        val result = analyzer.analyze(report(errorMessage = "a".repeat(8000) + "!"), manifest)
        assertEquals(Confidence.LOW, result.confidence)
        assertTrue(result.rootCause.length <= RepairLimits.MESSAGE_CHARS + 32)
    }

    @Test
    fun `unsupported and oversized hints retain normal error classification`() {
        for (pattern in listOf("(Boom)\\1", "(?<=Boom)Error", "x".repeat(513), "(")) {
            val manifest =
                ProcessManifest
                    .newBuilder()
                    .addRepairHints(
                        RepairHint
                            .newBuilder()
                            .setFailurePattern(pattern)
                            .setRepairStrategy(RepairStrategy.REPAIR_STRATEGY_ROLLBACK),
                    ).build()
            val result = analyzer.analyze(report(errorType = "java.lang.OutOfMemoryError"), manifest)
            assertEquals(RepairStrategy.REPAIR_STRATEGY_RESTART_TUNED, result.strategy)
        }
    }

    @Test
    fun `hint count and inspected report fields have server ceilings`() {
        val manifest = ProcessManifest.newBuilder()
        repeat(RepairLimits.HINT_COUNT) {
            manifest.addRepairHints(RepairHint.newBuilder().setFailurePattern("never matches"))
        }
        manifest.addRepairHints(
            RepairHint
                .newBuilder()
                .setFailurePattern("Boom")
                .setRepairStrategy(RepairStrategy.REPAIR_STRATEGY_ROLLBACK),
        )
        assertEquals(Confidence.LOW, analyzer.analyze(report(errorMessage = "Boom"), manifest.build()).confidence)
        val lateMatch = report(stackTrace = "x".repeat(RepairLimits.STACK_CHARS) + "OutOfMemoryError")
        assertEquals(Confidence.LOW, analyzer.analyze(lateMatch, null).confidence)
    }

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
