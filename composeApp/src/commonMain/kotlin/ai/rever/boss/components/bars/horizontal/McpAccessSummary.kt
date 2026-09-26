package ai.rever.boss.components.bars.horizontal

import ai.rever.boss.components.overlays.ContextMenuItem

/**
 * Aggregates all consent metrics for the single "MCP access" bar item that replaced three
 * separate indicators. Pre-computed so rendering the bar item is a pure read.
 */
data class McpAccessSummary(
    val savedRules: Int = 0,
    val trustedPlugins: Int = 0,
    val sessionGrants: Int = 0,
    val yolo: Boolean = false,
    val yoloAvailable: Boolean = true,
) {
    /** True if any permissions exist, any session grant is live, or any MCP tool is registered. */
    fun isVisible(hasTools: Boolean): Boolean =
        savedRules > 0 ||
            trustedPlugins > 0 ||
            sessionGrants > 0 ||
            yolo ||
            hasTools

    val label: String
        get() = if (yolo) "MCP: YOLO" else "MCP access"

    fun sessionGrantsDescription(): String? =
        when (sessionGrants) {
            0 -> null
            else -> formatPlural(sessionGrants, "%d tool trusted for this session", "%d tools trusted for this session")
        }

    fun tooltip(): String =
        buildString {
            if (yolo) {
                append("YOLO mode is ON: policy checks and session prompts are bypassed.")
            } else {
                append("MCP access controls. Click to manage rules and trust.")
            }
        }
}

internal data class McpAccessMenuActions(
    val onPolicies: () -> Unit,
    val onSessionTrust: () -> Unit = {},
    val onTrustedPlugins: () -> Unit = {},
    val onSentinel: () -> Unit = {},
    val onYolo: () -> Unit = {},
)

private fun formatPlural(
    n: Int,
    one: String,
    many: String,
): String = "$n ${if (n == 1) one else many}"

/**
 * The menu behind the "MCP access" item, one entry per kind of grant.
 */
internal fun mcpAccessMenuItems(
    summary: McpAccessSummary,
    actions: McpAccessMenuActions,
): List<ContextMenuItem> =
    buildList {
        if (summary.yolo) {
            add(ContextMenuItem(text = "Turn off YOLO mode", onClick = actions.onYolo))
            add(ContextMenuItem(isDivider = true))
        }
        add(
            ContextMenuItem(
                text = if (summary.savedRules > 0) "Tool policies (${summary.savedRules})..." else "Tool policies...",
                onClick = actions.onPolicies,
            ),
        )
        if (summary.sessionGrants > 0) {
            val label = formatPlural(summary.sessionGrants, "Session trust (%d)...", "Session trust (%d)...")
            add(ContextMenuItem(text = label, onClick = actions.onSessionTrust))
        }
        if (summary.trustedPlugins > 0) {
            val label = formatPlural(summary.trustedPlugins, "Trusted plugins (%d)...", "Trusted plugins (%d)...")
            add(ContextMenuItem(text = label, onClick = actions.onTrustedPlugins))
        }
        add(ContextMenuItem(text = "MCP Sentinel (ToolDNA)...", onClick = actions.onSentinel))
        if (!summary.yolo && summary.yoloAvailable) {
            add(ContextMenuItem(isDivider = true))
            add(ContextMenuItem(text = "YOLO mode...", onClick = actions.onYolo))
        }
    }
