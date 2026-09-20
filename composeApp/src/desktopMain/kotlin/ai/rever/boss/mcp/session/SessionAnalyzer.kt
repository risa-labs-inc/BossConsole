package ai.rever.boss.mcp.session

import ai.rever.boss.mcp.McpApprovalDisposition
import ai.rever.boss.mcp.McpOperationRecord

/** Whether a recorded call actually ran, or was stopped before it could. */
internal enum class CallOutcome {
    EXECUTED,
    BLOCKED,
}

/** The shape of a repetition, ordered by how much an agent should care. */
internal enum class LoopKind {
    /** Same tool, identical arguments, repeatedly failing. The strongest stuck signal. */
    REPEATING_FAILURE,

    /** Same tool, same error, different arguments. Variations tried, same wall hit. */
    FLAILING,

    /** Same tool, identical arguments, repeatedly succeeding. Wasteful rather than broken. */
    REDUNDANT,
}

/**
 * A detected repetition.
 *
 * Carries **no argument values and no error text**, by construction rather than by discipline:
 * `session_review` is the ungated tool, and the only way to guarantee it cannot leak the ledger's
 * sensitive content is for the analysis type to have nowhere to put it. Identity is carried as
 * [argsFingerprint], a short hash, which is enough for an agent to tell two groups apart.
 */
internal data class LoopFinding(
    val kind: LoopKind,
    val toolName: String,
    val argsFingerprint: String?,
    val occurrences: Int,
    val errorCount: Int,
    val distinctErrorCount: Int,
    val spanSeconds: Long,
    val advice: String,
)

/** Per tool totals. Numbers and a tool name only. */
internal data class ToolStat(
    val toolName: String,
    val calls: Int,
    val errors: Int,
    val blocked: Int,
    val medianDurationMs: Long,
    val maxDurationMs: Long,
)

/** Calls stopped by governance, grouped by why. */
internal data class BlockedGroup(
    val toolName: String,
    val disposition: String,
    val count: Int,
)

internal data class SessionReport(
    val windowStartMs: Long,
    val windowEndMs: Long,
    val totalCalls: Int,
    val errorCalls: Int,
    val blockedCalls: Int,
    val tools: List<ToolStat>,
    val blocked: List<BlockedGroup>,
    val loops: List<LoopFinding>,
)

/**
 * Turns a window of ledger records into something an agent can act on.
 *
 * Pure: no clock, no file, no registry. Every branch is unit tested against fabricated records,
 * which matters because the interesting logic here is classification, and classification bugs are
 * invisible in an integration test that only checks the payload parses.
 */
// One detector per loop shape plus the shared helpers, all reading as one algorithm. Splitting
// them across files would separate the three shapes from the ordering that decides between them.
@Suppress("TooManyFunctions")
internal object SessionAnalyzer {
    /** Identical call repeated at least this many times, all failing, is a stuck loop. */
    const val REPEATING_FAILURE_THRESHOLD: Int = 3

    /** Same tool failing this many times with one error across differing arguments is flailing. */
    const val FLAILING_THRESHOLD: Int = 3

    /** Identical successful call repeated this many times is redundant work. */
    const val REDUNDANT_THRESHOLD: Int = 5

    private const val MAX_LOOPS_REPORTED = 10
    private const val MAX_TOOLS_REPORTED = 30
    private const val MAX_BLOCKED_REPORTED = 20

