package ai.rever.boss.mcp

import ai.rever.boss.mcp.sandbox.DefaultMcpRiskEvaluator
import ai.rever.boss.mcp.sandbox.McpRiskAssessment
import ai.rever.boss.plugin.api.McpToolArgs

/**
 * A side-effect-free forecast of what the MCP registry would do with one tool *right now*.
 *
 * This is deliberately a model of the registry's admission path, not a second authorization
 * path. It never invokes a handler, creates an approval request, records to the ledger, or
 * inspects tool arguments. Invocation remains governed exclusively by [McpToolRegistryImpl].
 */
data class McpFlightPlanInput(
    val toolName: String,
    val providerId: String,
    val declaredReadOnly: Boolean,
    val isEnabled: Boolean,
    val isPermitted: Boolean,
    val policy: McpPolicyAction,
    val policyFaulted: Boolean,
)

/** Only an unreadable policy file makes the live policy resolver fail closed. */
fun mcpPolicyFaultBlocksInvocation(fault: McpPolicyFault?): Boolean = fault is McpPolicyFault.PersistedPolicyUnreadable

/** One visible checkpoint on an MCP tool's pre-execution route. */
data class McpFlightCheckpoint(
    val label: String,
    val detail: String,
    val state: McpFlightCheckpointState,
)

enum class McpFlightCheckpointState {
    CLEAR,
    WAITING,
    BLOCKED,
}

enum class McpFlightOutcome {
    READY_TO_RUN,
    AWAITING_OPERATOR,
    WITHHELD,
}

data class McpFlightPlan(
    val input: McpFlightPlanInput,
    val risk: McpRiskAssessment,
    val mutating: Boolean,
    val outcome: McpFlightOutcome,
    val summary: String,
    val checkpoints: List<McpFlightCheckpoint>,
)

/**
 * Forecast the registry's gate sequence using the current snapshots supplied by the UI.
 *
 * The `policy` argument must come from [McpPolicyEngine.policyFor] with the same provider and
 * `declaredReadOnly` value as the real invocation. Keeping that explicit makes this function
 * useful to a UI without accidentally becoming an alternate policy resolver.
 */
fun mcpFlightPlan(input: McpFlightPlanInput): McpFlightPlan {
    val risk =
        DefaultMcpRiskEvaluator().evaluateRisk(
            input.toolName,
            McpToolArgs(emptyMap()),
        )
    val mutating = McpMutatingToolCatalog.isMutating(input.toolName, input.declaredReadOnly)
    val availability = availabilityCheckpoint(input)
    val policy = policyCheckpoint(input)
    val approval = approvalCheckpoint(input)
    val outcome = outcomeFor(input)
    return McpFlightPlan(
        input = input,
        risk = risk,
        mutating = mutating,
        outcome = outcome,
        summary = summaryFor(outcome),
        checkpoints = listOf(availability, policy, approval, handlerCheckpoint(outcome)),
    )
}

private fun availabilityCheckpoint(input: McpFlightPlanInput): McpFlightCheckpoint =
    when {
        !input.isEnabled -> {
            McpFlightCheckpoint(
                "Tool switch",
                "This tool is switched off.",
                McpFlightCheckpointState.BLOCKED,
            )
        }

        !input.isPermitted -> {
            McpFlightCheckpoint(
                "Access",
                "Current permissions do not expose this tool.",
                McpFlightCheckpointState.BLOCKED,
            )
        }

        else -> {
            McpFlightCheckpoint(
                "Tool switch & access",
                "Enabled and available to this operator.",
                McpFlightCheckpointState.CLEAR,
            )
        }
    }

private fun policyCheckpoint(input: McpFlightPlanInput): McpFlightCheckpoint =
    when {
        input.policyFaulted -> {
            McpFlightCheckpoint(
                "Policy",
                "Policy storage is unhealthy, so access fails closed.",
                McpFlightCheckpointState.BLOCKED,
            )
        }

        input.policy == McpPolicyAction.DENY -> {
            McpFlightCheckpoint("Policy", "Current policy is DENY.", McpFlightCheckpointState.BLOCKED)
        }

        input.policy == McpPolicyAction.ASK -> {
            McpFlightCheckpoint("Policy", "Current policy is ASK.", McpFlightCheckpointState.WAITING)
        }

        else -> {
            McpFlightCheckpoint("Policy", "Current policy is ALLOW.", McpFlightCheckpointState.CLEAR)
        }
    }

private fun approvalCheckpoint(input: McpFlightPlanInput): McpFlightCheckpoint =
    when {
        !input.isEnabled || !input.isPermitted || input.policyFaulted || input.policy == McpPolicyAction.DENY -> {
            McpFlightCheckpoint(
                "Human approval",
                "No approval is requested because an earlier gate withholds this action.",
                McpFlightCheckpointState.BLOCKED,
            )
        }

        input.policy == McpPolicyAction.ASK -> {
            McpFlightCheckpoint(
                "Human approval",
                "An operator must approve before the handler can start.",
                McpFlightCheckpointState.WAITING,
            )
        }

        else -> {
            McpFlightCheckpoint(
                "Human approval",
                "No prompt is needed under the current policy.",
                McpFlightCheckpointState.CLEAR,
            )
        }
    }

private fun outcomeFor(input: McpFlightPlanInput): McpFlightOutcome =
    when {
        !input.isEnabled || !input.isPermitted || input.policyFaulted || input.policy == McpPolicyAction.DENY -> {
            McpFlightOutcome.WITHHELD
        }

        input.policy == McpPolicyAction.ASK -> {
            McpFlightOutcome.AWAITING_OPERATOR
        }

        else -> {
            McpFlightOutcome.READY_TO_RUN
        }
    }

private fun handlerCheckpoint(outcome: McpFlightOutcome): McpFlightCheckpoint =
    when (outcome) {
        McpFlightOutcome.READY_TO_RUN -> {
            McpFlightCheckpoint(
                "Handler",
                "Would run immediately if an agent invokes it.",
                McpFlightCheckpointState.CLEAR,
            )
        }

        McpFlightOutcome.AWAITING_OPERATOR -> {
            McpFlightCheckpoint(
                "Handler",
                "Held until the operator approves the request.",
                McpFlightCheckpointState.WAITING,
            )
        }

        McpFlightOutcome.WITHHELD -> {
            McpFlightCheckpoint(
                "Handler",
                "Will not run under the current conditions.",
                McpFlightCheckpointState.BLOCKED,
            )
        }
    }

private fun summaryFor(outcome: McpFlightOutcome): String =
    when (outcome) {
        McpFlightOutcome.READY_TO_RUN -> "Clear for execution"
        McpFlightOutcome.AWAITING_OPERATOR -> "Human approval required"
        McpFlightOutcome.WITHHELD -> "Action withheld"
    }
