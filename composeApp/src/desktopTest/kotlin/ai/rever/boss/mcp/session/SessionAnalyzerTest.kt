package ai.rever.boss.mcp.session

import ai.rever.boss.mcp.McpApprovalDisposition
import ai.rever.boss.mcp.McpOperationRecord
import ai.rever.boss.mcp.McpPolicyAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [SessionAnalyzer], where all the classification lives.
 *
 * Pure, no ledger file and no registry. Classification bugs are invisible in an integration test
 * that only checks the payload parses, so they are pinned here instead.
 */
class SessionAnalyzerTest {
    private var clock = 1_000_000L

    private fun call(
        tool: String,
        args: Map<String, String> = emptyMap(),
        isError: Boolean = false,
        error: String? = null,
        disposition: McpApprovalDisposition = McpApprovalDisposition.AUTO_ALLOWED,
        durationMs: Long = 10,
        advanceMs: Long = 1_000,
    ): McpOperationRecord {
        clock += advanceMs
        return McpOperationRecord(
            id = "id-$clock-${tool.hashCode()}-${args.hashCode()}",
            timestamp = clock,
            toolName = tool,
            providerId = "p",
            policyApplied = McpPolicyAction.ALLOW,
            approvalDisposition = disposition,
            durationMs = durationMs,
            isError = isError,
            sanitizedArgs = args,
            errorSnippet = error,
        )
    }

    private fun analyze(records: List<McpOperationRecord>): SessionReport {
        // Block body: ktlintFormat joins to 140 columns, detekt rejects above 120.
        return SessionAnalyzer.analyze(records, windowStartMs = 0, windowEndMs = clock + 1)
    }

    // ------------------------------------------------------------- outcomes

    @Test
    fun `every disposition is classified and the two asymmetric ones match the host`() {
        // An exhaustive when means adding a disposition is a compile error, but it cannot check
        // that the ANSWER is right. These two are the ones a reader gets wrong.
        assertEquals(
            CallOutcome.BLOCKED,
            SessionAnalyzer.outcomeOf(McpApprovalDisposition.POLICY_PERSIST_FAILED),
            "a failed approval write withholds the call",
        )
        assertEquals(
            CallOutcome.EXECUTED,
            SessionAnalyzer.outcomeOf(McpApprovalDisposition.PROVIDER_TRUST_PERSIST_FAILED),
            "the provider-wide path still runs the already-approved call; AGENTS.md calls this out",
        )

        // And nothing is left unclassified.
        McpApprovalDisposition.entries.forEach { SessionAnalyzer.outcomeOf(it) }
    }

    @Test
    fun `blocked calls are counted and grouped by disposition`() {
        val report =
            analyze(
                listOf(
                    call("k8s_delete", disposition = McpApprovalDisposition.POLICY_DENIED),
                    call("k8s_delete", disposition = McpApprovalDisposition.POLICY_DENIED),
                    call("run_command", disposition = McpApprovalDisposition.TIMEOUT),
                    call("read_file"),
                ),
            )

        assertEquals(4, report.totalCalls)
        assertEquals(3, report.blockedCalls)
        val denied = report.blocked.single { it.toolName == "k8s_delete" }
        assertEquals("POLICY_DENIED", denied.disposition)
        assertEquals(2, denied.count)
    }

    // ------------------------------------------------------------- loops

    @Test
    fun `an identical call failing three times is reported as a stuck loop`() {
        val args = mapOf("depth" to "2")
        val report = analyze(List(3) { call("codebase_tree", args, isError = true, error = "Could not scan: ") })

        val loop = report.loops.single()
        assertEquals(LoopKind.REPEATING_FAILURE, loop.kind)
        assertEquals("codebase_tree", loop.toolName)
        assertEquals(3, loop.occurrences)
        assertEquals(3, loop.errorCount)
        assertTrue(loop.advice.contains("Repeating it will fail again"))
    }

    @Test
    fun `two identical failures are not yet a loop`() {
        // The threshold is a judgement call, so it is pinned. Two failures is a retry, which is
        // normal and correct behaviour; calling it a loop would train the agent to ignore this.
        val report = analyze(List(2) { call("t", mapOf("a" to "1"), isError = true, error = "boom") })
        assertTrue(report.loops.isEmpty())
    }

    @Test
    fun `an identical call that sometimes succeeds is not a stuck loop`() {
        val args = mapOf("a" to "1")
        val report =
            analyze(
                listOf(
                    call("flaky", args, isError = true, error = "boom"),
                    call("flaky", args, isError = true, error = "boom"),
                    call("flaky", args, isError = false),
                ),
            )
        assertTrue(report.loops.none { it.kind == LoopKind.REPEATING_FAILURE }, "it recovered, so it is not stuck")
    }

