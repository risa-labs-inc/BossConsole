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

private data class SentinelAuditEvent(
    val event: String,
    val providerId: String,
    val toolName: String,
    val fingerprint: String,
    val disposition: McpApprovalDisposition,
    val isError: Boolean,
)

private data class BlockedEvalRequest(
    val providerId: String,
    val toolName: String,
    val updated: ToolBaselineRecord,
    val currentFp: String,
    val registeredTool: RegisteredMcpTool?,
    val currentEval: ToolEvaluationResult?,
)

internal fun makeSentinelKey(
    providerId: String,
    toolName: String,
): String = "${providerId.trim().lowercase()}/$toolName"

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
                evalMap[makeSentinelKey(eval.providerId, eval.toolName)] = eval
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
            val key = makeSentinelKey(eval.providerId, eval.toolName)
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
        val key = makeSentinelKey(providerId, toolName)
        var eval = _evaluations.value[key]
        if (eval == null && registeredTool != null) {
            eval = evaluateSingleToolAndMerge(registeredTool)
        }

        val targetEval =
            eval ?: return SentinelInvocationCheck(
                isAllowed = false,
                trustState = SentinelTrustState.UNKNOWN,
                reason = "MCP Sentinel: Tool evaluation state is UNKNOWN; invocation refused.",
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

                SentinelTrustState.UNKNOWN -> {
                    false to "MCP Sentinel: Tool evaluation state is UNKNOWN; invocation refused."
                }

                SentinelTrustState.NEW, SentinelTrustState.TRUSTED -> {
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
            val key = makeSentinelKey(providerId, toolName)
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
            recordAuditEvent(
                SentinelAuditEvent(
                    event = "TOOL_REAPPROVED",
                    providerId = providerId,
                    toolName = toolName,
                    fingerprint = currentFingerprint.fingerprint,
                    disposition = McpApprovalDisposition.SENTINEL_EVALUATED,
                    isError = false,
                ),
            )
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
        registeredTool: RegisteredMcpTool? = null,
    ): Boolean {
        synchronized(lock) {
            val existing = baselineStore.getBaseline(providerId, toolName)
            val eval = _evaluations.value[makeSentinelKey(providerId, toolName)]
            val currentFp =
                existing?.canonicalFingerprint?.takeIf { it.isNotBlank() }
                    ?: eval?.currentFingerprint?.fingerprint
                    ?: registeredTool?.let { ToolDnaFingerprinter.computeFingerprint(it).fingerprint }
                    ?: ""
            val now = System.currentTimeMillis()
            val updated =
                (
                    existing ?: ToolBaselineRecord(
                        providerId = providerId,
                        toolName = toolName,
                        canonicalFingerprint = currentFp,
                        firstSeenTimestamp = now,
                        lastSeenTimestamp = now,
                        trustState = SentinelTrustState.BLOCKED,
                        lastAcceptedDescription = eval?.currentFingerprint?.canonicalDescription ?: "",
                        lastAcceptedSchemaJson = eval?.currentFingerprint?.canonicalInputSchemaJson ?: "",
                    )
                ).copy(
                    trustState = SentinelTrustState.BLOCKED,
                    userDecision = "BLOCKED",
                    lastSeenTimestamp = now,
                )

            val saved = baselineStore.saveBaseline(updated)
            if (saved) {
                recordAuditEvent(
                    SentinelAuditEvent(
                        event = "TOOL_BLOCKED",
                        providerId = providerId,
                        toolName = toolName,
                        fingerprint = updated.canonicalFingerprint,
                        disposition = McpApprovalDisposition.SENTINEL_BLOCKED,
                        isError = true,
                    ),
                )
                val key = makeSentinelKey(providerId, toolName)
                val req =
                    BlockedEvalRequest(
                        providerId = providerId,
                        toolName = toolName,
                        updated = updated,
                        currentFp = currentFp,
                        registeredTool = registeredTool,
                        currentEval = _evaluations.value[key],
                    )
                val blockedEval = SentinelEvaluator.buildBlockedEvaluation(req)
                _evaluations.update { current -> current + (key to blockedEval) }
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
            val saved =
                if (existing.canonicalFingerprint.isBlank()) {
                    baselineStore.removeBaseline(providerId, toolName)
                } else {
                    val updated =
                        existing.copy(
                            trustState = SentinelTrustState.NEW,
                            userDecision = "UNBLOCKED",
                            lastSeenTimestamp = System.currentTimeMillis(),
                        )
                    baselineStore.saveBaseline(updated)
                }
            if (saved) {
                recordAuditEvent(
                    SentinelAuditEvent(
                        event = "TOOL_UNBLOCKED",
                        providerId = providerId,
                        toolName = toolName,
                        fingerprint = existing.canonicalFingerprint,
                        disposition = McpApprovalDisposition.SENTINEL_EVALUATED,
                        isError = false,
                    ),
                )
            }
            return saved
        }
    }

    /**
     * Recover a corrupted baseline store by moving it aside and placing all tools in REVIEW_REQUIRED.
     *
     * Recovery does NOT automatically restore trust. Every tool must be explicitly re-approved by
     * an operator before invocations are allowed. Calling [evaluateAll] here would assign NEW state
     * and allow invocations without oversight, which defeats the purpose of detecting corruption.
     */
    fun recoverCorruptedStore(tools: List<RegisteredMcpTool>): Boolean =
        synchronized(lock) {
            if (!baselineStore.isCorrupted) return true
            val cleared = baselineStore.backupAndClear()
            if (cleared) {
                logger.info(LogCategory.SYSTEM, "MCP Sentinel: Corrupted baseline store cleared and backed up")
                _evaluations.value = SentinelEvaluator.buildPostRecoveryEvals(baselineStore, ::makeSentinelKey, tools)
            } else {
                logger.error(LogCategory.SYSTEM, "MCP Sentinel: Failed to recover corrupted baseline store")
            }
            return cleared
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

    private fun recordAuditEvent(auditEvent: SentinelAuditEvent) {
        val policyAction =
            when (auditEvent.event) {
                "TOOL_BLOCKED", "TOOL_DEFINITION_CHANGED" -> {
                    McpPolicyAction.DENY
                }

                "TOOL_REAPPROVED" -> {
                    McpPolicyAction.ALLOW
                }

                "TOOL_UNBLOCKED" -> {
                    McpPolicyAction.ASK
                }

                "TOOL_FIRST_SEEN" -> {
                    if (auditEvent.isError) McpPolicyAction.DENY else McpPolicyAction.ALLOW
                }

                else -> {
                    if (auditEvent.isError) McpPolicyAction.DENY else McpPolicyAction.ASK
                }
            }

        logger.info(
            LogCategory.SYSTEM,
            "MCP Sentinel Event: ${auditEvent.event}",
            mapOf(
                "providerId" to auditEvent.providerId,
                "tool" to auditEvent.toolName,
                "fingerprint" to auditEvent.fingerprint.take(16),
            ),
        )

        ledger?.record(
            toolName = auditEvent.toolName,
            providerId = auditEvent.providerId,
            policyApplied = policyAction,
            approvalDisposition = auditEvent.disposition,
            durationMs = 0L,
            isError = auditEvent.isError,
            rawArgs =
                mapOf(
                    "event" to auditEvent.event,
                    "fingerprint" to auditEvent.fingerprint.take(16),
                ),
            errorSnippet =
                if (auditEvent.isError) {
                    "MCP Sentinel Event: ${auditEvent.event} " +
                        "(fingerprint: ${auditEvent.fingerprint.take(16)})"
                } else {
                    ""
                },
            countsAsCall = false,
        )
    }
}

private object SentinelEvaluator {
    fun evaluateNewTool(
        baselineStore: ToolDnaBaselineStore,
        recordAuditEvent: (SentinelAuditEvent) -> Unit,
        ctx: EvaluationContext,
        now: Long,
    ): ToolEvaluationResult {
        val isSuspicious = ctx.securityFindings.any { it.severity >= FindingSeverity.MEDIUM }
        val state =
            when {
                isSuspicious -> SentinelTrustState.SUSPICIOUS
                else -> SentinelTrustState.NEW
            }

        val reason =
            when {
                isSuspicious -> "New tool detected with suspicious findings (${ctx.securityFindings.size})"
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
        recordAuditEvent(
            SentinelAuditEvent(
                event = "TOOL_FIRST_SEEN",
                providerId = ctx.providerId,
                toolName = ctx.toolName,
                fingerprint = ctx.fingerprint.fingerprint,
                disposition = McpApprovalDisposition.SENTINEL_EVALUATED,
                isError = isSuspicious,
            ),
        )

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

    fun buildBlockedEvaluation(req: BlockedEvalRequest): ToolEvaluationResult {
        if (req.currentEval != null) {
            return req.currentEval.copy(
                trustState = SentinelTrustState.BLOCKED,
                baselineRecord = req.updated,
                reason = "Tool is explicitly BLOCKED by operator policy.",
            )
        }
        val fp =
            req.registeredTool?.let { ToolDnaFingerprinter.computeFingerprint(it) }
                ?: ToolDnaFingerprint(
                    providerId = req.providerId,
                    toolName = req.toolName,
                    fingerprint = req.currentFp,
                    canonicalDescription = "",
                    canonicalInputSchemaJson = "",
                    readOnly = false,
                    requiresAdmin = false,
                )
        return ToolEvaluationResult(
            providerId = req.providerId,
            toolName = req.toolName,
            trustState = SentinelTrustState.BLOCKED,
            currentFingerprint = fp,
            baselineRecord = req.updated,
            diffResult = null,
            securityFindings = emptyList(),
            shadowingFindings = emptyList(),
            reason = "Tool is explicitly BLOCKED by operator policy.",
        )
    }

    fun evaluateUnchangedTool(ctx: EvaluationContext): ToolEvaluationResult {
        val baseline = checkNotNull(ctx.baseline)
        val isSuspicious = ctx.securityFindings.any { it.severity >= FindingSeverity.MEDIUM }
        val state =
            when {
                // Trust is derived exclusively from trustState==TRUSTED on an HMAC-verified record.
                // The userDecision field is an audit label only and must never be used as an
                // authorization gate: a tampered record on disk can set userDecision="APPROVED"
                // while failing HMAC verification, which processLoadedRecords maps to
                // REVIEW_REQUIRED. Checking userDecision here would bypass that protection.
                baseline.trustState == SentinelTrustState.TRUSTED -> {
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
        recordAuditEvent: (SentinelAuditEvent) -> Unit,
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

        val hasMediumOrHighSeverity = ctx.securityFindings.any { it.severity >= FindingSeverity.MEDIUM }
        val hasExpansion =
            diff.categories.contains(ChangeCategory.CAPABILITY_EXPANSION) ||
                diff.categories.contains(ChangeCategory.DESTRUCTIVE_PARAMETER_ADDED)

        val newState =
            when {
                hasMediumOrHighSeverity -> SentinelTrustState.SUSPICIOUS
                hasExpansion -> SentinelTrustState.REVIEW_REQUIRED
                else -> SentinelTrustState.CHANGED
            }

        val prevFp = baseline.canonicalFingerprint.take(8)
        val newFp = ctx.fingerprint.fingerprint.take(8)
        val reason = "Tool definition changed! Previous: $prevFp, New: $newFp"
        recordAuditEvent(
            SentinelAuditEvent(
                event = "TOOL_DEFINITION_CHANGED",
                providerId = ctx.providerId,
                toolName = ctx.toolName,
                fingerprint = ctx.fingerprint.fingerprint,
                disposition = McpApprovalDisposition.SENTINEL_EVALUATED,
                isError = newState != SentinelTrustState.TRUSTED,
            ),
        )

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

    /**
     * Build the post-recovery evaluation map: every tool is placed in [SentinelTrustState.REVIEW_REQUIRED]
     * and a corresponding baseline record is persisted. Called by [McpSentinelEngine.forceReviewRequired]
     * to keep [McpSentinelEngine] under the function-count threshold.
     */
    fun buildPostRecoveryEvals(
        baselineStore: ToolDnaBaselineStore,
        makeKey: (String, String) -> String,
        tools: List<RegisteredMcpTool>,
    ): Map<String, ToolEvaluationResult> {
        val now = System.currentTimeMillis()
        val evalMap = mutableMapOf<String, ToolEvaluationResult>()
        for (registered in tools) {
            val providerId = registered.providerId
            val toolName = registered.definition.name
            val fingerprint = ToolDnaFingerprinter.computeFingerprint(registered)
            val securityFindings = ToolContentScanner.scan(registered.definition)
            val reason = "Baseline store was corrupted and cleared; operator re-approval required."
            val record =
                ToolBaselineRecord(
                    providerId = providerId,
                    toolName = toolName,
                    canonicalFingerprint = fingerprint.fingerprint,
                    fingerprintVersion = fingerprint.algorithmVersion,
                    firstSeenTimestamp = now,
                    lastSeenTimestamp = now,
                    trustState = SentinelTrustState.REVIEW_REQUIRED,
                    lastAcceptedDescription = fingerprint.canonicalDescription,
                    lastAcceptedSchemaJson = fingerprint.canonicalInputSchemaJson,
                    readOnly = fingerprint.readOnly,
                    requiresAdmin = fingerprint.requiresAdmin,
                    findings = securityFindings,
                    reasonForReevaluation =
                        "Baseline store was corrupted and cleared; " +
                            "operator re-approval required before invocation.",
                )
            baselineStore.saveBaseline(record)
            evalMap[makeKey(providerId, toolName)] =
                ToolEvaluationResult(
                    providerId = providerId,
                    toolName = toolName,
                    trustState = SentinelTrustState.REVIEW_REQUIRED,
                    currentFingerprint = fingerprint,
                    baselineRecord = record,
                    diffResult = null,
                    securityFindings = securityFindings,
                    shadowingFindings = emptyList(),
                    reason = reason,
                )
        }
        return evalMap
    }
}