    /**
     * Did this call run?
     *
     * An exhaustive `when` with no `else`, deliberately. `AGENTS.md` records the same lesson for
     * `McpUnsuccessfulCategory`: a membership test against a set silently counts any disposition
     * added later as the default, while an exhaustive `when` makes it a compile error here and
     * forces whoever adds it to decide.
     *
     * Two entries are counter-intuitive and both come from the host's documented behaviour:
     *
     * - `POLICY_PERSIST_FAILED` is **blocked**. A failed approval write withholds the call.
     * - `PROVIDER_TRUST_PERSIST_FAILED` is **executed**. The provider-wide path deliberately still
     *   runs the already-approved call when only the persistence failed, falling back to session
     *   trust for that one tool. `AGENTS.md` calls this asymmetry out explicitly.
     */
    fun outcomeOf(disposition: McpApprovalDisposition): CallOutcome =
        when (disposition) {
            McpApprovalDisposition.AUTO_ALLOWED,
            McpApprovalDisposition.APPROVED_ONCE,
            McpApprovalDisposition.SESSION_TRUSTED,
            McpApprovalDisposition.PERSISTENTLY_ALLOWED,
            McpApprovalDisposition.PROVIDER_TRUSTED,
            McpApprovalDisposition.PROVIDER_TRUST_PERSIST_FAILED,
            -> CallOutcome.EXECUTED

            McpApprovalDisposition.PERSISTENTLY_DENIED,
            McpApprovalDisposition.POLICY_PERSIST_FAILED,
            McpApprovalDisposition.DENIED_BY_OPERATOR,
            McpApprovalDisposition.TIMEOUT,
            McpApprovalDisposition.POLICY_DENIED,
            McpApprovalDisposition.CANCELLED,
            McpApprovalDisposition.CANCELLED_AWAITING_APPROVAL,
            McpApprovalDisposition.CANCELLED_IN_FLIGHT,
            McpApprovalDisposition.QUEUE_FULL,
            -> CallOutcome.BLOCKED
        }

    fun analyze(
        records: List<McpOperationRecord>,
        windowStartMs: Long,
        windowEndMs: Long,
    ): SessionReport {
        val blockedRecords = records.filter { outcomeOf(it.approvalDisposition) == CallOutcome.BLOCKED }

        return SessionReport(
            windowStartMs = windowStartMs,
            windowEndMs = windowEndMs,
            totalCalls = records.size,
            errorCalls = records.count { it.isError },
            blockedCalls = blockedRecords.size,
            tools = toolStats(records),
            blocked = blockedGroups(blockedRecords),
            loops = detectLoops(records),
        )
    }

    private fun toolStats(records: List<McpOperationRecord>): List<ToolStat> =
        records
            .groupBy { it.toolName }
            .map { (tool, calls) ->
                val durations = calls.map { it.durationMs }.sorted()
                ToolStat(
                    toolName = tool,
                    calls = calls.size,
                    errors = calls.count { it.isError },
                    blocked = calls.count { outcomeOf(it.approvalDisposition) == CallOutcome.BLOCKED },
                    medianDurationMs = durations[durations.size / 2],
                    maxDurationMs = durations.last(),
                )
            }
            // Most calls first, so truncation drops the tools least worth reading about.
            .sortedWith(compareByDescending<ToolStat> { it.calls }.thenBy { it.toolName })
            .take(MAX_TOOLS_REPORTED)

    private fun blockedGroups(blocked: List<McpOperationRecord>): List<BlockedGroup> =
        blocked
            .groupBy { it.toolName to it.approvalDisposition }
            .map { (key, calls) -> BlockedGroup(key.first, key.second.name, calls.size) }
            .sortedWith(compareByDescending<BlockedGroup> { it.count }.thenBy { it.toolName })
            .take(MAX_BLOCKED_REPORTED)

    /**
     * The three repetition shapes.
     *
     * Only calls that actually ran are considered. A tool denied by policy five times is not an
     * agent looping, it is governance working, and it is already reported under `blocked`;
     * calling it a loop would tell the agent to change its arguments when the answer is that it
     * needs permission.
     */
    private fun detectLoops(records: List<McpOperationRecord>): List<LoopFinding> {
        val executed = records.filter { outcomeOf(it.approvalDisposition) == CallOutcome.EXECUTED }
        val findings = mutableListOf<LoopFinding>()

        val byCall = executed.groupBy { it.toolName to fingerprint(it.sanitizedArgs) }
        byCall.forEach { (key, calls) ->
            val errors = calls.count { it.isError }
            when {
                calls.size >= REPEATING_FAILURE_THRESHOLD && errors == calls.size -> {
                    findings += repeatingFailure(key.first, key.second, calls, errors)
                }

                calls.size >= REDUNDANT_THRESHOLD && errors == 0 -> {
                    findings += redundant(key.first, key.second, calls)
                }
            }
        }

        findings += flailing(executed)

        return findings
            .sortedWith(compareBy<LoopFinding> { it.kind.ordinal }.thenByDescending { it.occurrences })
            .take(MAX_LOOPS_REPORTED)
    }

