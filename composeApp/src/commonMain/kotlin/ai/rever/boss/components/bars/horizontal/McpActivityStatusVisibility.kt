package ai.rever.boss.components.bars.horizontal

import ai.rever.boss.plugin.api.RegisteredMcpTool

internal fun mcpActivityStatusShouldRender(
    hasRecentOperations: Boolean,
    hasRegisteredTools: Boolean,
    showActivityLog: Boolean,
    showFlightPlan: Boolean,
): Boolean = hasRecentOperations || hasRegisteredTools || showActivityLog || showFlightPlan

/**
 * [McpActivityStatusItem]'s registry-facing derivation: "has registered tools" is
 * `allTools`.isNotEmpty — every tool from active plugins — and NOT the exposed `tools`
 * set, which also drops user-disabled and permission-denied tools.
 *
 * Binding the exposed set instead would hide the status line exactly when every tool
 * is blocked, taking with it the entry point to the surfaces that explain the
 * blocking. Taking the registry snapshot as the parameter — rather than a precomputed
 * boolean — is what lets a test drive a real `McpToolRegistryCore` through this
 * derivation, so the binding contract is executed, not implied (review on #1380).
 */
internal fun mcpActivityStatusShowsFor(
    allTools: List<RegisteredMcpTool>,
    hasRecentOperations: Boolean,
    showActivityLog: Boolean,
    showFlightPlan: Boolean,
): Boolean =
    mcpActivityStatusShouldRender(
        hasRecentOperations = hasRecentOperations,
        hasRegisteredTools = allTools.isNotEmpty(),
        showActivityLog = showActivityLog,
        showFlightPlan = showFlightPlan,
    )
