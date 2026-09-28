package ai.rever.boss.components.dialogs

internal fun filterSavedPolicies(
    rules: List<McpSavedRule>,
    tools: List<McpToolIdentity>?,
    query: String,
    pluginNames: Map<String, String>,
    fallbackTools: List<McpToolIdentity> = emptyList(),
): List<McpSavedRule> {
    val known = tools ?: fallbackTools
    val term = query.trim()
    return rules.filter { rule ->
        // A scoped rule is matched against its own plugin, never against whichever registered
        // plugin happens to ship a tool of the same name.
        val tool =
            known.firstOrNull {
                it.toolName == rule.toolName && (rule.providerId == null || it.providerId == rule.providerId)
            }
        rule.toolName.contains(term, true) ||
            rule.providerId?.let {
                it.contains(term, true) || policySectionName(it, pluginNames).contains(term, true)
            } == true ||
            tool?.description?.contains(term, true) == true
    }
}
