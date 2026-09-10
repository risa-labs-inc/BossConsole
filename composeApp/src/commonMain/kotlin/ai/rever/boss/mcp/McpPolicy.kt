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
     * Determine if a tool is mutating from its name alone, using the known tool catalog
     * and naming heuristics.
     *
     * This is a guess about a fact the tool itself declares. Prefer the overload taking
     * `declaredReadOnly` wherever the [ai.rever.boss.plugin.api.McpToolDefinition] is in
     * hand, which is every call site that governs an actual invocation.
     */
    fun isMutating(toolName: String): Boolean {
        if (toolName in KNOWN_MUTATING_TOOLS) return true
        val lower = toolName.lowercase()
        return MUTATING_SUFFIXES.any { lower.endsWith(it) }
    }

    /**
     * Whether a tool mutates, given both its name and what it declares about itself.
     *
     * [declaredReadOnly] is `McpToolDefinition.readOnly`, which the plugin author sets.
     * A declaration can only ever ADD caution here, never remove it, and the asymmetry
     * is the whole point:
     *
     * - `readOnly = false` is an explicit statement that the tool has side effects, so it
     *   is believed outright. This is what the name heuristic cannot reach: a mutating
     *   tool called `git_push`, `send_email` or `publish_release` is in neither
     *   [KNOWN_MUTATING_TOOLS] nor [MUTATING_SUFFIXES], so on the name alone it resolves
     *   to [McpToolPolicyConfig.defaultReadOnlyAction] and runs with no operator prompt.
     * - `readOnly = true` is NOT believed, because it is the field's default. A tool whose
     *   author never considered the question is indistinguishable from one that answered
     *   "no side effects", so trusting it would let any plugin opt out of approval by
     *   saying nothing at all. The name heuristic still applies, and nothing the catalog
     *   already catches is weakened.
     *
     * The composition is therefore fail-safe in both directions: mutating if the tool says
     * so, or if its name says so.
     */
    fun isMutating(
        toolName: String,
        declaredReadOnly: Boolean,
    ): Boolean = !declaredReadOnly || isMutating(toolName)

    /**
     * Resolve the action for a given tool name against [config].
     *
     * [declaredReadOnly] defaults to `true` so that a caller with no definition in hand
     * gets exactly the previous name-only behaviour: `!true` is false, and the expression
     * collapses to the name heuristic.
     */
    fun resolveAction(
        toolName: String,
        config: McpToolPolicyConfig,
        declaredReadOnly: Boolean = true,
    ): McpPolicyAction {
        config.rules[toolName]?.let { return it }
        return if (isMutating(toolName, declaredReadOnly)) {
            config.defaultMutatingAction
        } else {
            config.defaultReadOnlyAction
        }
    }
}
