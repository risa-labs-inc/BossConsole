package ai.rever.boss.mcp

import kotlinx.serialization.Serializable

/**
 * The disposition rule applied to an MCP tool invocation.
 *
 * Modeled after the policy inspector (ALLOW / ASK / DENY):
 * - [ALLOW]: The tool handler runs immediately without prompt or delay.
 * - [ASK]: Execution is suspended, and the human operator is prompted for approval.
 * - [DENY]: Execution is immediately rejected with a policy error; the handler is never called.
 */
enum class McpPolicyAction {
    ALLOW,
    ASK,
    DENY,
}

/**
 * How an MCP tool call was ultimately authorized or rejected.
 */
enum class McpApprovalDisposition {
    AUTO_ALLOWED,
    APPROVED_ONCE,
    SESSION_TRUSTED,

    // A persisted decision, in the same [McpApprovalDecision] shape as SESSION_TRUSTED /
    // DENIED_BY_OPERATOR - distinct dispositions so the ledger records which of the three
    // scopes (once, session, persistent) the operator actually chose.
    PERSISTENTLY_ALLOWED,
    PERSISTENTLY_DENIED,
    POLICY_PERSIST_FAILED,
    DENIED_BY_OPERATOR,
    TIMEOUT,
    POLICY_DENIED,
    CANCELLED, // Legacy ledger value.
    CANCELLED_AWAITING_APPROVAL,
    CANCELLED_IN_FLIGHT,
    QUEUE_FULL,
}

/**
 * Persisted policy configuration for MCP tool execution.
 *
 * Saved to `~/.boss/mcp-tool-policy.json`. A damaged or unparseable file causes the
 * system to fail closed, defaulting all tools to [McpPolicyAction.DENY].
 */
@Serializable
data class McpToolPolicyConfig(
    val defaultMutatingAction: McpPolicyAction = McpPolicyAction.ASK,
    val defaultReadOnlyAction: McpPolicyAction = McpPolicyAction.ALLOW,
    val rules: Map<String, McpPolicyAction> = emptyMap(),
)

/**
 * Known tools and patterns that perform state mutations, infrastructure modifications,
 * shell execution, or credential extraction.
 */
object McpMutatingToolCatalog {
    /**
     * Exhaustive set of known mutating tools across BOSS dynamic plugins.
     */
    val KNOWN_MUTATING_TOOLS: Set<String> =
        setOf(
            // Kubernetes
            "k8s_delete",
            "k8s_exec",
            "k8s_apply",
            "k8s_scale",
            "k8s_rollout_restart",
            // Docker
            "docker_rm",
            "docker_stop",
            "docker_compose_down",
            "docker_compose_up",
            "docker_build",
            "docker_start",
            "docker_restart",
            // Helm
            "helm_install",
            "helm_upgrade",
            "helm_rollback",
            "helm_uninstall",
            // Secrets
            "secret_get",
            // File & OS Execution
            "codebase_write",
            "run_command",
            "run_in_sidebar",
            "run_in_panel",
            "send_input",
            "project_replace",
        )

    private val MUTATING_SUFFIXES =
        listOf(
            "_delete",
            "_rm",
            "_apply",
            "_exec",
            "_write",
            "_upgrade",
            "_rollback",
            "_restart",
            "_install",
            "_uninstall",
            "_stop",
        )

    /**
     * Determine if a tool is mutating based on known tool catalog and naming heuristics.
     */
    fun isMutating(toolName: String): Boolean {
        if (toolName in KNOWN_MUTATING_TOOLS) return true
        val lower = toolName.lowercase()
        return MUTATING_SUFFIXES.any { lower.endsWith(it) }
    }

    /**
     * Resolve the action for a given tool name against [config].
     */
    fun resolveAction(
        toolName: String,
        config: McpToolPolicyConfig,
    ): McpPolicyAction {
        config.rules[toolName]?.let { return it }
        return if (isMutating(toolName)) {
            config.defaultMutatingAction
        } else {
            config.defaultReadOnlyAction
        }
    }
}
