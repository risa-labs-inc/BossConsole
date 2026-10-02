package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpPolicyAction
import ai.rever.boss.mcp.McpProactivePolicyOutcome

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

internal fun emptyRulesMessage(noSavedRules: Boolean): String =
    if (noSavedRules) "No saved rules. Default policies and session trust still apply." else "No matching saved rules."

internal fun McpToolIdentity.matchesPolicyQuery(query: String): Boolean =
    toolName.contains(query.trim(), ignoreCase = true) ||
        providerId.contains(query.trim(), ignoreCase = true) ||
        description.contains(query.trim(), ignoreCase = true)

/** Refusals refresh candidates but always require a new operator confirmation. */
internal fun McpProactivePolicyOutcome.proactivePolicyMessage(): String? =
    when (this) {
        McpProactivePolicyOutcome.Saved -> {
            null
        }

        McpProactivePolicyOutcome.Refused -> {
            "Policy changed. Candidates refreshed; review the current policy before trying again."
        }

        McpProactivePolicyOutcome.PolicyUnreadable -> {
            "Policy file unreadable: all tools are withheld. Preserve a backup, repair the file, and restart BOSS."
        }

        McpProactivePolicyOutcome.Denied -> {
            "Current policy already denies this tool. Review the provider policy or defaults before adding a rule."
        }

        is McpProactivePolicyOutcome.Failed -> {
            "Could not save this rule. Check the host log and storage, then try again."
        }
    }
