package ai.rever.boss.mcp.sandbox

import ai.rever.boss.plugin.api.McpToolArgs

/**
 * Converts a risk assessment into a policy decision (ALLOW, DENY, REQUIRE_APPROVAL).
 *
 * Keeps risk classification separate from policy enforcement.
 */
fun interface McpSandboxPolicyGate {
    fun evaluatePolicy(
        toolName: String,
        args: McpToolArgs,
        assessment: McpRiskAssessment,
    ): McpToolDecision
}

/**
 * Default sandbox policy gate mapping risk levels to policy decisions.
 *
 * LOW -> ALLOW
 * MEDIUM -> ALLOW
 * HIGH -> REQUIRE_APPROVAL
 * CRITICAL -> REQUIRE_APPROVAL
 */
class DefaultMcpSandboxPolicyGate : McpSandboxPolicyGate {
    override fun evaluatePolicy(
        toolName: String,
        args: McpToolArgs,
        assessment: McpRiskAssessment,
    ): McpToolDecision =
        when (assessment.level) {
            McpRiskLevel.LOW -> McpToolDecision.ALLOW
            McpRiskLevel.MEDIUM -> McpToolDecision.ALLOW
            McpRiskLevel.HIGH -> McpToolDecision.REQUIRE_APPROVAL
            McpRiskLevel.CRITICAL -> McpToolDecision.REQUIRE_APPROVAL
        }
}
