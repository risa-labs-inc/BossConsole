package ai.rever.boss.mcp.sentinel

import ai.rever.boss.mcp.McpApprovalDisposition
import ai.rever.boss.mcp.McpOperationLedger
import ai.rever.boss.mcp.McpPolicyAction
import ai.rever.boss.plugin.api.RegisteredMcpTool
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Result of evaluating a registered tool against ToolDNA baselines and security policies.
 */
data class ToolEvaluationResult(
    val providerId: String,
    val toolName: String,
    val trustState: SentinelTrustState,
    val currentFingerprint: ToolDnaFingerprint,
    val baselineRecord: ToolBaselineRecord?,
    val diffResult: ToolDnaDiffResult?,
    val securityFindings: List<SecurityFinding>,
    val shadowingFindings: List<ShadowingFinding>,
    val reason: String,
)

/**
 * Result of checking whether an invocation is allowed by Sentinel governance.
 */
data class SentinelInvocationCheck(
    val isAllowed: Boolean,
    val trustState: SentinelTrustState,
    val reason: String?,
    val evaluationResult: ToolEvaluationResult?,
)

private data class EvaluationContext(
    val providerId: String,
    val toolName: String,
    val fingerprint: ToolDnaFingerprint,
    val baseline: ToolBaselineRecord?,
    val securityFindings: List<SecurityFinding>,
    val toolShadowings: List<ShadowingFinding>,
)

/**
 * Engine coordinating MCP Sentinel's trust lifecycle, change detection, security scanning, and governance integration.
 */
