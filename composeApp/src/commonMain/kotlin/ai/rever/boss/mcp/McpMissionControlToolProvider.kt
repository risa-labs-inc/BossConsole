package ai.rever.boss.mcp

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Built-in host tool provider exposing Mission Control self-awareness and healing
 * tools to AI agents (Claude Code, Gemini CLI, Codex, OpenCode).
 */
object McpMissionControlToolProvider : McpToolProvider {
    override val providerId: String = "boss.mission_control"
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    override fun tools(): List<McpToolDefinition> =
        listOf(
            historyTool(),
            diagnoseTool(),
        )

    private fun historyTool(): McpToolDefinition =
        McpToolDefinition(
            name = "get_tool_history",
            description =
                "Get the execution history of recent MCP tool calls in BOSS. " +
                    "Allows an agent to inspect previous arguments, duration, and failure reasons for self-correction.",
            handler =
                McpToolHandler { args ->
                    val limit = args.int("limit") ?: 10
                    val statusFilter = args.string("status")?.uppercase()

                    val records = McpTelemetryRecorder.records.value
                    val filtered =
                        records
                            .asSequence()
                            .filter { record ->
                                if (statusFilter.isNullOrBlank()) {
                                    true
                                } else {
                                    record.status.name.equals(statusFilter, ignoreCase = true)
                                }
                            }.take(limit.coerceIn(1, 50))
                            .map { r ->
                                CallSummary(
                                    callId = r.callId,
                                    toolName = r.toolName,
                                    status = r.status.name,
                                    durationMs = r.durationMs,
                                    arguments = r.arguments,
                                    errorMessage = r.errorMessage,
                                )
                            }.toList()

                    McpToolResult(json.encodeToString(filtered))
                },
        ).apply { requiresAdmin = true }

    private fun diagnoseTool(): McpToolDefinition {
        return McpToolDefinition(
            name = "diagnose_last_failure",
            description =
                "Analyze the most recent failed, denied, or timed-out MCP tool call. " +
                    "Returns specific guidance and cause diagnosis for autonomous recovery.",
            handler =
                McpToolHandler {
                    val records = McpTelemetryRecorder.records.value
                    val lastFailure =
                        records.firstOrNull { r ->
                            r.status in
                                setOf(
                                    McpCallStatus.ERROR,
                                    McpCallStatus.TIMEOUT,
                                    McpCallStatus.DENIED,
                                    McpCallStatus.BLOCKED,
                                )
                        }

                    if (lastFailure == null) {
                        return@McpToolHandler McpToolResult("No recent tool failures found.")
                    }

                    val diagnosis =
                        when (lastFailure.status) {
                            McpCallStatus.DENIED -> {
                                "The operator actively denied this action via the Human-in-the-Loop approval gate. " +
                                    "Explain the intent to the operator before retrying."
                            }

                            McpCallStatus.TIMEOUT -> {
                                "The tool exceeded its execution deadline (${lastFailure.durationMs}ms). " +
                                    "If running a heavy build or network request, break it down or run asynchronously."
                            }

                            McpCallStatus.BLOCKED -> {
                                "The tool is disabled or requires elevated permissions. " +
                                    "Ask the operator to enable '${lastFailure.toolName}' in Toolbox -> MCP."
                            }

                            McpCallStatus.ERROR -> {
                                "The tool handler returned an error. Verify the argument types and payload syntax."
                            }

                            else -> {
                                "Check error message details."
                            }
                        }

                    val result =
                        DiagnosticReport(
                            callId = lastFailure.callId,
                            toolName = lastFailure.toolName,
                            status = lastFailure.status.name,
                            arguments = lastFailure.arguments,
                            errorDetails = lastFailure.errorMessage ?: "Unknown error",
                            durationMs = lastFailure.durationMs,
                            recommendedAction = diagnosis,
                        )

                    McpToolResult(json.encodeToString(result))
                },
        ).apply { requiresAdmin = true }
    }

    @Serializable
    private data class CallSummary(
        val callId: String,
        val toolName: String,
        val status: String,
        val durationMs: Long,
        val arguments: String,
        val errorMessage: String?,
    )

    @Serializable
    private data class DiagnosticReport(
        val callId: String,
        val toolName: String,
        val status: String,
        val arguments: String,
        val errorDetails: String,
        val durationMs: Long,
        val recommendedAction: String,
    )
}
