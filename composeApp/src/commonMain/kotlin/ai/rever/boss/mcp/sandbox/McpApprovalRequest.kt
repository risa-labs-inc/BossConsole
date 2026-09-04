package ai.rever.boss.mcp.sandbox

import kotlinx.coroutines.CompletableDeferred

/**
 * Host-side request model representing a pending human approval decision for a sensitive MCP tool.
 *
 * Contains ONLY safe contextual information ([toolName], [riskLevel], [reason]).
 * Does NOT contain raw arguments, secret keys, or untrusted command strings.
 */
data class McpApprovalRequest(
    val toolName: String,
    val riskLevel: McpRiskLevel,
    val reason: String,
    val answer: CompletableDeferred<Boolean> = CompletableDeferred(),
)
