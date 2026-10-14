package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpPolicyAction

internal fun filterSavedPolicies(
    rules: Map<String, McpPolicyAction>,
    tools: List<McpToolIdentity>?,
    query: String,
    pluginNames: Map<String, String>,
    fallbackTools: List<McpToolIdentity> = emptyList(),
): Map<String, McpPolicyAction> {
    val byName = (tools ?: fallbackTools).associateBy { it.toolName }
    val term = query.trim()
    return rules.filterKeys { name ->
        name.contains(term, true) || byName[name]?.let {
            it.description.contains(term, true) || it.providerId.contains(term, true) ||
                policySectionName(it.providerId, pluginNames).contains(term, true)
        } == true
    }
}
