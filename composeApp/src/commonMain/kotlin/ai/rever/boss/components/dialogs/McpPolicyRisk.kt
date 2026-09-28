package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpMutatingToolCatalog
import ai.rever.boss.mcp.McpPolicyAction
import ai.rever.boss.mcp.McpToolPolicyConfig
import ai.rever.boss.mcp.ruleFor
import ai.rever.boss.mcp.sandbox.DefaultMcpRiskEvaluator
import ai.rever.boss.mcp.sandbox.McpRiskLevel
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp

internal fun policyRisk(tool: McpToolIdentity) =
    DefaultMcpRiskEvaluator().evaluateRisk(
        tool.toolName,
        McpToolArgs(emptyMap()),
    )

internal fun sensitiveAllows(
    tools: List<McpToolIdentity>,
    selected: Set<McpToolKey>,
    policy: McpToolPolicyConfig,
) = tools.filter {
    it.key in selected &&
        (
            policyRisk(it).level >= McpRiskLevel.HIGH ||
                McpMutatingToolCatalog.isMutating(it.toolName, it.readOnly) ||
                policy.ruleFor(it.toolName, it.providerId) == McpPolicyAction.DENY
        )
}

internal fun savedPolicyLabel(rule: McpPolicyAction?): String =
    when (rule) {
        null -> "No saved rule · default policy and session trust apply"
        McpPolicyAction.ASK -> "Saved: Ask before running"
        McpPolicyAction.ALLOW -> "Saved: Allow"
        McpPolicyAction.DENY -> "Saved: Deny"
    }

@Composable
internal fun PolicyChangeSummary(
    tools: List<McpToolIdentity>,
    policy: McpToolPolicyConfig,
    selected: Set<McpToolKey>,
) {
    val changed =
        tools.filter {
            val current = policy.ruleFor(it.toolName, it.providerId)
            current != if (it.key in selected) McpPolicyAction.ALLOW else McpPolicyAction.DENY
        }
    val replaced = changed.mapNotNull { policy.ruleFor(it.toolName, it.providerId) }
    val defaultDenials =
        changed.count {
            policy.ruleFor(it.toolName, it.providerId) == null && it.key !in selected
        }
    Text(
        "Save ${selected.size} Allow and ${tools.size - selected.size} Deny rules. " +
            "Replaces ${replaced.size} saved rules (${replaced.count { it == McpPolicyAction.DENY }} Deny, " +
            "${replaced.count { it == McpPolicyAction.ASK }} Ask). " +
            "$defaultDenials tools using defaults will be denied. " +
            "Applies to all agents and arguments across restarts. Future tools are not included.",
        color = BossTheme.colors.textSecondary,
        fontSize = 12.sp,
    )
}

@Composable
internal fun PolicyAllowRisks(
    tools: List<McpToolIdentity>,
    policy: McpToolPolicyConfig,
) {
    tools.forEach { tool ->
        val risk = policyRisk(tool)
        Text("${tool.toolName} · ${risk.level}: ${risk.reason}", color = BossTheme.colors.alert, fontSize = 12.sp)
        if (policy.ruleFor(tool.toolName, tool.providerId) == McpPolicyAction.DENY) {
            Text(
                "Replaces an existing denial with unattended access.",
                color = BossTheme.colors.alert,
                fontSize = 12.sp,
            )
        }
    }
}
