package ai.rever.boss.mcp.sandbox

import ai.rever.boss.plugin.api.McpToolArgs

/**
 * Interface representing the complete Agent Tool Sandbox safety pipeline.
 */
interface McpToolSandbox {
    val riskEvaluator: McpRiskEvaluator
    val policyGate: McpSandboxPolicyGate
    val approvalHandler: McpApprovalHandler?

    suspend fun evaluateInvocation(
        toolName: String,
        args: McpToolArgs,
    ): McpSandboxOutcome
}

/**
 * Outcome of evaluating a tool invocation through the Agent Tool Sandbox.
 */
sealed interface McpSandboxOutcome {
    /** Execution is permitted. */
    object Allowed : McpSandboxOutcome

    /** Execution is denied by policy. */
    data class Denied(
        val assessment: McpRiskAssessment,
        val decision: McpToolDecision,
    ) : McpSandboxOutcome

    /** Human approval is required but no approval handler is installed (fail-closed). */
    data class RequiresApprovalUnresolved(
        val assessment: McpRiskAssessment,
    ) : McpSandboxOutcome
}

/**
 * Default implementation of [McpToolSandbox].
 */
class DefaultMcpToolSandbox(
    override val riskEvaluator: McpRiskEvaluator = DefaultMcpRiskEvaluator(),
    override val policyGate: McpSandboxPolicyGate = DefaultMcpSandboxPolicyGate(),
    override val approvalHandler: McpApprovalHandler? = McpApprovalEventBus,
) : McpToolSandbox {
    override suspend fun evaluateInvocation(
        toolName: String,
        args: McpToolArgs,
    ): McpSandboxOutcome {
        val assessment = riskEvaluator.evaluateRisk(toolName, args)
        val decision = policyGate.evaluatePolicy(toolName, args, assessment)

        return when (decision) {
            McpToolDecision.ALLOW -> {
                McpSandboxOutcome.Allowed
            }

            McpToolDecision.DENY -> {
                McpSandboxOutcome.Denied(assessment, decision)
            }

            McpToolDecision.REQUIRE_APPROVAL -> {
                val handler = approvalHandler
                if (handler != null) {
                    val approved = handler.requestApproval(toolName, args, assessment)
                    if (approved) {
                        McpSandboxOutcome.Allowed
                    } else {
                        McpSandboxOutcome.Denied(assessment, decision)
                    }
                } else {
                    McpSandboxOutcome.RequiresApprovalUnresolved(assessment)
                }
            }
        }
    }
}
