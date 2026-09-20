package ai.rever.boss.mcp.session

import ai.rever.boss.mcp.McpOperationRecord
import ai.rever.boss.mcp.McpToolRegistryImpl
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.pathutils.BossDirectories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.lang.management.ManagementFactory

/**
 * Lets an agent see its own governed session, exposed as `mcp__boss__session_*`.
 *
 * ## Why this can only exist inside BOSS
 *
 * Every MCP call is recorded by the host with the governance decision attached: which tool, which
 * provider, what the policy said, whether an operator approved it, how long it took, whether it
 * errored. No external MCP server has that, because none of them sit inside the governance
 * boundary. This is the one diagnostic BOSS can offer that a third party server structurally
 * cannot.
 *
 * ## The problem it solves
 *
 * Agents get stuck, and cannot tell. They repeat a call that has already failed three times, they
 * do not notice that a call was **denied by policy and never ran**, and they cannot answer "what
 * have I already tried". Nothing in the session, human or agent, can answer that today.
 *
 * ## Why two tools rather than one
 *
 * The split is on sensitivity, not convenience. `AGENTS.md` (issue #416) states that a ledger read
 * surface "must be host-implemented and permission-gated", warning that ungated access would
 * disclose "every other plugin's tool names, sanitized arguments and error snippets".
 *
 * Loop detection needs none of that content, so the useful half stays ungated:
 *
 * - [SESSION_REVIEW] returns counts, per tool statistics and detected loops. Tool names and
 *   numbers only. It is structurally incapable of leaking argument values or error text, because
 *   [SessionReport] has nowhere to put them.
 * - [SESSION_INSPECT_CALLS] returns the sensitive detail and is gated on [ACTIVITY_PERMISSION].
 *
 * Known consequence: a gated tool is hidden from a session with no signed in user, since
 * `mcpToolPermitted` checks `permissions.containsAll(...)` against an empty set for a non admin.
 * Admins bypass. That is the right fail closed posture for the sensitive half, and it is exactly
 * why the loop detection half is deliberately not gated.
 *
 * Both tools are `readOnly = true`: reading a log changes nothing.
 */
@Suppress("TooManyFunctions")
internal object SessionMcpToolProvider : McpToolProvider {
    const val SESSION_REVIEW: String = "session_review"
    const val SESSION_INSPECT_CALLS: String = "session_inspect_calls"

    /** Shaped after the permission `AGENTS.md` suggests for exactly this surface. */
    const val ACTIVITY_PERMISSION: String = "mcp.activity.read"

    private val json = Json { prettyPrint = false }

    override val providerId: String = "boss-session"

    /** Overridable so tests can point at a fixture ledger instead of the real one. */
    internal var ledgerFileProvider: () -> File = { BossDirectories.resolve("mcp-calls.jsonl") }

    /** Overridable so tests need no live registry. */
    internal var inMemoryProvider: () -> List<McpOperationRecord> = {
        McpToolRegistryImpl.ledger.recentOperations.value
    }

    /**
     * When this BOSS run started.
     *
     * The session boundary is the host process, which is the only definition that needs no state
     * of its own and cannot drift. Overridable for tests.
     */
    internal var sessionStartProvider: () -> Long = { ManagementFactory.getRuntimeMXBean().startTime }

    override fun tools(): List<McpToolDefinition> = listOf(reviewTool(), inspectTool())

