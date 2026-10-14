package ai.rever.boss.components.bars.horizontal

import ai.rever.boss.components.dialogs.McpToolIdentity
import ai.rever.boss.mcp.McpPolicyAction
import ai.rever.boss.mcp.McpToolRegistryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun saveProactiveToolPolicy(
    tool: McpToolIdentity,
    action: McpPolicyAction,
) = withContext(Dispatchers.IO) {
    McpToolRegistryImpl.policyEngine.setToolPolicyIfAbsent(
        tool.toolName,
        action,
        expectedRevocation = tool.expectedRevocation,
        providerId = tool.providerId,
    )
}
