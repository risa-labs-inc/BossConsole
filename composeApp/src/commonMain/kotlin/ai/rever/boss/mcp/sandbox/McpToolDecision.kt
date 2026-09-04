package ai.rever.boss.mcp.sandbox

/**
 * Policy decision rendered by the Agent Tool Sandbox prior to tool execution.
 */
enum class McpToolDecision {
    /** Tool execution is permitted to proceed. */
    ALLOW,

    /** Tool execution is rejected. */
    DENY,

    /** Tool execution requires human approval before proceeding. */
    REQUIRE_APPROVAL,
}
