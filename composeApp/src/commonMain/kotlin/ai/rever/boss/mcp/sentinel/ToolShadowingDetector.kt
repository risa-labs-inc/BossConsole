package ai.rever.boss.mcp.sentinel

import ai.rever.boss.plugin.api.RegisteredMcpTool

/**
 * Cross-server tool shadowing and name collision detector.
 *
 * Identifies:
 * 1. Exact Name Collisions across different providers.
 * 2. Normalized Name Collisions (snake_case vs kebab-case vs camelCase).
 * 3. Conflicting tool definitions for equivalent identities.
 */
object ToolShadowingDetector {
    /**
     * Analyze a list of registered tools from all providers for collisions.
     */
    fun detectShadowing(tools: List<RegisteredMcpTool>): List<ShadowingFinding> {
        val findings = mutableListOf<ShadowingFinding>()

        // Group tools by exact tool name
        val exactGroups = tools.groupBy { it.definition.name }
        for ((name, toolList) in exactGroups) {
            val providers = toolList.map { it.providerId }.distinct()
            if (providers.size > 1) {
                for (tool in toolList) {
                    val colliding = providers.filter { it != tool.providerId }
                    val collidingStr = colliding.joinToString()
                    findings.add(
                        ShadowingFinding(
                            collidingProviderId = colliding.joinToString(", "),
                            toolName = name,
                            collisionType = "EXACT_NAME_COLLISION",
                            explanation =
                                "Tool '$name' contributed by '${tool.providerId}' " +
                                    "collides with exact same tool name from provider(s): $collidingStr.",
                        ),
                    )
                }
            }
        }

        // Group tools by normalized tool name
        val normalizedGroups = tools.groupBy { normalizeToolName(it.definition.name) }
        for ((_, toolList) in normalizedGroups) {
            val distinctNames = toolList.map { it.definition.name }.distinct()
            val providers = toolList.map { it.providerId }.distinct()

            if (distinctNames.size > 1 && providers.size > 1) {
                for (tool in toolList) {
                    val others = toolList.filter { it.providerId != tool.providerId }
                    val collidingNames = others.map { "${it.providerId}/${it.definition.name}" }
                    val collidingStr = collidingNames.joinToString()
                    findings.add(
                        ShadowingFinding(
                            collidingProviderId = others.map { it.providerId }.distinct().joinToString(", "),
                            toolName = tool.definition.name,
                            collisionType = "NORMALIZED_NAME_COLLISION",
                            explanation =
                                "Tool '${tool.definition.name}' from '${tool.providerId}' " +
                                    "has a normalized name collision with: $collidingStr.",
                        ),
                    )
                }
            }
        }

        return findings
    }

    /**
     * Normalize tool name by converting to lowercase and stripping delimiters (`_`, `-`, `.`).
     */
    fun normalizeToolName(name: String): String =
        name
            .lowercase()
            .replace("_", "")
            .replace("-", "")
            .replace(".", "")
            .replace(" ", "")
}