class McpSentinelEngine(
    val baselineStore: ToolDnaBaselineStore,
    private val ledger: McpOperationLedger? = null,
) {
    private val logger = BossLogger.forComponent("McpSentinelEngine")
    private val lock = Any()

    private val _evaluations = MutableStateFlow<Map<String, ToolEvaluationResult>>(emptyMap())
    val evaluations: StateFlow<Map<String, ToolEvaluationResult>> = _evaluations.asStateFlow()

    private val _shadowingFindings = MutableStateFlow<List<ShadowingFinding>>(emptyList())
    val shadowingFindings: StateFlow<List<ShadowingFinding>> = _shadowingFindings.asStateFlow()

    /**
     * Evaluate all currently registered tools.
     */
    fun evaluateAll(tools: List<RegisteredMcpTool>): List<ToolEvaluationResult> {
        val shadowings = ToolShadowingDetector.detectShadowing(tools)
        val results = mutableListOf<ToolEvaluationResult>()
        val evalMap = mutableMapOf<String, ToolEvaluationResult>()

        synchronized(lock) {
            for (registered in tools) {
                val eval = evaluateSingleTool(registered, shadowings)
                results.add(eval)
                evalMap["${eval.providerId}/${eval.toolName}"] = eval
            }
            _shadowingFindings.value = shadowings
            _evaluations.value = evalMap
        }

        return results
    }

    /**
     * Evaluate a single tool incrementally without collapsing other evaluations.
     */
    fun evaluateSingleToolAndMerge(registered: RegisteredMcpTool): ToolEvaluationResult {
        synchronized(lock) {
            val shadowings = _shadowingFindings.value
            val eval = evaluateSingleTool(registered, shadowings)
            val key = "${eval.providerId}/${eval.toolName}"
            _evaluations.update { current -> current + (key to eval) }
            return eval
        }
    }

    /**
     * Check if a specific tool invocation is allowed under current Sentinel state.
     */
    fun checkInvocation(
        providerId: String,
        toolName: String,
        registeredTool: RegisteredMcpTool? = null,
    ): SentinelInvocationCheck {
        val key = "$providerId/$toolName"
        var eval = _evaluations.value[key]
        if (eval == null && registeredTool != null) {
            eval = evaluateSingleToolAndMerge(registeredTool)
        }

        val targetEval =
            eval ?: return SentinelInvocationCheck(
                isAllowed = true,
                trustState = SentinelTrustState.UNKNOWN,
                reason = null,
                evaluationResult = null,
            )

        val (isAllowed, reason) =
            when (targetEval.trustState) {
                SentinelTrustState.BLOCKED -> {
                    false to
                        "MCP Sentinel: Tool '$toolName' from provider '$providerId' is BLOCKED by security policy."
                }

                SentinelTrustState.CHANGED -> {
                    false to
                        "MCP Sentinel: Tool '$toolName' definition changed (Rug Pull detected). Re-approval required."
                }

                SentinelTrustState.SUSPICIOUS -> {
                    false to
                        "MCP Sentinel: Tool '$toolName' flagged for suspicious content: ${targetEval.reason}"
                }

                SentinelTrustState.REVIEW_REQUIRED -> {
                    val msg =
                        "MCP Sentinel: Tool '$toolName' definition updated with structural changes. " +
                            "Operator review required."
                    false to msg
                }

                SentinelTrustState.NEW, SentinelTrustState.TRUSTED, SentinelTrustState.UNKNOWN -> {
                    true to null
                }
            }

        return SentinelInvocationCheck(
            isAllowed = isAllowed,
            trustState = targetEval.trustState,
            reason = reason,
            evaluationResult = targetEval,
        )
    }

    /**
     * Explicitly approve a tool definition change and update its baseline.
     */
    fun approveAndTrustTool(
        providerId: String,
        toolName: String,
        reviewedFingerprint: String? = null,
        registeredTool: RegisteredMcpTool? = null,
    ): Boolean =
        synchronized(lock) {
            val key = "$providerId/$toolName"
            val eval = _evaluations.value[key]
            val currentFingerprint =
                eval?.currentFingerprint
                    ?: registeredTool?.let { ToolDnaFingerprinter.computeFingerprint(it) }

            if (currentFingerprint == null) return@synchronized false

            val isMatch = reviewedFingerprint == null || currentFingerprint.fingerprint == reviewedFingerprint
            if (isMatch) {
                executeApproval(providerId, toolName, currentFingerprint, registeredTool)
            } else {
                logger.warn(
                    LogCategory.SYSTEM,
                    "MCP Sentinel: Rejected approval due to fingerprint mismatch",
                    mapOf("reviewed" to (reviewedFingerprint ?: ""), "current" to currentFingerprint.fingerprint),
                )
                false
            }
        }

    private fun executeApproval(
        providerId: String,
        toolName: String,
        currentFingerprint: ToolDnaFingerprint,
        registeredTool: RegisteredMcpTool?,
    ): Boolean {
        val existingBaseline = baselineStore.getBaseline(providerId, toolName)
        val now = System.currentTimeMillis()
        val history = (existingBaseline?.fingerprintHistory.orEmpty() + currentFingerprint.fingerprint).distinct()
        val changeHist = (existingBaseline?.changeHistory.orEmpty() + "Approved by user at $now").takeLast(50)

        val newRecord =
            ToolBaselineRecord(
                providerId = providerId,
                toolName = toolName,
                canonicalFingerprint = currentFingerprint.fingerprint,
                fingerprintVersion = currentFingerprint.algorithmVersion,
                firstSeenTimestamp = existingBaseline?.firstSeenTimestamp ?: now,
                lastSeenTimestamp = now,
                trustState = SentinelTrustState.TRUSTED,
                lastAcceptedDescription = currentFingerprint.canonicalDescription,
                lastAcceptedSchemaJson = currentFingerprint.canonicalInputSchemaJson,
                readOnly = currentFingerprint.readOnly,
                requiresAdmin = currentFingerprint.requiresAdmin,
                fingerprintHistory = history,
                changeHistory = changeHist,
                userDecision = "APPROVED",
            )

        val saved = baselineStore.saveBaseline(newRecord)
        if (saved) {
            logger.info(
                LogCategory.SYSTEM,
                "MCP Sentinel: Approved and established new baseline",
                mapOf("provider" to providerId, "tool" to toolName, "fingerprint" to currentFingerprint.fingerprint),
            )
            recordAuditEvent("TOOL_REAPPROVED", providerId, toolName, currentFingerprint.fingerprint)
            registeredTool?.let { evaluateSingleToolAndMerge(it) }
        }
        return saved
    }

    /**
     * Explicitly block a tool.
     */
    fun blockTool(
        providerId: String,
        toolName: String,
    ): Boolean {
        synchronized(lock) {
            val existing = baselineStore.getBaseline(providerId, toolName)
            val now = System.currentTimeMillis()
            val updated =
                (
                    existing ?: ToolBaselineRecord(
                        providerId = providerId,
                        toolName = toolName,
                        canonicalFingerprint = "",
                        firstSeenTimestamp = now,
                        lastSeenTimestamp = now,
                        trustState = SentinelTrustState.BLOCKED,
                        lastAcceptedDescription = "",
                        lastAcceptedSchemaJson = "",
                    )
                ).copy(
                    trustState = SentinelTrustState.BLOCKED,
                    userDecision = "BLOCKED",
                    lastSeenTimestamp = now,
                )

            val saved = baselineStore.saveBaseline(updated)
            if (saved) {
                recordAuditEvent("TOOL_BLOCKED", providerId, toolName, updated.canonicalFingerprint)
            }
            return saved
        }
    }

    /**
     * Unblock a tool and reset its trust state to NEW for re-evaluation.
     */
    fun unblockTool(
        providerId: String,
        toolName: String,
    ): Boolean {
        synchronized(lock) {
            val existing = baselineStore.getBaseline(providerId, toolName) ?: return false
            val updated =
                existing.copy(
                    trustState = SentinelTrustState.NEW,
                    userDecision = "UNBLOCKED",
                    lastSeenTimestamp = System.currentTimeMillis(),
                )

            val saved = baselineStore.saveBaseline(updated)
            if (saved) {
                recordAuditEvent("TOOL_UNBLOCKED", providerId, toolName, updated.canonicalFingerprint)
            }
            return saved
        }
    }

    private fun evaluateSingleTool(
        registered: RegisteredMcpTool,
        shadowings: List<ShadowingFinding>,
    ): ToolEvaluationResult {
        val providerId = registered.providerId
        val toolName = registered.definition.name
        val fingerprint = ToolDnaFingerprinter.computeFingerprint(registered)
        val securityFindings = ToolContentScanner.scan(registered.definition)
        val toolShadowings = shadowings.filter { it.toolName == toolName }
        val now = System.currentTimeMillis()

        if (baselineStore.isCorrupted) {
            return ToolEvaluationResult(
                providerId = providerId,
                toolName = toolName,
                trustState = SentinelTrustState.REVIEW_REQUIRED,
                currentFingerprint = fingerprint,
                baselineRecord = null,
                diffResult = null,
                securityFindings = securityFindings,
                shadowingFindings = toolShadowings,
                reason = "ToolDNA baseline store is corrupted on disk; operator review required.",
            )
        }

        val baseline = baselineStore.getBaseline(providerId, toolName)
        val ctx = EvaluationContext(providerId, toolName, fingerprint, baseline, securityFindings, toolShadowings)

        return when {
            baseline == null -> SentinelEvaluator.evaluateNewTool(baselineStore, ::recordAuditEvent, ctx, now)
            baseline.trustState == SentinelTrustState.BLOCKED -> SentinelEvaluator.evaluateBlockedTool(ctx)
            baseline.canonicalFingerprint == fingerprint.fingerprint -> SentinelEvaluator.evaluateUnchangedTool(ctx)
            else -> SentinelEvaluator.evaluateChangedTool(registered, ::recordAuditEvent, ctx)
        }
    }

    private fun recordAuditEvent(
        event: String,
        providerId: String,
        toolName: String,
        fingerprint: String,
    ) {
        logger.info(
            LogCategory.SYSTEM,
            "MCP Sentinel Event: $event",
            mapOf("providerId" to providerId, "tool" to toolName, "fingerprint" to fingerprint.take(16)),
        )
        ledger?.record(
            toolName = toolName,
            providerId = providerId,
            policyApplied = McpPolicyAction.DENY,
            approvalDisposition = McpApprovalDisposition.SENTINEL_BLOCKED,
            durationMs = 0L,
            isError = true,
            rawArgs = mapOf("event" to event, "fingerprint" to fingerprint.take(16)),
            errorSnippet = "MCP Sentinel Event: $event (fingerprint: ${fingerprint.take(16)})",
            countsAsCall = false,
        )
    }
}

