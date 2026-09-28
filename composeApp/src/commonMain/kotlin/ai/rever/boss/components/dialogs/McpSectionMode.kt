package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpPolicyAction
import ai.rever.boss.mcp.McpToolPolicyConfig
import ai.rever.boss.mcp.ruleFor

internal enum class McpSectionMode { All, View, Edit, Custom, None }

internal fun savedSectionMode(
    tools: List<McpToolIdentity>,
    policy: McpToolPolicyConfig,
): McpSectionMode {
    val explicit =
        tools.all {
            val rule = policy.ruleFor(it.toolName, it.providerId)
            rule == McpPolicyAction.ALLOW || rule == McpPolicyAction.DENY
        }
    val allowed =
        tools
            .filter { policy.ruleFor(it.toolName, it.providerId) == McpPolicyAction.ALLOW }
            .map { it.key }
            .toSet()
    if (!explicit || allowed.isEmpty()) {
        return if (explicit) McpSectionMode.None else McpSectionMode.Custom
    }
    return listOf(McpSectionMode.All, McpSectionMode.View, McpSectionMode.Edit)
        .firstOrNull { sectionSelection(tools, it) == allowed } ?: McpSectionMode.Custom
}
