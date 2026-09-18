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

    data class ProviderPolicyPersistFailed(
        val providerId: String,
        val error: String,
    ) : McpPolicyFault {
        override val message: String
            get() = "Could not save MCP provider trust for '$providerId' ($error)."
    }
}

/**
 * Result of an atomic proactive write. Refusals need a refreshed candidate and a new
 * operator decision; storage failures need storage recovery. Neither is a saved rule.
 */
sealed interface McpProactivePolicyOutcome {
    data object Saved : McpProactivePolicyOutcome

    /** The candidate is stale, a rule exists, or an effective DENY/fault blocks the write. */
    data object Refused : McpProactivePolicyOutcome

    data object PolicyUnreadable : McpProactivePolicyOutcome

    data object Denied : McpProactivePolicyOutcome

    data class Failed(
        val error: String,
    ) : McpProactivePolicyOutcome
}

/** One explicitly reviewed change in a host UI section snapshot. */
data class McpSectionPolicyChange(
    val toolName: String,
    val providerId: String,
    val expectedRevocation: Long,
    val expectedRule: McpPolicyAction?,
    val action: McpPolicyAction,
)

/**
 * One operator grant of session trust: [providerId]'s [toolName] runs without prompting
 * until it is revoked, a policy reset lands, or the app restarts. The provider is part of
 * the identity because a second provider shipping a same-named tool is a *different* tool:
 * a name-only grant would hand an unvetted plugin the approval its sibling earned, the same
 * tool-name squat the provider rules and the mutating catalog already defend against.
 */
data class McpSessionTrust(
    val providerId: String,
    val toolName: String,
) {
    /** `providerId/toolName`, for display wherever trusted tools are listed. */
    override fun toString(): String = "$providerId/$toolName"
}

/**
 * Manages MCP tool execution policies (ALLOW / ASK / DENY).
 *
 * Persisted to `~/.boss/mcp-tool-policy.json`. A damaged or unreadable file
 * degrades to fail-closed defaults (all tools are denied).
 *
 * In-memory session trust ([trustForSession]) allows an operator to approve a tool
 * for the duration of the current application run without writing a permanent rule.
 *
 * Tool-scoped and provider-scoped policy ([setProviderPolicy]/[revokeProviderPolicy]) are kept
 * side by side deliberately rather than split into two classes, which would each need their own
 * copy of the lock, fault channel and persisted config this one already has.
 */