private object SentinelEvaluator {
    fun evaluateNewTool(
        baselineStore: ToolDnaBaselineStore,
        recordAuditEvent: (String, String, String, String) -> Unit,
        ctx: EvaluationContext,
        now: Long,
    ): ToolEvaluationResult {
        val isSuspicious = ctx.securityFindings.any { it.severity >= FindingSeverity.HIGH }
        val hasShadowing = ctx.toolShadowings.isNotEmpty()
        val state =
            when {
                isSuspicious -> SentinelTrustState.SUSPICIOUS
                hasShadowing -> SentinelTrustState.REVIEW_REQUIRED
                else -> SentinelTrustState.NEW
            }

        val reason =
            when {
                isSuspicious -> "New tool detected with suspicious findings (${ctx.securityFindings.size})"
                hasShadowing -> "New tool detected with cross-provider shadowing collision"
                else -> "First time observing tool definition"
            }

        val record =
            ToolBaselineRecord(
                providerId = ctx.providerId,
                toolName = ctx.toolName,
                canonicalFingerprint = ctx.fingerprint.fingerprint,
                fingerprintVersion = ctx.fingerprint.algorithmVersion,
                firstSeenTimestamp = now,
                lastSeenTimestamp = now,
                trustState = state,
                lastAcceptedDescription = ctx.fingerprint.canonicalDescription,
                lastAcceptedSchemaJson = ctx.fingerprint.canonicalInputSchemaJson,
                readOnly = ctx.fingerprint.readOnly,
                requiresAdmin = ctx.fingerprint.requiresAdmin,
                findings = ctx.securityFindings,
                reasonForReevaluation = reason,
            )
        baselineStore.saveBaseline(record)
        recordAuditEvent("TOOL_FIRST_SEEN", ctx.providerId, ctx.toolName, ctx.fingerprint.fingerprint)

        return ToolEvaluationResult(
            providerId = ctx.providerId,
            toolName = ctx.toolName,
            trustState = state,
            currentFingerprint = ctx.fingerprint,
            baselineRecord = record,
            diffResult = null,
            securityFindings = ctx.securityFindings,
            shadowingFindings = ctx.toolShadowings,
            reason = reason,
        )
    }

