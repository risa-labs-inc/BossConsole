package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpFlightPlan

/** A registered tool plus its current, side-effect-free [McpFlightPlan]. */
data class McpFlightPlanView(
    val providerId: String,
    val toolName: String,
    val description: String,
    val inputSchema: String,
    val plan: McpFlightPlan,
) {
    val id: String get() = "$providerId/$toolName"
}
