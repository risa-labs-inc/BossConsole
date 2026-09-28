package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpPolicyAction
import ai.rever.boss.mcp.McpProactivePolicyOutcome
import ai.rever.boss.mcp.McpSectionPolicyChange
import ai.rever.boss.mcp.McpToolPolicyConfig
import ai.rever.boss.mcp.ruleFor
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
internal fun SectionConfirmation(
    tools: List<McpToolIdentity>,
    policy: McpToolPolicyConfig,
    selected: Set<McpToolKey>,
    dirty: Boolean,
    onApply: suspend (List<McpSectionPolicyChange>) -> McpProactivePolicyOutcome,
    onRefresh: () -> Unit,
    onSaving: (Boolean) -> Unit,
    onDone: () -> Unit,
    confirmLabel: String = "Confirm section changes",
) {
    val colors = BossTheme.colors
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var feedback by remember(tools, policy, selected) { mutableStateOf<String?>(null) }
    var riskConfirmed by remember(tools, policy, selected) { mutableStateOf(false) }
    val risks = remember(tools, policy, selected) { sensitiveAllows(tools, selected, policy) }
    if (dirty) {
        PolicyChangeSummary(tools, policy, selected)
        if (riskConfirmed) PolicyAllowRisks(risks, policy)
        Button(
            enabled = !saving,
            colors =
                ButtonDefaults.buttonColors(
                    backgroundColor = colors.signal,
                    contentColor = colors.onSignal,
                ),
            onClick = {
                if (risks.isNotEmpty() && !riskConfirmed) {
                    riskConfirmed = true
                    return@Button
                }
                val changes =
                    tools.map {
                        McpSectionPolicyChange(
                            it.toolName,
                            it.providerId,
                            it.expectedRevocation,
                            policy.ruleFor(it.toolName, it.providerId),
                            if (it.key in selected) McpPolicyAction.ALLOW else McpPolicyAction.DENY,
                        )
                    }
                feedback = null
                saving = true
                onSaving(true)
                scope.launch {
                    try {
                        val outcome = onApply(changes)
                        feedback = outcome.proactivePolicyMessage() ?: "Policies saved."
                        riskConfirmed = false
                        if (outcome == McpProactivePolicyOutcome.Saved ||
                            outcome == McpProactivePolicyOutcome.Refused
                        ) {
                            onDone()
                            onRefresh()
                        }
                    } finally {
                        saving = false
                        onSaving(false)
                    }
                }
            },
        ) { Text(policyConfirmLabel(saving, risks.isNotEmpty(), riskConfirmed, confirmLabel), fontSize = 12.sp) }
    }
    feedback?.let { Text(it, color = colors.textSecondary, fontSize = 12.sp) }
}

private fun policyConfirmLabel(
    saving: Boolean,
    risky: Boolean,
    reviewed: Boolean,
    normal: String,
): String =
    when {
        saving -> "Saving…"
        risky && !reviewed -> "Review sensitive allows"
        risky -> "Confirm sensitive allows"
        else -> normal
    }
