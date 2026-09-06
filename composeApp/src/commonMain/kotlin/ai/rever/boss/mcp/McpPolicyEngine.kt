package ai.rever.boss.mcp

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
            get() = "MCP policy file could not be read ($error): all mutating tools defaulted to ASK."
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
 * degrades to fail-closed defaults (mutating tools require ASK).
 *
 * In-memory session trust ([trustForSession]) allows an operator to approve a tool
 * for the duration of the current application run without writing a permanent rule.
 */
class McpPolicyEngine(
    private val policyFile: File? = null,
    private val onFault: (McpPolicyFault) -> Unit = {},
) {
    private val logger = BossLogger.forComponent("McpPolicyEngine")
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _fault = MutableStateFlow<McpPolicyFault?>(null)
    val fault: StateFlow<McpPolicyFault?> = _fault.asStateFlow()

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<McpToolPolicyConfig> = _config.asStateFlow()

    private val _sessionTrustedTools = MutableStateFlow<Set<String>>(emptySet())
    val sessionTrustedTools: StateFlow<Set<String>> = _sessionTrustedTools.asStateFlow()

    /**
     * Resolve the effective policy action for [toolName].
     *
     * Explicit DENY rules in configuration always win over session trust.
     * If [toolName] was trusted by the operator for this session, it returns [McpPolicyAction.ALLOW].
     */
    fun policyFor(toolName: String): McpPolicyAction {
        val configured = _config.value.rules[toolName]
        if (configured == McpPolicyAction.DENY) {
            return McpPolicyAction.DENY
        }
        if (toolName in _sessionTrustedTools.value) {
            return McpPolicyAction.ALLOW
        }
        return McpMutatingToolCatalog.resolveAction(toolName, _config.value)
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
     * Set a persistent policy rule for [toolName].
     */
    fun setToolPolicy(
        toolName: String,
        action: McpPolicyAction,
    ) {
        synchronized(lock) {
            val updated = _config.value.copy(rules = _config.value.rules + (toolName to action))
            val error = persistConfig(updated)
            if (error != null) {
                val faultObj = McpPolicyFault.PolicyPersistFailed(toolName, error)
                _fault.value = faultObj
                onFault(faultObj)
                logger.warn(
                    LogCategory.SYSTEM,
                    "Failed to persist MCP policy update",
                    mapOf("tool" to toolName, "error" to error),
                )
            } else {
                _config.value = updated
                _fault.value = null
                logger.info(
                    LogCategory.SYSTEM,
                    "Updated tool policy",
                    mapOf("tool" to toolName, "action" to action.name),
                )
            }
        }
    }

    private fun loadConfig(): McpToolPolicyConfig {
        val file = policyFile ?: return McpToolPolicyConfig()
        if (!file.exists()) return McpToolPolicyConfig()

        return try {
            val raw = file.readText()
            json.decodeFromString<McpToolPolicyConfig>(raw)
        } catch (t: Throwable) {
            val errorMsg = t.message ?: t::class.simpleName ?: "unknown error"
            val faultObj = McpPolicyFault.PersistedPolicyUnreadable(file.path, errorMsg)
            _fault.value = faultObj
            onFault(faultObj)
            logger.error(
                LogCategory.SYSTEM,
                "Failed to parse MCP policy file - defaulting to fail-closed configuration",
                mapOf("path" to file.path, "error" to errorMsg),
            )
            // Fail closed: enforce ASK on mutating tools
            McpToolPolicyConfig(defaultMutatingAction = McpPolicyAction.ASK)
        }
    }

    private fun persistConfig(cfg: McpToolPolicyConfig): String? {
        val file = policyFile ?: return null
        return try {
            file.atomicWriteText(json.encodeToString(cfg))
            null
        } catch (t: Throwable) {
            t.message ?: t::class.simpleName ?: "unknown write error"
        }
    }
}
