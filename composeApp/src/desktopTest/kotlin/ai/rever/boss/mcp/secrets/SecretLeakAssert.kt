package ai.rever.boss.mcp.secrets

import ai.rever.boss.mcp.McpApprovalRequest
import ai.rever.boss.mcp.McpOperationRecord
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogEntry
import ai.rever.boss.plugin.logging.LogLevel
import ai.rever.boss.plugin.logging.LogListener
import kotlin.test.fail

/**
 * The surfaces INV1 names, and one assertion over all of them.
 *
 * A "surface" here is anything the agent, the operator or a later reader could see: the result
 * text, every field of every ledger record, every field of every approval request, and every
 * host log entry captured while the call ran. [assertAbsent] checks each protected encoding of
 * the value against each surface and names the surface that leaked, so a failure says where.
 */
internal class SecretLeakAssert(
    private val value: String,
) {
    private val forms = McpResultScrubber.encodings(value)

    fun assertAbsent(
        surface: String,
        text: String?,
    ) {
        if (text == null) return
        for (form in forms) {
            if (text.contains(form)) fail("secret value ($form) appeared in $surface: $text")
        }
    }

    fun assertAbsentFromLedger(records: List<McpOperationRecord>) {
        records.forEachIndexed { i, r ->
            assertAbsent("ledger[$i].sanitizedArgs", r.sanitizedArgs.toString())
            assertAbsent("ledger[$i].errorSnippet", r.errorSnippet)
            assertAbsent("ledger[$i].secretRefs", r.secretRefs.toString())
            assertAbsent("ledger[$i].toolName", r.toolName)
        }
    }

    fun assertAbsentFromRequests(requests: List<McpApprovalRequest>) {
        requests.forEachIndexed { i, req ->
            assertAbsent("approval[$i].arguments", req.arguments.toString())
            assertAbsent("approval[$i].risk", req.riskAssessment?.reason)
            assertAbsent("approval[$i].secretRefs", req.secretRefs.joinToString { it.display })
            assertAbsent("approval[$i].toString", req.toString())
        }
    }

    fun assertAbsentFromLogs(entries: List<LogEntry>) {
        entries.forEachIndexed { i, e ->
            assertAbsent("log[$i].message", e.message)
            assertAbsent("log[$i].data", e.data?.toString())
            assertAbsent("log[$i].error", e.error?.toString())
        }
    }
}

/**
 * Captures every host log entry, at every level, for the duration of [block].
 *
 * The global level is lowered to TRACE so a DEBUG line that quoted a value would be caught
 * rather than filtered, and restored afterwards; listeners are synchronous, so nothing is
 * missed by timing.
 */
internal fun <T> captureHostLogs(block: () -> T): Pair<T, List<LogEntry>> {
    val entries = java.util.Collections.synchronizedList(ArrayList<LogEntry>())
    val listener = LogListener { entries.add(it) }
    val previous = BossLogger.globalLevel
    BossLogger.setGlobalLevel(LogLevel.TRACE)
    BossLogger.addListener(listener)
    return try {
        block() to entries.toList()
    } finally {
        BossLogger.removeListener(listener)
        BossLogger.setGlobalLevel(previous)
    }
}
