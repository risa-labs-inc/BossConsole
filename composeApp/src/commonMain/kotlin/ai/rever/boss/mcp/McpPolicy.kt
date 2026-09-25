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

    /**
     * The call was refused before authorization because its arguments were malformed or
     * failed the tool's declared inputSchema - the handler never ran and no approval was
     * requested.
     */
    INVALID_ARGUMENTS,

    /**
     * The operator chose "Trust this plugin" and the persisted, provider-wide grant actually
     * saved - every other tool from [ai.rever.boss.mcp.McpApprovalRequest.providerId] is now
     * ALLOW too, across restarts, with no further prompts for this provider.
     */
    PROVIDER_TRUSTED,

    /**
     * The operator chose "Trust this plugin" but the write failed - this call still executes
     * (downgraded to session trust, matching how a call in hand is never blocked by a disk
     * fault) but the durable grant does not exist. See [McpPolicyFault.ProviderPolicyPersistFailed].
     */
    PROVIDER_TRUST_PERSIST_FAILED,

    /**
     * The call carried a secret reference it may not have: the user lacks `secret.read`, the
     * host has `secretBearingCalls = DENY` or `secretReferencesEnabled = false`, or the secret is
     * an AI provider key the plugin itself refuses to reveal. Decided before any vault read and
     * before any prompt; the handler never ran. See `ai.rever.boss.mcp.secrets`.
     */
    SECRET_FORBIDDEN,

    /**
     * The call carried a secret reference the host could not resolve: malformed, an unknown id,
     * a field the secret does not have, or a vault read that failed. All-or-nothing: one such
     * reference withholds the whole call, and the handler never sees a partially substituted
     * argument. Decided before any prompt.
     */
    SECRET_UNRESOLVED,

    /**
     * The policy was ASK and the call ran without a prompt because the operator had
     * [ai.rever.boss.mcp.McpPolicyEngine.yoloMode] on. Its own value, so an audit can tell a
     * call nobody looked at from one somebody approved.
     */
    YOLO_ALLOWED,

    /**
     * Governance events, not tool calls: the operator switched YOLO mode on or off. Recorded in
     * the ledger (tool name [McpYoloMode.LEDGER_TOOL_NAME]) so the window during which calls could
     * run unattended is part of the hash-chained audit trail even if nothing was invoked in it.
     * See [isGovernanceEvent].
     */
    YOLO_ENABLED,
    YOLO_DISABLED,
    ;

    /** True for the [YOLO_ENABLED] / [YOLO_DISABLED] ledger markers, which are not tool calls. */
    val isGovernanceEvent: Boolean get() = this == YOLO_ENABLED || this == YOLO_DISABLED
}

/** Constants for YOLO mode's ledger markers. */
object McpYoloMode {
    const val LEDGER_TOOL_NAME = "yolo_mode"
    const val LEDGER_PROVIDER_ID = "host"
}

/**
 * What the host does with a call that carries `{{secret:...}}` references.
 *
 * Two members on purpose. There is no ALLOW: a secret-bearing call always reaches an operator,
 * above session trust and above any tool-wide or provider-wide ALLOW, so the primitive cannot be
 * configured into silently delivering credentials. An operator who wants fewer prompts is asking
 * for a per-(tool, secret) grant with its own review and revocation surface - a separate design,
 * not a switch here.
 */
enum class McpSecretPolicyAction {
    /** Prompt every time, showing which secrets and fields the tool would receive. The default. */
    ASK,

