package ai.rever.boss.mcp

import ai.rever.boss.mcp.sandbox.DefaultMcpRiskEvaluator
import ai.rever.boss.mcp.sandbox.McpRiskLevel
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Fault explaining why the MCP policy state is degraded or cannot be persisted.
 */
sealed interface McpPolicyFault {
    val message: String

    data class PersistedPolicyUnreadable(
        val path: String,
        val error: String,
    ) : McpPolicyFault {
        override val message: String
            get() = "MCP policy file could not be read ($error): all tools withheld. Repair $path and restart BOSS."
    }

    data class PolicyPersistFailed(
        val toolName: String,
        val error: String,
    ) : McpPolicyFault {
        override val message: String
            get() = "Could not save MCP policy for '$toolName' ($error)."
    }
}

/**
 * Manages MCP tool execution policies (ALLOW / ASK / DENY).
 *
 * Persisted to `~/.boss/mcp-tool-policy.json`. A damaged or unreadable file
 * degrades to fail-closed defaults (all tools are denied).
 *
 * In-memory session trust ([trustForSession]) allows an operator to approve a tool
 * for the duration of the current application run without writing a permanent rule.
 */
@Suppress("TooManyFunctions") // Policy resolution, approval guards and durable updates share one state and lock.
class McpPolicyEngine(
    private val policyFile: File? = null,
    private val onFault: (McpPolicyFault) -> Unit = {},
) {
    private val logger = BossLogger.forComponent("McpPolicyEngine")
    private val lock = Any()
    private val revocations = ConcurrentHashMap<String, Long>()
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

    private val _fault = MutableStateFlow<McpPolicyFault?>(null)
    val fault: StateFlow<McpPolicyFault?> = _fault.asStateFlow()

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<McpToolPolicyConfig> = _config.asStateFlow()

    private val _sessionTrustedTools = MutableStateFlow<Set<String>>(emptySet())
    val sessionTrustedTools: StateFlow<Set<String>> = _sessionTrustedTools.asStateFlow()

    /** Capture before reading policy; a reset invalidates every older authorization. */
    internal fun revocationVersion(toolName: String): Long = revocations[toolName] ?: 0L

    /** Final authorization boundary. Session grants and operator resets use the same lock. */
    internal fun confirmInvocation(
        toolName: String,
        expectedRevocation: Long,
        grantSessionTrust: Boolean,
    ): Boolean =
        synchronized(lock) {
            if (revocationVersion(toolName) != expectedRevocation || policyFor(toolName) == McpPolicyAction.DENY) {
                false
            } else {
                if (grantSessionTrust) trustForSession(toolName)
                true
            }
        }

    /**
     * Resolve the effective policy action for [toolName].
     *
     * Explicit DENY rules in configuration always win over session trust.
     * If [toolName] was trusted by the operator for this session, it returns [McpPolicyAction.ALLOW].
     */
    @Suppress("ReturnCount") // Ordered deny, trust and default policy precedence.
    fun policyFor(toolName: String): McpPolicyAction {
        if (_fault.value is McpPolicyFault.PersistedPolicyUnreadable) return McpPolicyAction.DENY
        val configured = _config.value.rules[toolName]
        if (configured == McpPolicyAction.DENY) {
            return McpPolicyAction.DENY
        }
        if (toolName in _sessionTrustedTools.value) {
            return McpPolicyAction.ALLOW
        }
        if (configured != null) return configured
        val risk = DefaultMcpRiskEvaluator().evaluateRisk(toolName, McpToolArgs(emptyMap())).level
        return if (risk >= McpRiskLevel.HIGH || McpMutatingToolCatalog.isMutating(toolName)) {
            _config.value.defaultMutatingAction
        } else {
            _config.value.defaultReadOnlyAction
        }
    }

    /**
     * Trust [toolName] for the duration of this session only.
     * Session trust is not written to disk and clears upon app restart.
     */
    fun trustForSession(toolName: String) {
        _sessionTrustedTools.update { it + toolName }
        logger.info(
            LogCategory.SYSTEM,
            "Tool trusted for current session",
            mapOf("tool" to toolName),
        )
    }

    /**
     * Revoke session trust for [toolName].
     */
    fun revokeSessionTrust(toolName: String) {
        _sessionTrustedTools.update { it - toolName }
        logger.info(
            LogCategory.SYSTEM,
            "Revoked session trust for tool",
            mapOf("tool" to toolName),
        )
    }

    /**
     * Clear all session trusts.
     */
    fun clearSessionTrusts() {
        _sessionTrustedTools.value = emptySet()
    }

    /**
     * Replaces the rule map and persists it, publishing the result the same way for every
     * caller - [setToolPolicy]'s add/replace and [revokePersistedPolicy]'s remove both go
     * through this, so a future change to how a persist failure is reported (or logged, or
     * faulted) cannot update one path and silently miss the other.
     */
    private fun applyRules(
        toolName: String,
        updatedRules: Map<String, McpPolicyAction>,
        successMessage: String,
        failureMessage: String,
    ): Boolean {
        val updated = _config.value.copy(rules = updatedRules)
        val error = persistConfig(updated)
        return if (error != null) {
            val faultObj = McpPolicyFault.PolicyPersistFailed(toolName, error)
            if (_fault.value !is McpPolicyFault.PersistedPolicyUnreadable) _fault.value = faultObj
            notifyFault(faultObj)
            logger.warn(LogCategory.SYSTEM, failureMessage, mapOf("tool" to toolName, "error" to error))
            false
        } else {
            _config.value = updated
            _fault.value = null
            logger.info(LogCategory.SYSTEM, successMessage, mapOf("tool" to toolName))
            true
        }
    }

    /**
     * Set a persistent policy rule for [toolName], returning whether it was saved.
     * [preserveDeny] keeps a queued approval from replacing a newer denial.
     */
    fun setToolPolicy(
        toolName: String,
        action: McpPolicyAction,
        preserveDeny: Boolean = false,
        expectedRevocation: Long? = null,
    ): Boolean =
        synchronized(lock) {
            if (expectedRevocation != null && revocationVersion(toolName) != expectedRevocation) {
                return@synchronized false
            }
            if (preserveDeny && policyFor(toolName) == McpPolicyAction.DENY) return@synchronized false
            applyRules(
                toolName,
                _config.value.rules + (toolName to action),
                successMessage = "Updated tool policy: ${action.name}",
                failureMessage = "Failed to persist MCP policy update",
            )
        }

    /**
     * The operator-facing undo for [setToolPolicy]'s persistent scope: removes [toolName]'s rule
     * entirely, so the next call falls through to whatever [McpToolPolicyConfig.defaultMutatingAction]
     * / `defaultReadOnlyAction` actually say - not to a hardcoded ASK.
     *
     * **Removes the key rather than rewriting it to ASK.** An earlier version did the latter by
     * calling `setToolPolicy(toolName, ASK)`, which - because [setToolPolicy] always writes
     * `rules + (toolName to action)` - left the key in the map forever, just holding ASK instead
     * of its old value. Three consequences that all trace back to that one line: the bottom bar's
     * "Persisted MCP policies (n)" count never dropped after a revoke, because the row was still
     * there; the policy manager dialog kept listing the "revoked" tool with a Reset button that
     * rewrote the same value and reported success; and on a config with `defaultMutatingAction =
     * DENY`, the explicit ASK a revoke left behind was *weaker* than the operator's own configured
     * default - the opposite of what "reset to default" should mean. `rules - toolName` fixes all
     * three: [policyFor] sees no configured rule and falls through to the real default, and the
     * row genuinely disappears everywhere that reads [config] directly.
     *
     * Clears session trust for the same tool too, not only the persisted rule. [policyFor]
     * checks session trust *before* a non-DENY configured rule, so a tool that happens to hold
     * both (session-trusted, then separately given a persistent rule) would otherwise keep
     * answering ALLOW from the session-trust check alone even after its persisted rule was
     * reset - "revoke" has to mean the call asks again, not "asks again unless it also had the
     * other kind of standing grant."
     *
     * Returns whether the persisted half succeeded, via the same disk-write path
     * [setToolPolicy] uses - a caller surfaces `false` as a real failure, not a silent no-op,
     * since a revoke that did not actually take effect on disk is worse than useless: the UI
     * would show the tool as reset while the file, and the next restart, still say otherwise.
     */
    fun revokePersistedPolicy(toolName: String): Boolean =
        synchronized(lock) {
            // Even a failed reset invalidates queued answers. The previous durable rule remains
            // visible on failure, but an older answer cannot restore trust behind this reset.
            revokeSessionTrust(toolName)
            val saved =
                applyRules(
                    toolName,
                    _config.value.rules - toolName,
                    successMessage = "Revoked persisted tool policy",
                    failureMessage = "Failed to persist MCP policy revocation",
                )
            // Publish last: a caller observing this version must also see the reset policy.
            // Calls that captured the previous version cannot pass the locked approval guard.
            revocations[toolName] = revocationVersion(toolName) + 1
            saved
        }

    // An absent file uses defaults; I/O and JSON failures withhold tools.
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun loadConfig(): McpToolPolicyConfig {
        val file = policyFile ?: return McpToolPolicyConfig()
        if (!file.exists()) return McpToolPolicyConfig()

        return try {
            val raw = file.readText()
            json.decodeFromString<McpToolPolicyConfig>(raw)
        } catch (t: Exception) {
            val errorMsg = t::class.simpleName ?: "unknown error"
            val faultObj = McpPolicyFault.PersistedPolicyUnreadable(file.path, errorMsg)
            _fault.value = faultObj
            logger.error(
                LogCategory.SYSTEM,
                "Failed to parse MCP policy file - defaulting to fail-closed configuration",
                mapOf("path" to file.path, "error" to errorMsg),
            )
            // Fail closed: withhold every tool until policy recovery.
            McpToolPolicyConfig(
                defaultMutatingAction = McpPolicyAction.DENY,
                defaultReadOnlyAction = McpPolicyAction.DENY,
            )
        }
    }

    @Suppress("TooGenericExceptionCaught") // UI notification failure must not disable policy enforcement.
    private fun notifyFault(fault: McpPolicyFault) {
        try {
            onFault(fault)
        } catch (_: Exception) {
            logger.warn(LogCategory.SYSTEM, "MCP policy fault notification failed")
        }
    }

    @Suppress("TooGenericExceptionCaught") // Persistence failures must leave the previous policy in force.
    private fun persistConfig(cfg: McpToolPolicyConfig): String? {
        val file = policyFile ?: return null
        return try {
            file.atomicWriteText(json.encodeToString(cfg))
            null
        } catch (t: Exception) {
            t.message ?: t::class.simpleName ?: "unknown write error"
        }
    }
}
