package ai.rever.boss.mcp

import kotlinx.serialization.Serializable

/**
 * An immutable ledger entry recording a single MCP tool execution, rejection, or timeout.
 *
 * Persisted to `~/.boss/mcp-calls.jsonl` as an append-only JSONL record.
 * Arguments are strictly sanitized before recording so secrets/passwords/tokens never
 * land in the persistent log.
 */
@Serializable
data class McpOperationRecord(
    val id: String,
    val timestamp: Long,
    val toolName: String,
    val providerId: String,
    val policyApplied: McpPolicyAction,
    val approvalDisposition: McpApprovalDisposition,
    val durationMs: Long,
    val isError: Boolean,
    val sanitizedArgs: Map<String, String>,
    val errorSnippet: String? = null,
)
