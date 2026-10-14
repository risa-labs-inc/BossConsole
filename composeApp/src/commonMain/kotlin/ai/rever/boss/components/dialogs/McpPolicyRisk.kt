package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpMutatingToolCatalog
import ai.rever.boss.mcp.McpPolicyAction
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
    selected: Set<String>,
    rules: Map<String, McpPolicyAction>,
) = tools.filter {
    it.toolName in selected &&
        (
            policyRisk(it).level >= McpRiskLevel.HIGH ||
                McpMutatingToolCatalog.isMutating(it.toolName, it.readOnly) ||
                rules[it.toolName] == McpPolicyAction.DENY
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
    rules: Map<String, McpPolicyAction>,
    selected: Set<String>,
) {
    val changed =
        tools.filter {
            rules[it.toolName] != if (it.toolName in selected) McpPolicyAction.ALLOW else McpPolicyAction.DENY
        }
    val replaced = changed.mapNotNull { rules[it.toolName] }
    val defaultDenials = changed.count { it.toolName !in rules && it.toolName !in selected }
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
    rules: Map<String, McpPolicyAction>,
) {
    tools.forEach { tool ->
        val risk = policyRisk(tool)
        Text("${tool.toolName} · ${risk.level}: ${risk.reason}", color = BossTheme.colors.alert, fontSize = 12.sp)
        if (rules[tool.toolName] == McpPolicyAction.DENY) {
            Text(
                "Replaces an existing denial with unattended access.",
                color = BossTheme.colors.alert,
                fontSize = 12.sp,
            )
        }
    }
}