@Suppress("TooManyFunctions") // Policy resolution, approval guards and durable updates share one state and lock.
class McpPolicyEngine(
    private val policyFile: File? = null,
    private val onFault: (McpPolicyFault) -> Unit = {},
) {
    private val logger = BossLogger.forComponent("McpPolicyEngine")
    private val lock = Any()
    private val revocations = ConcurrentHashMap<String, Long>()
    private val providerRevocations = ConcurrentHashMap<String, Long>()
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

    private val _fault = MutableStateFlow<McpPolicyFault?>(null)
    val fault: StateFlow<McpPolicyFault?> = _fault.asStateFlow()

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<McpToolPolicyConfig> = _config.asStateFlow()

    private val _sessionTrustedTools = MutableStateFlow<Set<McpSessionTrust>>(emptySet())
    val sessionTrustedTools: StateFlow<Set<McpSessionTrust>> = _sessionTrustedTools.asStateFlow()

    /** Capture before reading policy; a reset invalidates every older authorization. */
    internal fun revocationVersion(
        toolName: String,
        providerId: String? = null,
    ): Long = (revocations[toolName] ?: 0L) + (providerId?.let { providerRevocations[it] } ?: 0L)

    /**
     * Final authorization boundary. Session grants and operator resets use the same lock.
     *
     * [providerId] is the tool's contributing provider, so the DENY recheck evaluates the
     * same provider-aware policy the initial lookup did - a provider-wide DENY must hold at
     * this boundary too, or a queued approval captured before the DENY was saved would get a
     * second look without it. [declaredReadOnly] exists for the same reason: the recheck must
     * classify the tool exactly as the initial lookup did, or the two ends of one invocation
     * could disagree about whether the default that applies is the mutating one.
     */
    internal fun confirmInvocation(
        toolName: String,
        expectedRevocation: Long,
        grantSessionTrust: Boolean,
        providerId: String? = null,
        declaredReadOnly: Boolean? = null,
    ): Boolean =
        synchronized(lock) {
            if (revocationVersion(toolName, providerId) != expectedRevocation ||
                policyFor(toolName, providerId, declaredReadOnly) == McpPolicyAction.DENY
            ) {
                false
            } else {
                if (grantSessionTrust) {
                    if (providerId != null) {
                        trustForSession(toolName, providerId)
                    } else {
                        // The call itself proceeds - the operator answered for it - but the
                        // trust does not stick: a name-only grant is exactly the cross-provider
                        // leak the scoped key exists to close, so trust waits for a provider.
                        logger.warn(
                            LogCategory.SYSTEM,
                            "Session trust not granted - no provider in hand",
                            mapOf("tool" to toolName),
                        )
                    }
                }
                true
            }
        }

    /**
     * Resolve the effective policy action for [toolName], contributed by [providerId].
     *
     * Precedence, most authoritative first:
     * 1. A fault that withholds every tool.
     * 2. An explicit DENY - tool-specific **or** [providerId]'s own - always wins, over
     *    everything below, including a more specific ALLOW. This is deliberately NOT
     *    "most specific wins": a provider-wide DENY is a broader, and typically later,
     *    decision than whatever per-tool rule it sits next to, and letting a narrower ALLOW
     *    punch a hole through it would reopen exactly the access the wide DENY was meant to
     *    close - the same "most restrictive wins" posture DENY already has everywhere else in
     *    this engine (it already beats session trust the same unconditional way).
     * 3. Session trust for this exact tool, granted to the provider the operator approved -
     *    a same-named tool from a different provider does not inherit it, and with no
     *    provider in hand session trust never applies.
     * 4. An explicit tool-specific rule that is not DENY (ALLOW or ASK) - more specific than
     *    [providerId]'s rule, so it wins when the two disagree and neither is a DENY.
     * 5. [providerId]'s own ALLOW - "trust every tool this plugin contributes."
     * 6. The risk-based default: HIGH risk or mutating classification picks
     *    [McpToolPolicyConfig.defaultMutatingAction], everything else the read-only default.
     *
     * [providerId] is optional so existing callers that only ever checked a tool name (tests,
     * anything resolving policy before a provider is known) keep compiling; omitting it just
     * means step 2 and 5 never apply. [declaredReadOnly] is the same story for the tool's own
     * read-only declaration: when the caller has the definition in hand it passes
     * `definition.readOnly` and a tool that declared side effects classifies as mutating
     * whatever its name says (#804); without it the name-only catalog decides, as before.
     */
    @Suppress("ReturnCount") // Ordered deny, trust, tool-rule, provider-rule and default precedence.
    fun policyFor(
        toolName: String,
        providerId: String? = null,
        declaredReadOnly: Boolean? = null,
    ): McpPolicyAction {
        if (_fault.value is McpPolicyFault.PersistedPolicyUnreadable) return McpPolicyAction.DENY
        val configuredTool = _config.value.rules[toolName]
        if (configuredTool == McpPolicyAction.DENY) {
            return McpPolicyAction.DENY
        }
        val configuredProvider = providerId?.let { _config.value.providerRules[it] }
        if (configuredProvider == McpPolicyAction.DENY) {
            return McpPolicyAction.DENY
        }
        if (providerId != null && McpSessionTrust(providerId, toolName) in _sessionTrustedTools.value) {
            return McpPolicyAction.ALLOW
        }
        if (configuredTool != null) return configuredTool
        if (configuredProvider == McpPolicyAction.ALLOW) return McpPolicyAction.ALLOW
        val risk = DefaultMcpRiskEvaluator().evaluateRisk(toolName, McpToolArgs(emptyMap())).level
        return if (risk >= McpRiskLevel.HIGH || McpMutatingToolCatalog.isMutating(toolName, declaredReadOnly)) {
            _config.value.defaultMutatingAction
        } else {
            _config.value.defaultReadOnlyAction
        }
    }

    /**
     * Trust [toolName], contributed by [providerId], for the duration of this session only.
     * Session trust is not written to disk and clears upon app restart. [providerId] has no
     * default on purpose: a caller that cannot name the provider cannot grant trust without
     * giving the same grant to every plugin that ships a same-named tool.
     */
    fun trustForSession(
        toolName: String,
        providerId: String,
    ) {
        _sessionTrustedTools.update { it + McpSessionTrust(providerId, toolName) }
        logger.info(
            LogCategory.SYSTEM,
            "Tool trusted for current session",
            mapOf("tool" to toolName, "provider" to providerId),
        )
    }

    /**
     * Revoke session trust for [toolName]. [providerId] narrows the revoke to one provider's
     * grant; left null it revokes the tool for *every* provider at once - the direction a
     * persisted-rule reset needs, since the rules on disk are name-keyed and cannot know
     * which provider a session trust was granted for. Over-removing trust fails closed;
     * keeping it would not.
     */
    fun revokeSessionTrust(
        toolName: String,
        providerId: String? = null,
    ) {
        _sessionTrustedTools.update { trusted ->
            trusted
                .filterNot {
                    it.toolName == toolName && (providerId == null || it.providerId == providerId)
                }.toSet()
        }
        logger.info(
            LogCategory.SYSTEM,
            "Revoked session trust for tool",
            mapOf("tool" to toolName, "provider" to (providerId ?: "<all>")),
        )
    }

    /**
     * Clear all session trusts.
     */
    fun clearSessionTrusts() {
        _sessionTrustedTools.value = emptySet()
    }

    /**
     * Persists an already-built config and publishes the result the same way for every
     * caller - [setToolPolicy]'s add/replace, [revokePersistedPolicy]'s remove,
     * [setProviderPolicy]'s add/replace and [revokeProviderPolicy]'s remove all go through
     * this, so a future change to how a persist failure is reported (or logged, or faulted)
     * cannot update one path and silently miss the others. [logKey] is the field name used
     * in the structured log so tool writes and provider writes stay distinguishable.
     */
    @Suppress("LongParameterList") // Six distinct fields the four write paths all need; no natural grouping.
    private fun applyConfig(
        key: String,
        logKey: String,
        updated: McpToolPolicyConfig,
        successMessage: String,
        failureMessage: String,
        faultFor: (key: String, error: String) -> McpPolicyFault,
    ): Boolean =
        writeConfig(key, logKey, updated, successMessage, failureMessage, faultFor) is McpProactivePolicyOutcome.Saved

    /**
     * The persist-publish-log-fault sequence every durable write shares, returning
     * [McpProactivePolicyOutcome] so [setToolPolicyIfAbsent] can report [McpProactivePolicyOutcome.Failed]
     * distinctly from a guard refusal. [applyConfig] is the `Boolean`-returning adapter its four
     * existing callers keep using unchanged.
     */
    @Suppress("LongParameterList") // Mirrors applyConfig's own six fields; see its KDoc.
    private fun writeConfig(
        key: String,
        logKey: String,
        updated: McpToolPolicyConfig,
        successMessage: String,
        failureMessage: String,
        faultFor: (key: String, error: String) -> McpPolicyFault,
    ): McpProactivePolicyOutcome {
        val error = persistConfig(updated)
        return if (error != null) {
            val faultObj = faultFor(key, error)
            if (_fault.value !is McpPolicyFault.PersistedPolicyUnreadable) _fault.value = faultObj
            notifyFault(faultObj)
            logger.warn(LogCategory.SYSTEM, failureMessage, mapOf(logKey to key, "error" to error))
            McpProactivePolicyOutcome.Failed(error)
        } else {
            _config.value = updated
            _fault.value = null
            logger.info(LogCategory.SYSTEM, successMessage, mapOf(logKey to key))
            McpProactivePolicyOutcome.Saved
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
        providerId: String? = null,
    ): Boolean =
        synchronized(lock) {
            if (expectedRevocation != null && revocationVersion(toolName, providerId) != expectedRevocation) {
                return@synchronized false
            }
            if (preserveDeny && policyFor(toolName, providerId) == McpPolicyAction.DENY) return@synchronized false
            applyConfig(
                key = toolName,
                logKey = "tool",
                updated = _config.value.copy(rules = _config.value.rules + (toolName to action)),
                successMessage = "Updated tool policy: ${action.name}",
                failureMessage = "Failed to persist MCP policy update",
                faultFor = { k, e -> McpPolicyFault.PolicyPersistFailed(k, e) },
            )
        }

    /**
     * The proactive path's write: set a persistent rule for [toolName], but only while it still
     * has none of its own. [setToolPolicy]'s [expectedRevocation] guard alone does not close
     * this - only a *revoke* ([revokePersistedPolicy]/[revokeProviderPolicy]) bumps
     * [revocationVersion], so an intervening explicit ASK or ALLOW made through the reactive
     * approval dialog for this same tool never trips it, and a candidate offered while the tool
     * had no rule could otherwise silently overwrite a decision made in between (review on
     * #636). Checking `toolName !in rules` under the same [lock] the write itself takes closes
     * that: any rule present at write time - ASK or ALLOW, not only DENY the way
     * [setToolPolicy]'s `preserveDeny` protects - refuses the write, which is the "add only if
     * absent" contract this call exists for.
     *
     * Returns [McpProactivePolicyOutcome] rather than [Boolean]: the caller needs to tell a
     * refusal (refresh policy context before retrying) apart from a genuine disk
     * failure (this operator's own choice did not take), which a bare `false` cannot express.
     */
    fun setToolPolicyIfAbsent(
        toolName: String,
        action: McpPolicyAction,
        expectedRevocation: Long,
        providerId: String? = null,
    ): McpProactivePolicyOutcome =
        synchronized(lock) {
            if (_fault.value is McpPolicyFault.PersistedPolicyUnreadable) {
                return@synchronized McpProactivePolicyOutcome.PolicyUnreadable
            }
            if (revocationVersion(toolName, providerId) != expectedRevocation || toolName in _config.value.rules) {
                return@synchronized McpProactivePolicyOutcome.Refused
            }
            if (policyFor(toolName, providerId) == McpPolicyAction.DENY) {
                return@synchronized McpProactivePolicyOutcome.Denied
            }
            writeConfig(
                key = toolName,
                logKey = "tool",
                updated = _config.value.copy(rules = _config.value.rules + (toolName to action)),
                successMessage = "Updated tool policy: ${action.name}",
                failureMessage = "Failed to persist MCP policy update",
                faultFor = { k, e -> McpPolicyFault.PolicyPersistFailed(k, e) },
            )
        }

    /** Save a reviewed section in one durable write; concurrent edits invalidate the whole snapshot. */
    fun setSectionPolicies(changes: List<McpSectionPolicyChange>): McpProactivePolicyOutcome =
        synchronized(lock) {
            if (_fault.value is McpPolicyFault.PersistedPolicyUnreadable) {
                return@synchronized McpProactivePolicyOutcome.PolicyUnreadable
            }
            if (changes.isEmpty() || changes.distinctBy { it.toolName }.size != changes.size) {
                return@synchronized McpProactivePolicyOutcome.Refused
            }
            if (changes.any {
                    revocationVersion(it.toolName, it.providerId) != it.expectedRevocation ||
                        _config.value.rules[it.toolName] != it.expectedRule
                }
            ) {
                return@synchronized McpProactivePolicyOutcome.Refused
            }
            if (changes.any {
                    it.action == McpPolicyAction.ALLOW &&
                        _config.value.providerRules[it.providerId] == McpPolicyAction.DENY
                }
            ) {
                return@synchronized McpProactivePolicyOutcome.Denied
            }
            val outcome =
                writeConfig(
                    key = "${changes.size} tools",
                    logKey = "section",
                    updated =
                        _config.value.copy(
                            rules = _config.value.rules + changes.associate { it.toolName to it.action },
                        ),
                    successMessage = "Updated section tool policies",
                    failureMessage = "Failed to persist MCP section policies",
                    faultFor = { k, e -> McpPolicyFault.PolicyPersistFailed(k, e) },
                )
            if (outcome == McpProactivePolicyOutcome.Saved) {
                changes.forEach { revocations[it.toolName] = revocationVersion(it.toolName) + 1 }
                // A section change speaks for one provider's tool, so it drops only that
                // provider's session grant. Same-named trust held for other providers must
                // survive: their calls still answer to the rules this write did not touch.
                _sessionTrustedTools.update { trusted ->
                    trusted
                        .filterNot { trust ->
                            changes.any { it.toolName == trust.toolName && it.providerId == trust.providerId }
                        }.toSet()
                }
            }
            outcome
        }

    /**
     * Trust every tool [providerId] contributes, persistently - "Trust this plugin" in the
     * approval dialog, for an operator who does not want to approve each of its tools one at a
     * time. Weaker than an explicit tool-specific rule: see [policyFor].
     *
     * [preserveDeny]/[expectedRevocation]/[toolName] mirror [setToolPolicy]'s own guards: a
     * queued "Trust This Plugin" click is answering for the *tool* that prompted it, so its
     * write must recheck that tool's revocation/DENY state under this same lock, not only at
     * the caller's pre-check - otherwise a reset landing between the pre-check and the write
     * (BossConsole#542 review) persists a provider-wide grant the reset was supposed to
     * invalidate. All three are optional because this is also called with no tool in mind
     * (tests, and any future non-approval-flow caller). Supplying [expectedRevocation]
     * requires [toolName]; without it the guard fails closed and returns false.
     */
    fun setProviderPolicy(
        providerId: String,
        action: McpPolicyAction,
        preserveDeny: Boolean = false,
        expectedRevocation: Long? = null,
        toolName: String? = null,
    ): Boolean =
        synchronized(lock) {
            if (expectedRevocation != null &&
                (toolName == null || revocationVersion(toolName, providerId) != expectedRevocation)
            ) {
                return@synchronized false
            }
            if (preserveDeny && isProviderOrToolDenied(providerId, toolName)) {
                return@synchronized false
            }
            applyConfig(
                key = providerId,
                logKey = "provider",
                updated = _config.value.copy(providerRules = _config.value.providerRules + (providerId to action)),
                successMessage = "Updated provider policy: ${action.name}",
                failureMessage = "Failed to persist MCP provider policy update",
                faultFor = { k, e -> McpPolicyFault.ProviderPolicyPersistFailed(k, e) },
            )
        }

    /**
     * True when [providerId]'s own rule is DENY, or [toolName] (when this call has one in mind)
     * has a more specific DENY of its own - either one is what [setProviderPolicy]'s
     * `preserveDeny` guard exists to protect from being overwritten.
     */
    private fun isProviderOrToolDenied(
        providerId: String,
        toolName: String?,
    ): Boolean =
        _config.value.providerRules[providerId] == McpPolicyAction.DENY ||
            (toolName != null && policyFor(toolName, providerId) == McpPolicyAction.DENY)

    /**
     * The operator-facing undo for [setProviderPolicy]: removes [providerId]'s rule entirely
     * (`providerRules - providerId`), so each of its tools falls back to whatever its own
     * tool-specific rule or the default policy says - not to a hardcoded value. Deliberately a
     * removal rather than a rewrite to some "neutral" action: a key left behind holding some
     * other value is not the same thing as the rule being gone, and would leave a permanently
     * non-empty entry for a provider the operator asked to stop trusting.
     *
     * Does not touch session trust for the provider's tools, unlike
     * [revokePersistedPolicy] for a single tool: session trust is a separate, in-session grant
     * the operator manages from the "Revoke MCP session trust" control, and revoking a
     * durable provider rule must not silently withdraw grants the operator made per tool.
     */
    fun revokeProviderPolicy(providerId: String): Boolean =
        synchronized(lock) {
            val saved =
                applyConfig(
                    key = providerId,
                    logKey = "provider",
                    updated = _config.value.copy(providerRules = _config.value.providerRules - providerId),
                    successMessage = "Revoked provider policy",
                    failureMessage = "Failed to persist MCP provider policy revocation",
                    faultFor = { k, e -> McpPolicyFault.ProviderPolicyPersistFailed(k, e) },
                )
            // Invalidate queued authorizations even when the durable reset fails.
            providerRevocations[providerId] = (providerRevocations[providerId] ?: 0L) + 1
            saved
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
                applyConfig(
                    key = toolName,
                    logKey = "tool",
                    updated = _config.value.copy(rules = _config.value.rules - toolName),
                    successMessage = "Revoked persisted tool policy",
                    failureMessage = "Failed to persist MCP policy revocation",
                    faultFor = { k, e -> McpPolicyFault.PolicyPersistFailed(k, e) },
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
