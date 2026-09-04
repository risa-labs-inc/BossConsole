package ai.rever.boss.mcp.sandbox

/**
 * Risk classification for MCP tool invocations.
 */
enum class McpRiskLevel {
    /** Low risk: safe read-only operations or benign metadata queries. */
    LOW,

    /** Medium risk: moderate state observations or low-impact actions. */
    MEDIUM,

    /** High risk: state-modifying, file writing, or resource altering operations. */
    HIGH,

    /** Critical risk: destructive operations, raw secret access, or arbitrary shell/code execution. */
    CRITICAL,
}
