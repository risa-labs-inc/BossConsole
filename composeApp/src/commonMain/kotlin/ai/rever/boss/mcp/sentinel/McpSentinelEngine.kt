package ai.rever.boss.mcp.sentinel

import ai.rever.boss.mcp.McpOperationLedger
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
     * Computes fingerprints, diffs, static scans, shadowing, and updates evaluation state flow.
     */
    fun evaluateAll(tools: List<RegisteredMcpTool>): List<ToolEvaluationResult> {
        val shadowings = ToolShadowingDetector.detectShadowing(tools)
        val shadowingMap = shadowings.groupBy { "${it.collidingProviderId}/${it.toolName}" }

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
            val evaluated = evaluateAll(listOf(registeredTool))
            eval = evaluated.firstOrNull()
        }

        val targetEval = eval ?: return SentinelInvocationCheck(
            isAllowed = true,
            trustState = SentinelTrustState.UNKNOWN,
            reason = null,
            evaluationResult = null,
        )

        return when (targetEval.trustState) {
            SentinelTrustState.BLOCKED -> SentinelInvocationCheck(
                isAllowed = false,
                trustState = targetEval.trustState,
                reason = "MCP Sentinel: Tool '$toolName' from provider '$providerId' is BLOCKED by security policy.",
                evaluationResult = targetEval,
            )
            SentinelTrustState.CHANGED -> SentinelInvocationCheck(
                isAllowed = false,
                trustState = targetEval.trustState,
                reason = "MCP Sentinel: Tool '$toolName' definition changed (Rug Pull detected). Re-approval required.",
                evaluationResult = targetEval,
            )
            SentinelTrustState.SUSPICIOUS -> SentinelInvocationCheck(
                isAllowed = false,
                trustState = targetEval.trustState,
                reason = "MCP Sentinel: Tool '$toolName' flagged for suspicious content: ${targetEval.reason}",
                evaluationResult = targetEval,
            )
            SentinelTrustState.REVIEW_REQUIRED -> SentinelInvocationCheck(
                isAllowed = false,
                trustState = targetEval.trustState,
                reason = "MCP Sentinel: Tool '$toolName' definition updated with structural changes. Operator review required.",
                evaluationResult = targetEval,
            )
            SentinelTrustState.NEW, SentinelTrustState.TRUSTED, SentinelTrustState.UNKNOWN -> SentinelInvocationCheck(
                isAllowed = true,
                trustState = targetEval.trustState,
                reason = null,
                evaluationResult = targetEval,
            )
        }
    }

    /**
     * Explicitly approve a tool definition change and update its baseline.
     * Transitions state to [SentinelTrustState.TRUSTED].
     */
    fun approveAndTrustTool(providerId: String, toolName: String, registeredTool: RegisteredMcpTool? = null): Boolean {
        synchronized(lock) {
            val key = "$providerId/$toolName"
            val eval = _evaluations.value[key]

            val currentFingerprint = eval?.currentFingerprint
                ?: registeredTool?.let { ToolDnaFingerprinter.computeFingerprint(it) }
                ?: return false

            val existingBaseline = baselineStore.getBaseline(providerId, toolName)
            val now = System.currentTimeMillis()

            val history = (existingBaseline?.fingerprintHistory.orEmpty() + currentFingerprint.fingerprint).distinct()
            val changeHist = (existingBaseline?.changeHistory.orEmpty() + "Approved by user at $now").takeLast(50)

            val newRecord = ToolBaselineRecord(
                providerId = providerId,
                toolName = toolName,
                canonicalFingerprint = currentFingerprint.fingerprint,
                fingerprintVersion = currentFingerprint.algorithmVersion,
                firstSeenTimestamp = existingBaseline?.firstSeenTimestamp ?: now,
                lastSeenTimestamp = now,
                trustState = SentinelTrustState.TRUSTED,
                lastAcceptedDescription = currentFingerprint.canonicalDescription,
                lastAcceptedSchemaJson = currentFingerprint.canonicalInputSchemaJson,
                fingerprintHistory = history,
                changeHistory = changeHist,
                reasonForReevaluation = null,
                findings = emptyList(),
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
            }
            return saved
        }
    }

    /**
     * Explicitly block a tool.
     */
    fun blockTool(providerId: String, toolName: String): Boolean {
        synchronized(lock) {
            val existing = baselineStore.getBaseline(providerId, toolName)
            val now = System.currentTimeMillis()
            val updated = (existing ?: ToolBaselineRecord(
                providerId = providerId,
                toolName = toolName,
                canonicalFingerprint = "",
                firstSeenTimestamp = now,
                lastSeenTimestamp = now,
                trustState = SentinelTrustState.BLOCKED,
                lastAcceptedDescription = "",
                lastAcceptedSchemaJson = "",
            )).copy(
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
     * Unblock a tool and reset its trust state to UNKNOWN / NEW for re-evaluation.
     */
    fun unblockTool(providerId: String, toolName: String): Boolean {
        synchronized(lock) {
            val existing = baselineStore.getBaseline(providerId, toolName) ?: return false
            val updated = existing.copy(
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
        val def = registered.definition
        val toolName = def.name

        val fingerprint = ToolDnaFingerprinter.computeFingerprint(registered)
        val baseline = baselineStore.getBaseline(providerId, toolName)

        val securityFindings = ToolContentScanner.scan(def)
        val toolShadowings = shadowings.filter { it.toolName == toolName }

        val now = System.currentTimeMillis()

        if (baseline == null) {
            // New tool never seen before
            val isSuspicious = securityFindings.any { it.severity >= FindingSeverity.HIGH }
            val state = if (isSuspicious) SentinelTrustState.SUSPICIOUS else SentinelTrustState.NEW

            val reason = when {
                isSuspicious -> "New tool detected with suspicious findings (${securityFindings.size})"
                toolShadowings.isNotEmpty() -> "New tool detected with cross-provider shadowing collision"
                else -> "First time observing tool definition"
            }

            // Auto-create initial baseline as NEW
            val record = ToolBaselineRecord(
                providerId = providerId,
                toolName = toolName,
                canonicalFingerprint = fingerprint.fingerprint,
                fingerprintVersion = fingerprint.algorithmVersion,
                firstSeenTimestamp = now,
                lastSeenTimestamp = now,
                trustState = state,
                lastAcceptedDescription = fingerprint.canonicalDescription,
                lastAcceptedSchemaJson = fingerprint.canonicalInputSchemaJson,
                findings = securityFindings,
                reasonForReevaluation = reason,
            )
            baselineStore.saveBaseline(record)
            recordAuditEvent("TOOL_FIRST_SEEN", providerId, toolName, fingerprint.fingerprint)

            return ToolEvaluationResult(
                providerId = providerId,
                toolName = toolName,
                trustState = state,
                currentFingerprint = fingerprint,
                baselineRecord = record,
                diffResult = null,
                securityFindings = securityFindings,
                shadowingFindings = toolShadowings,
                reason = reason,
            )
        }

        // Check explicit user block
        if (baseline.trustState == SentinelTrustState.BLOCKED) {
            return ToolEvaluationResult(
                providerId = providerId,
                toolName = toolName,
                trustState = SentinelTrustState.BLOCKED,
                currentFingerprint = fingerprint,
                baselineRecord = baseline,
                diffResult = null,
                securityFindings = securityFindings,
                shadowingFindings = toolShadowings,
                reason = "Tool is explicitly BLOCKED by operator policy.",
            )
        }

        // Compare fingerprints
        if (baseline.canonicalFingerprint == fingerprint.fingerprint) {
            // Definition matches baseline!
            val isSuspicious = securityFindings.any { it.severity >= FindingSeverity.HIGH }
            val state = when {
                baseline.trustState == SentinelTrustState.TRUSTED || baseline.userDecision == "APPROVED" -> SentinelTrustState.TRUSTED
                isSuspicious -> SentinelTrustState.SUSPICIOUS
                else -> baseline.trustState
            }

            return ToolEvaluationResult(
                providerId = providerId,
                toolName = toolName,
                trustState = state,
                currentFingerprint = fingerprint,
                baselineRecord = baseline,
                diffResult = null,
                securityFindings = securityFindings,
                shadowingFindings = toolShadowings,
                reason = if (isSuspicious && state != SentinelTrustState.TRUSTED) {
                    "Matches baseline fingerprint but contains security findings"
                } else {
                    "Matches trusted baseline"
                },
            )
        }

        // FINGERPRINT MISMATCH -> RUG PULL / DEFINITION CHANGED!
        val diff = ToolDnaDiffEngine.computeDiff(
            oldDescription = baseline.lastAcceptedDescription,
            oldSchemaJson = baseline.lastAcceptedSchemaJson,
            oldReadOnly = false,
            oldRequiresAdmin = false,
            newDefinition = def,
        )

        val hasHighSeverityFindings = securityFindings.any { it.severity >= FindingSeverity.HIGH }
        val hasCapabilityExpansion = diff.categories.contains(ChangeCategory.CAPABILITY_EXPANSION) ||
                diff.categories.contains(ChangeCategory.DESTRUCTIVE_PARAMETER_ADDED)

        val newState = when {
            hasHighSeverityFindings -> SentinelTrustState.SUSPICIOUS
            hasCapabilityExpansion -> SentinelTrustState.REVIEW_REQUIRED
            else -> SentinelTrustState.CHANGED
        }

        val reason = "Tool definition changed! Previous: ${baseline.canonicalFingerprint.take(8)}, New: ${fingerprint.fingerprint.take(8)}"

        recordAuditEvent("TOOL_DEFINITION_CHANGED", providerId, toolName, fingerprint.fingerprint)

        return ToolEvaluationResult(
            providerId = providerId,
            toolName = toolName,
            trustState = newState,
            currentFingerprint = fingerprint,
            baselineRecord = baseline,
            diffResult = diff,
            securityFindings = securityFindings,
            shadowingFindings = toolShadowings,
            reason = reason,
        )
    }

    private fun recordAuditEvent(event: String, providerId: String, toolName: String, fingerprint: String) {
        logger.info(
            LogCategory.SYSTEM,
            "MCP Sentinel Event: $event",
            mapOf("providerId" to providerId, "tool" to toolName, "fingerprint" to fingerprint.take(16)),
        )
    }
}