    private fun reviewTool() =
        McpToolDefinition(
            name = SESSION_REVIEW,
            description =
                "Review your own MCP activity this session: how many calls you made, which failed, which were " +
                    "blocked by policy and never actually ran, and whether you are stuck repeating something. " +
                    "Call this when a task is not converging, before retrying a failing tool again, or to " +
                    "recall what you have already tried. Returns tool names and counts only, no arguments.",
            inputSchema =
                """
                {
                  "type": "object",
                  "properties": {
                    "since_minutes": {
                      "type": "integer", "minimum": 1,
                      "description": "Look back this many minutes. Defaults to the whole session."
                    }
                  }
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleReview(args) },
            readOnly = true,
        )

    private fun inspectTool() =
        McpToolDefinition.withRbac(
            name = SESSION_INSPECT_CALLS,
            description =
                "Show individual MCP calls from this session, including their sanitized arguments and error " +
                    "text. Use after session_review names a problem, to see exactly what was sent and what came " +
                    "back. Filter by tool name or to errors only.",
            handler = McpToolHandler { args -> handleInspect(args) },
            inputSchema =
                """
                {
                  "type": "object",
                  "properties": {
                    "tool_name": { "type": "string", "description": "Only calls to this tool." },
                    "errors_only": { "type": "boolean", "default": false, "description": "Only failed calls." },
                    "since_minutes": { "type": "integer", "minimum": 1, "description": "Look back window." },
                    "limit": { "type": "integer", "default": 10, "maximum": 50, "description": "Calls to return." }
                  }
                }
                """.trimIndent(),
            readOnly = true,
            // The sensitive half. See the class KDoc for why only this one is gated.
            requiredPermissions = listOf(ACTIVITY_PERMISSION),
        )

    // ---------------------------------------------------------------- handlers

    private suspend fun handleReview(args: McpToolArgs): McpToolResult {
        val window = loadWindow(args) ?: return error(INVALID_WINDOW)
        val report = SessionAnalyzer.analyze(window.records, window.sinceMs, System.currentTimeMillis())

        return ok(
            buildJsonObject {
                put("window_start_ms", report.windowStartMs)
                put("window_end_ms", report.windowEndMs)
                put("total_calls", report.totalCalls)
                put("error_calls", report.errorCalls)
                put("blocked_calls", report.blockedCalls)
                if (window.ledger.malformedLines > 0) put("unreadable_ledger_lines", window.ledger.malformedLines)
                if (window.ledger.truncated) {
                    put("truncated", true)
                    put("truncated_note", "The session is longer than the read window; older calls are not counted.")
                }
                if (report.totalCalls == 0) put("note", EMPTY_SESSION_NOTE)

                putLoops(report)
                putBlocked(report)
                putTools(report)
            },
        )
    }

    private fun JsonObjectBuilder.putLoops(report: SessionReport) {
        putJsonArray("loops") {
            report.loops.forEach { loop ->
                add(
                    buildJsonObject {
                        put("kind", loop.kind.name)
                        put("tool_name", loop.toolName)
                        loop.argsFingerprint?.let { put("args_fingerprint", it) }
                        put("occurrences", loop.occurrences)
                        put("error_count", loop.errorCount)
                        put("distinct_errors", loop.distinctErrorCount)
                        put("span_seconds", loop.spanSeconds)
                        put("advice", loop.advice)
                    },
                )
            }
        }
    }

    private fun JsonObjectBuilder.putBlocked(report: SessionReport) {
        putJsonArray("blocked") {
            report.blocked.forEach { group ->
                add(
                    buildJsonObject {
                        put("tool_name", group.toolName)
                        put("disposition", group.disposition)
                        put("count", group.count)
                    },
                )
            }
        }
        if (report.blocked.isNotEmpty()) put("blocked_note", BLOCKED_NOTE)
    }

    private fun JsonObjectBuilder.putTools(report: SessionReport) {
        putJsonArray("tools") {
            report.tools.forEach { stat ->
                add(
                    buildJsonObject {
                        put("tool_name", stat.toolName)
                        put("calls", stat.calls)
                        put("errors", stat.errors)
                        put("blocked", stat.blocked)
                        put("median_duration_ms", stat.medianDurationMs)
                        put("max_duration_ms", stat.maxDurationMs)
                    },
                )
            }
        }
    }

    private suspend fun handleInspect(args: McpToolArgs): McpToolResult {
        val window = loadWindow(args) ?: return error(INVALID_WINDOW)
        val toolFilter = args.string("tool_name")?.trim()?.takeIf { it.isNotEmpty() }
        val errorsOnly = args.boolean("errors_only") ?: false
        val limit = (args.int("limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        val matches =
            window.records
                .asReversed()
                .asSequence()
                .filter { toolFilter == null || it.toolName == toolFilter }
                .filter { !errorsOnly || it.isError }
                .take(limit)
                .toList()

        return ok(
            buildJsonObject {
                put("calls_matched", matches.size)
                put("calls_in_window", window.records.size)
                putJsonArray("calls") {
                    matches.forEach { record ->
                        add(
                            buildJsonObject {
                                put("timestamp_ms", record.timestamp)
                                put("tool_name", record.toolName)
                                put("provider_id", record.providerId)
                                put("policy", record.policyApplied.name)
                                put("disposition", record.approvalDisposition.name)
                                put("executed", SessionAnalyzer.outcomeOf(record.approvalDisposition).name)
                                put("duration_ms", record.durationMs)
                                put("is_error", record.isError)
                                put("args_fingerprint", SessionAnalyzer.fingerprint(record.sanitizedArgs))
                                record.errorSnippet?.let { put("error", it) }
                                putJsonObject("arguments") {
                                    record.sanitizedArgs.forEach { (key, value) -> put(key, value) }
                                }
                            },
                        )
                    }
                }
            },
        )
    }

    // ---------------------------------------------------------------- shared

    private class SessionWindow(
        val records: List<McpOperationRecord>,
        val sinceMs: Long,
        val ledger: LedgerWindow,
    )

    /** Null when `since_minutes` was given but unusable, so the caller can refuse rather than guess. */
    private suspend fun loadWindow(args: McpToolArgs): SessionWindow? {
        val requested = args.int("since_minutes")
        if (args.has("since_minutes") && (requested == null || requested < 1)) return null

        val sinceMs =
            requested
                ?.let { System.currentTimeMillis() - it * MILLIS_PER_MINUTE }
                ?: sessionStartProvider()

        val ledger =
            withContext(Dispatchers.IO) {
                SessionLedgerReader.read(
                    ledgerFile = ledgerFileProvider(),
                    inMemory = inMemoryProvider(),
                    sinceMs = sinceMs,
                )
            }
        return SessionWindow(ledger.records, sinceMs, ledger)
    }

    private fun ok(payload: JsonObject) = McpToolResult(json.encodeToString(JsonObject.serializer(), payload))

    private fun error(message: String) = McpToolResult(message, isError = true)

    private const val DEFAULT_LIMIT = 10
    private const val MAX_LIMIT = 50
    private const val MILLIS_PER_MINUTE = 60_000L

    private const val INVALID_WINDOW = "since_minutes must be a positive integer number of minutes."

    private const val EMPTY_SESSION_NOTE =
        "No MCP calls recorded in this window. If BOSS restarted recently the session log starts from that " +
            "restart; pass since_minutes to look further back."

    private const val BLOCKED_NOTE =
        "These calls did NOT run. Governance stopped them, so any conclusion you drew from their result is " +
            "unfounded. A denial needs an operator decision, not different arguments."
}
