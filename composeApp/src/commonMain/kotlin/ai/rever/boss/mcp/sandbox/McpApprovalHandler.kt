package ai.rever.boss.mcp.sandbox

import ai.rever.boss.plugin.api.McpToolArgs

/**
 * Abstraction for requesting human approval for an MCP tool invocation.
 *
 * In Milestone 1, no interactive UI handler is attached by default.
 * In Milestone 2, a Compose UI approval dialog implementation will implement this interface.
 */
fun interface McpApprovalHandler {
    suspend fun requestApproval(
        toolName: String,
        args: McpToolArgs,
        assessment: McpRiskAssessment,
    ): Boolean
}
