package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpPolicyAction
import ai.rever.boss.mcp.McpToolPolicyConfig

/** A tool as one plugin contributes it. Two plugins can ship the same [toolName]. */
internal data class McpToolKey(
    val providerId: String,
    val toolName: String,
)

internal val McpToolIdentity.key: McpToolKey get() = McpToolKey(providerId, toolName)

/**
 * One row of the saved-rules list. A null [providerId] is an unscoped, name-wide rule (written
 * before rules carried a provider, or by hand) that answers for every plugin.
 */
data class McpSavedRule(
    val toolName: String,
    val providerId: String?,
    val action: McpPolicyAction,
)

/** Every persisted tool rule in [this], unscoped ones first, then by plugin and tool name. */
fun McpToolPolicyConfig.savedRules(): List<McpSavedRule> =
    (
        rules.map { (tool, action) -> McpSavedRule(tool, null, action) } +
            providerToolRules.flatMap { (provider, byTool) ->
                byTool.map { (tool, action) -> McpSavedRule(tool, provider, action) }
            }
    ).sortedWith(compareBy({ it.providerId != null }, { it.providerId }, { it.toolName }))

/** Which plugins the rule applies to, in words the operator can read. */
internal fun savedRuleScopeLabel(
    rule: McpSavedRule,
    pluginNames: Map<String, String>,
): String =
    rule.providerId?.let { "Plugin: ${policySectionName(it, pluginNames)}" }
        ?: "All plugins (saved before rules were per plugin)"