    /** Refuse every secret-bearing call before any vault read. */
    DENY,
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
    /**
     * Rules keyed by [ai.rever.boss.mcp.McpApprovalRequest.providerId] rather than tool name -
     * "trust every tool this plugin contributes" instead of approving each one individually.
     * Weaker than [rules]: an exact tool-name entry always overrides its provider's rule, in
     * either direction. See [ai.rever.boss.mcp.McpPolicyEngine.policyFor] for the full precedence.
     */
    val providerRules: Map<String, McpPolicyAction> = emptyMap(),
    /**
     * Whether `{{secret:<id>}}` references in tool arguments are resolved at all. Off, a
     * secret-bearing call is refused (never passed through with its placeholders intact, which
     * would leave the agent believing a credential was delivered). A rollback switch, not a
     * bypass: nothing here makes such a call run silently.
     */
    val secretReferencesEnabled: Boolean = true,
    /** See [McpSecretPolicyAction]. */
    val secretBearingCalls: McpSecretPolicyAction = McpSecretPolicyAction.ASK,
    /**
     * Whether resolved values are removed from a tool's result text before it returns to the
     * agent. Defense in depth against a handler that echoes its input; the non-disclosure
     * guarantee holds without it (see `ai.rever.boss.mcp.secrets.McpResultScrubber`).
     */
    val resultScrubbingEnabled: Boolean = true,
    val providerMapping: Map<String, Set<String>> = emptyMap(),
)

/**
 * Known tools and patterns that perform state mutations, infrastructure modifications,
 * shell execution, or credential extraction.
 *
 * Two signals feed [isMutating], combined fail-closed: the name signals this object owns
 * ([KNOWN_MUTATING_TOOLS], [MUTATING_SUFFIXES]) and the provider's own read-only declaration
 * ([ai.rever.boss.plugin.api.McpToolDefinition.readOnly], which every registered tool already
 * carries). Neither signal alone is trustworthy - an innocent name can hide a mutation the
 * catalog never listed (#804), and a mutating name must not be upgraded by a dishonest read
 * claim - so either one saying "mutating" is final.
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
            // Sensitive reads use the approval-requiring default too: download URLs can
            // contain bearer tokens. A read-only declaration must not bypass that default.
            "downloads_history_list",
            // File & OS Execution
            "codebase_write",
            "run_command",
            "run_in_sidebar",
            "run_in_panel",
            "send_input",
            "project_replace",
            // Workspace and Terminal Lifecycle
            "open_workspace",
            "workspace_open",
            "create_workspace",
            "workspace_create",
            "open_terminal",
            "terminal_open",
            "close_workspace",
            "workspace_close",
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
     * Determine whether a tool is mutating, fail-closed over the two signals the host has.
     *
     * The name signals are evaluated first and are final: a tool NAMED like a mutation stays
     * mutating even when its provider declares [declaredReadOnly] = true, because a dishonest or
     * careless claim must never upgrade a tool past what its own name gives away. Only when the
     * name says nothing does the provider's declaration decide: [declaredReadOnly] = false is
     * the tool telling the host at registration that it has side effects, and that wins over any
     * innocent name (#804: a third-party `data_fetch`/`env_sync` used to be auto-allowed under
     * the lenient read-only default purely because its name avoided the catalog). [declaredReadOnly]
     * = null means the caller has no declaration in hand - a policy lookup for a tool whose
     * definition is not available to it - and the name-only answer stands, exactly as before
     * this parameter existed.
     */
    fun isMutating(
        toolName: String,
        declaredReadOnly: Boolean? = null,
    ): Boolean {
        val lower = toolName.lowercase()
        return toolName in KNOWN_MUTATING_TOOLS ||
            MUTATING_SUFFIXES.any { lower.endsWith(it) } ||
            declaredReadOnly == false
    }

    /**
     * Resolve the action for a given tool name against [config], honoring the same
     * [declaredReadOnly] declaration [isMutating] does.
     */
    fun resolveAction(
        toolName: String,
        config: McpToolPolicyConfig,
        declaredReadOnly: Boolean? = null,
    ): McpPolicyAction {
        config.rules[toolName]?.let { return it }
        return if (isMutating(toolName, declaredReadOnly)) {
            config.defaultMutatingAction
        } else {
            config.defaultReadOnlyAction
        }
    }
}