    private fun repeatingFailure(
        tool: String,
        args: String,
        calls: List<McpOperationRecord>,
        errors: Int,
    ) = LoopFinding(
        kind = LoopKind.REPEATING_FAILURE,
        toolName = tool,
        argsFingerprint = args,
        occurrences = calls.size,
        errorCount = errors,
        distinctErrorCount = calls.mapNotNull { it.errorSnippet }.distinct().size,
        spanSeconds = spanSeconds(calls),
        advice =
            "Called $tool ${calls.size} times with identical arguments and it failed every time. " +
                "Repeating it will fail again. Change the arguments, fix the underlying cause, or " +
                "use a different tool. Call session_inspect_calls for the arguments and error text.",
    )

    private fun redundant(
        tool: String,
        args: String,
        calls: List<McpOperationRecord>,
    ) = LoopFinding(
        kind = LoopKind.REDUNDANT,
        toolName = tool,
        argsFingerprint = args,
        occurrences = calls.size,
        errorCount = 0,
        distinctErrorCount = 0,
        spanSeconds = spanSeconds(calls),
        advice =
            "Called $tool ${calls.size} times with identical arguments and it succeeded every time. " +
                "The answer is not changing; reuse the result you already have.",
    )

    /**
     * Same tool, same error, different arguments.
     *
     * Distinct from a repeating failure and the distinction matters to the advice: varying the
     * arguments has already been tried, so telling the agent to vary them again would send it
     * round the same loop it is already in.
     */
    private fun flailing(executed: List<McpOperationRecord>): List<LoopFinding> =
        executed
            .filter { it.isError && !it.errorSnippet.isNullOrBlank() }
            .groupBy { it.toolName to normalizeError(it.errorSnippet) }
            .filter { (_, calls) ->
                // Two conditions, and the second is what separates this from REPEATING_FAILURE:
                // there must be at least two distinct argument sets, or it is the same identical
                // call that the other detector has already reported.
                calls.size >= FLAILING_THRESHOLD &&
                    calls.distinctBy { fingerprint(it.sanitizedArgs) }.size >= 2
            }.map { (key, calls) ->
                LoopFinding(
                    kind = LoopKind.FLAILING,
                    toolName = key.first,
                    argsFingerprint = null,
                    occurrences = calls.size,
                    errorCount = calls.size,
                    distinctErrorCount = 1,
                    spanSeconds = spanSeconds(calls),
                    advice =
                        "Called ${key.first} ${calls.size} times with different arguments and got the same " +
                            "error each time. The arguments are not the problem. Check whether the tool is " +
                            "usable at all here, or whether a precondition is missing.",
                )
            }

    private fun spanSeconds(calls: List<McpOperationRecord>): Long {
        val times = calls.map { it.timestamp }
        return (times.max() - times.min()) / MILLIS_PER_SECOND
    }

    /**
     * A stable, short, non reversible identity for an argument set.
     *
     * A hash rather than the arguments themselves, so the ungated report can say "these two calls
     * were identical" without disclosing what they were.
     */
    fun fingerprint(args: Map<String, String>): String {
        val canonical = args.entries.sortedBy { it.key }.joinToString("\u0000") { "${it.key}=${it.value}" }
        return Integer.toHexString(canonical.hashCode()).padStart(FINGERPRINT_LENGTH, '0')
    }

    /** Collapses whitespace and digits so "timeout after 31ms" and "after 44ms" group together. */
    private fun normalizeError(snippet: String?): String =
        snippet
            .orEmpty()
            .lowercase()
            .replace(Regex("\\d+"), "#")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(ERROR_KEY_LENGTH)

    private const val MILLIS_PER_SECOND = 1000L
    private const val FINGERPRINT_LENGTH = 8
    private const val ERROR_KEY_LENGTH = 200
}