    @Test
    fun `the same error across different arguments is flailing, not repetition`() {
        val report =
            analyze(
                listOf(
                    call("read_file", mapOf("path" to "a.kt"), isError = true, error = "Permission denied"),
                    call("read_file", mapOf("path" to "b.kt"), isError = true, error = "Permission denied"),
                    call("read_file", mapOf("path" to "c.kt"), isError = true, error = "Permission denied"),
                ),
            )

        val loop = report.loops.single()
        assertEquals(LoopKind.FLAILING, loop.kind)
        assertEquals(3, loop.occurrences)
        assertNull(loop.argsFingerprint, "flailing spans several argument sets, so there is no single one")
        // The advice must not send the agent round the same loop it is already in.
        assertTrue(loop.advice.contains("arguments are not the problem"))
    }

    @Test
    fun `error text is normalized so varying numbers still group`() {
        val report =
            analyze(
                listOf(
                    call("t", mapOf("a" to "1"), isError = true, error = "timed out after 31 ms"),
                    call("t", mapOf("a" to "2"), isError = true, error = "timed out after 4012 ms"),
                    call("t", mapOf("a" to "3"), isError = true, error = "timed out after 7 ms"),
                ),
            )
        assertEquals(LoopKind.FLAILING, report.loops.single().kind)
    }

    @Test
    fun `identical repeated successes are reported as redundant, not as failure`() {
        val args = mapOf("q" to "x")
        val report = analyze(List(5) { call("search", args) })

        val loop = report.loops.single()
        assertEquals(LoopKind.REDUNDANT, loop.kind)
        assertEquals(0, loop.errorCount)
        assertTrue(loop.advice.contains("reuse the result"))
    }

    @Test
    fun `a repeatedly denied tool is not called a loop`() {
        // Governance working is not the agent looping, and the advice would be actively wrong:
        // a denial needs an operator decision, not different arguments. It is already reported
        // under `blocked`.
        val report =
            analyze(
                List(4) {
                    call("k8s_delete", mapOf("ns" to "prod"), disposition = McpApprovalDisposition.POLICY_DENIED)
                },
            )

        assertTrue(report.loops.isEmpty(), "denials are governance, not a loop")
        assertEquals(4, report.blockedCalls)
    }

    @Test
    fun `loops are ordered with the most actionable first`() {
        val report =
            analyze(
                List(5) { call("cache", mapOf("k" to "v")) } +
                    List(3) { call("broken", mapOf("a" to "1"), isError = true, error = "nope") },
            )

        assertEquals(LoopKind.REPEATING_FAILURE, report.loops.first().kind)
        assertEquals(LoopKind.REDUNDANT, report.loops.last().kind)
    }

    // ------------------------------------------------------------- stats

    @Test
    fun `per tool statistics report median and max separately`() {
        val report =
            analyze(
                listOf(
                    call("slow", durationMs = 10),
                    call("slow", durationMs = 20),
                    call("slow", durationMs = 5_000),
                ),
            )

        val stat = report.tools.single()
        assertEquals(3, stat.calls)
        // Median, not mean: one 5 second outlier must not make the tool look uniformly slow.
        assertEquals(20, stat.medianDurationMs)
        assertEquals(5_000, stat.maxDurationMs)
    }

    @Test
    fun `an empty window analyzes to zeroes rather than throwing`() {
        val report = SessionAnalyzer.analyze(emptyList(), 0, 1)
        assertEquals(0, report.totalCalls)
        assertTrue(report.tools.isEmpty())
        assertTrue(report.loops.isEmpty())
    }

    // ------------------------------------------------------------- leak guard

    @Test
    fun `the report type cannot carry argument values or error text`() {
        // The structural guarantee behind the ungated tool: session_review cannot leak the
        // ledger's sensitive content because SessionReport has nowhere to put it. If someone adds
        // such a field, this test tells them the gating decision has to be revisited.
        val secretArg = "SUPER-SECRET-ARG-VALUE"
        val secretError = "SUPER-SECRET-ERROR-TEXT"
        val report =
            analyze(
                List(3) {
                    call("t", mapOf("token" to secretArg), isError = true, error = secretError)
                },
            )

        val rendered = report.toString()
        assertTrue(secretArg !in rendered, "an argument value reached the report")
        assertTrue(secretError !in rendered, "error text reached the report")
        // The fingerprint still distinguishes the group without disclosing it.
        assertTrue(
            report.loops
                .single()
                .argsFingerprint!!
                .isNotBlank(),
        )
    }

    @Test
    fun `the fingerprint is stable, order independent and different for different arguments`() {
        val a = SessionAnalyzer.fingerprint(mapOf("x" to "1", "y" to "2"))
        val b = SessionAnalyzer.fingerprint(mapOf("y" to "2", "x" to "1"))
        val c = SessionAnalyzer.fingerprint(mapOf("x" to "1", "y" to "3"))

        assertEquals(a, b, "key order must not change identity")
        assertTrue(a != c)
    }
}
