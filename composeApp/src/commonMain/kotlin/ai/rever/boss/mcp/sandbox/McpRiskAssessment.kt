package ai.rever.boss.mcp.sandbox

/**
 * Outcome of a risk evaluation for an MCP tool invocation.
 *
 * @property level The assessed risk level.
 * @property reason Human-readable justification for the risk assignment.
 */
data class McpRiskAssessment(
    val level: McpRiskLevel,
    val reason: String,
)
