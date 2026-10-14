package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpPolicyAction

internal enum class McpSectionMode { All, View, Edit, Custom, None }

internal fun savedSectionMode(
    tools: List<McpToolIdentity>,
    rules: Map<String, McpPolicyAction>,
): McpSectionMode {
    val explicit =
        tools.all {
            rules[it.toolName] == McpPolicyAction.ALLOW || rules[it.toolName] == McpPolicyAction.DENY
        }
    val allowed = tools.filter { rules[it.toolName] == McpPolicyAction.ALLOW }.map { it.toolName }.toSet()
    if (!explicit || allowed.isEmpty()) {
        return if (explicit) McpSectionMode.None else McpSectionMode.Custom
    }
    return listOf(McpSectionMode.All, McpSectionMode.View, McpSectionMode.Edit)
        .firstOrNull { sectionSelection(tools, it) == allowed } ?: McpSectionMode.Custom
}