    fun evaluateBlockedTool(ctx: EvaluationContext): ToolEvaluationResult =
        ToolEvaluationResult(
            providerId = ctx.providerId,
            toolName = ctx.toolName,
            trustState = SentinelTrustState.BLOCKED,
            currentFingerprint = ctx.fingerprint,
            baselineRecord = ctx.baseline,
            diffResult = null,
            securityFindings = ctx.securityFindings,
            shadowingFindings = ctx.toolShadowings,
            reason = "Tool is explicitly BLOCKED by operator policy.",
        )

    fun evaluateUnchangedTool(ctx: EvaluationContext): ToolEvaluationResult {
        val baseline = checkNotNull(ctx.baseline)
        val isSuspicious = ctx.securityFindings.any { it.severity >= FindingSeverity.HIGH }
        val state =
            when {
                baseline.trustState == SentinelTrustState.TRUSTED || baseline.userDecision == "APPROVED" -> {
                    SentinelTrustState.TRUSTED
                }

                isSuspicious -> {
                    SentinelTrustState.SUSPICIOUS
                }

                else -> {
                    baseline.trustState
                }
            }

        return ToolEvaluationResult(
            providerId = ctx.providerId,
            toolName = ctx.toolName,
            trustState = state,
            currentFingerprint = ctx.fingerprint,
            baselineRecord = baseline,
            diffResult = null,
            securityFindings = ctx.securityFindings,
            shadowingFindings = ctx.toolShadowings,
            reason =
                if (isSuspicious && state != SentinelTrustState.TRUSTED) {
                    "Matches baseline fingerprint but contains security findings"
                } else {
                    "Matches trusted baseline"
                },
        )
    }

    fun evaluateChangedTool(
        registered: RegisteredMcpTool,
        recordAuditEvent: (String, String, String, String) -> Unit,
        ctx: EvaluationContext,
    ): ToolEvaluationResult {
        val baseline = checkNotNull(ctx.baseline)
        val diff =
            ToolDnaDiffEngine.computeDiff(
                oldDescription = baseline.lastAcceptedDescription,
                oldSchemaJson = baseline.lastAcceptedSchemaJson,
                oldReadOnly = baseline.readOnly,
                oldRequiresAdmin = baseline.requiresAdmin,
                newDefinition = registered.definition,
            )

        val hasHighSeverity = ctx.securityFindings.any { it.severity >= FindingSeverity.HIGH }
        val hasExpansion =
            diff.categories.contains(ChangeCategory.CAPABILITY_EXPANSION) ||
                diff.categories.contains(ChangeCategory.DESTRUCTIVE_PARAMETER_ADDED)

        val newState =
            when {
                hasHighSeverity -> SentinelTrustState.SUSPICIOUS
                hasExpansion -> SentinelTrustState.REVIEW_REQUIRED
                else -> SentinelTrustState.CHANGED
            }

        val prevFp = baseline.canonicalFingerprint.take(8)
        val newFp = ctx.fingerprint.fingerprint.take(8)
        val reason = "Tool definition changed! Previous: $prevFp, New: $newFp"
        recordAuditEvent("TOOL_DEFINITION_CHANGED", ctx.providerId, ctx.toolName, ctx.fingerprint.fingerprint)

        return ToolEvaluationResult(
            providerId = ctx.providerId,
            toolName = ctx.toolName,
            trustState = newState,
            currentFingerprint = ctx.fingerprint,
            baselineRecord = baseline,
            diffResult = diff,
            securityFindings = ctx.securityFindings,
            shadowingFindings = ctx.toolShadowings,
            reason = reason,
        )
    }
}
