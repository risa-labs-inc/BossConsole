package ai.rever.boss.mcp.sandbox

import ai.rever.boss.plugin.api.McpToolArgs

/**
 * Abstraction for requesting human approval for an MCP tool invocation.
 *
 * The default event bus routes requests to a Compose approval dialog.
 * Tests and embedding hosts can supply a different handler.
 */
fun interface McpApprovalHandler {
    suspend fun requestApproval(
        toolName: String,
        args: McpToolArgs,
        assessment: McpRiskAssessment,
    ): Boolean
}
