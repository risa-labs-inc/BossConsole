package ai.rever.boss.mcp

import ai.rever.boss.mcp.sandbox.McpRiskAssessment
import ai.rever.boss.mcp.secrets.SecretDescriptor
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The operator's decision on a suspended MCP tool invocation.
 */
sealed interface McpApprovalDecision {
    data class Approved(
        val trustForSession: Boolean = false,
        /**
         * Write this tool's policy to `~/.boss/mcp-tool-policy.json` as ALLOW, so it never
         * suspends for approval again - across restarts, not just this session. Independent
         * of [trustForSession]: a persisted ALLOW makes session trust redundant, so the registry does not also call
         * `trustForSession` when this is true.
         */
        val persistPolicy: Boolean = false,
        /**
         * "Trust this plugin": persist ALLOW for every tool [McpApprovalRequest.providerId]
         * contributes, not just this one tool or this one call.
         */
        val trustProvider: Boolean = false,
    ) : McpApprovalDecision

    data class Denied(
        val reason: String = "Operator rejected tool execution",
        /** Write this tool's policy to disk as DENY, so it is refused automatically from now on. */
        val persistPolicy: Boolean = false,
    ) : McpApprovalDecision

    data object Timeout : McpApprovalDecision

    data object QueueFull : McpApprovalDecision
}

/**
 * An interactive prompt asking the operator to permit or reject a tool call.
 */
data class McpApprovalRequest(
    val id: String = UUID.randomUUID().toString(),
    val toolName: String,
    val providerId: String,
    val arguments: Map<String, Any?>,
    val timeoutMs: Long,
    val riskAssessment: McpRiskAssessment? = null,
    /**
     * The tool's own [ai.rever.boss.plugin.api.McpToolDefinition.readOnly] declaration, captured
     * at invocation so the dialog's mutating-vs-read labeling cannot be spoofed by an innocent
     * tool name the way the policy gate could before #804. Null when no definition was in hand
     * when the request was raised, which leaves the name-only catalog to label it.
     */
    val declaredReadOnly: Boolean? = null,
    /**
     * The tool's declared description, captured at invocation so the operator approves with
     * sight of what the tool claims to do rather than a bare name. Null only when the request
     * was raised without the definition in hand.
     */
    val toolDescription: String? = null,
    /** The policy action that suspended this call - ASK today; carried so the dialog can say why. */
    val policy: McpPolicyAction? = null,
    /**
     * True when a saved ALLOW was overridden because this call rates CRITICAL (#1577). No saved
     * rule can pre-approve such a call - the gate asks again every time - so the dialog offers
     * only a one-off answer here, and the registry treats any broader approval as once (#1624).
     */
    val escalated: Boolean = false,
    /**
     * The secrets this call would hand the tool, one per reference in its arguments. Metadata
     * only (website, username, field): the values are never on this object, so a dialog cannot
     * show them by accident. Empty for every call without references.
     */
    val secretRefs: List<SecretDescriptor> = emptyList(),
    val requestedAt: Long = System.currentTimeMillis(),
    val deferred: CompletableDeferred<McpApprovalDecision> = CompletableDeferred(),
) {
    /**
     * Milliseconds left before this request auto-denies, relative to [requestedAt].
     * Nothing here ticks. The dialog's one countdown is `approvalMillisRemaining`, which reads
     * the same `requestedAt + timeoutMs` deadline that [McpApprovalBus.requestApproval] enforces.
     */
    fun remainingTimeoutMs(): Long = (timeoutMs - (System.currentTimeMillis() - requestedAt)).coerceAtLeast(0)
}

/**
 * Central event bus for routing interactive tool approval requests to the UI.
 *
 * Uses a buffered Channel to ensure the calling MCP suspend coroutine can submit
 * its request without blocking or stalling background threads.
 */
open class McpApprovalBus(
    private val defaultTimeoutMs: Long = 45_000L,
    private val maxPendingRequests: Int = 4,
) {
    private val logger = BossLogger.forComponent("McpApprovalBus")
    private val lock = Any()

    private val _requests = Channel<McpApprovalRequest>(maxPendingRequests)
    val requests: Flow<McpApprovalRequest> = _requests.receiveAsFlow()

    private val activeRequests = ConcurrentHashMap<String, McpApprovalRequest>()
    private val _pendingList = MutableStateFlow<List<McpApprovalRequest>>(emptyList())
    val pendingList: StateFlow<List<McpApprovalRequest>> = _pendingList.asStateFlow()

    /**
     * Request approval from the operator for [toolName].
     *
     * Suspends the calling coroutine until the operator answers via the UI
     * or [timeoutMs] elapses (in which case it fails closed).
     */
    // Queue overflow needs its own returns; the request carries the tool's full approval
    // context, from name and provider to its own read-only declaration and the secrets it
    // would receive. Folding those into a builder would move the same names one call deeper.
    @Suppress("ReturnCount", "LongParameterList")
    suspend fun requestApproval(
        toolName: String,
        providerId: String,
        arguments: Map<String, Any?>,
        timeoutMs: Long = defaultTimeoutMs,
        riskAssessment: McpRiskAssessment? = null,
        declaredReadOnly: Boolean? = null,
        toolDescription: String? = null,
        policy: McpPolicyAction? = null,
        escalated: Boolean = false,
        secretRefs: List<SecretDescriptor> = emptyList(),
    ): McpApprovalDecision {
        val request =
            McpApprovalRequest(
                toolName = toolName,
                providerId = providerId,
                arguments = McpArgumentSanitizer.sanitize(arguments),
                timeoutMs = timeoutMs,
                riskAssessment = riskAssessment,
                declaredReadOnly = declaredReadOnly,
                toolDescription = toolDescription,
                policy = policy,
                escalated = escalated,
                secretRefs = secretRefs,
            )

        synchronized(lock) {
            if (_pendingList.value.size >= maxPendingRequests) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Approval request dropped - buffer full",
                    mapOf("tool" to toolName),
                )
                return McpApprovalDecision.QueueFull
            }
            activeRequests[request.id] = request
            _pendingList.update { it + request }
        }

        if (_requests.trySend(request).isFailure) {
            release(request)
            return McpApprovalDecision.QueueFull
        }

        logger.info(
            LogCategory.SYSTEM,
            "Approval requested for MCP tool",
            mapOf(
                "tool" to toolName,
                "requestId" to request.id,
                "timeoutMs" to timeoutMs,
                "secretRefs" to secretRefs.size,
            ),
        )

        return try {
            val decision =
                withTimeoutOrNull(timeoutMs) {
                    request.deferred.await()
                } ?: McpApprovalDecision.Timeout

            if (decision is McpApprovalDecision.Timeout) {
                request.deferred.complete(McpApprovalDecision.Timeout)
                logger.warn(
                    LogCategory.SYSTEM,
                    "Approval request timed out - failing closed",
                    mapOf("tool" to toolName, "requestId" to request.id),
                )
            }
            decision
        } finally {
            request.deferred.complete(McpApprovalDecision.Denied("Approval request expired"))
            release(request)
        }
    }

    /** Forget [request]: it was answered, timed out, or could not be delivered. */
    private fun release(request: McpApprovalRequest) {
        synchronized(lock) {
            activeRequests.remove(request.id)
            _pendingList.update { list -> list.filterNot { it.id == request.id } }
        }
    }

    /**
     * Approve the request with [requestId].
     */
    fun approve(
        requestId: String,
        trustForSession: Boolean = false,
        persistPolicy: Boolean = false,
        trustProvider: Boolean = false,
    ): Boolean {
        val req = activeRequests[requestId] ?: return false
        val completed =
            req.deferred.complete(
                McpApprovalDecision.Approved(trustForSession, persistPolicy, trustProvider),
            )
        if (completed) {
            logger.info(
                LogCategory.SYSTEM,
                "Operator approved tool execution",
                mapOf(
                    "tool" to req.toolName,
                    "trustForSession" to trustForSession,
                    "persistPolicy" to persistPolicy,
                    "trustProvider" to trustProvider,
                ),
            )
        }
        return completed
    }

    /**
     * Deny the request with [requestId] and optional explanation.
     */
    fun deny(
        requestId: String,
        reason: String = "Operator rejected tool execution",
        persistPolicy: Boolean = false,
    ): Boolean {
        val req = activeRequests[requestId] ?: return false
        val completed = req.deferred.complete(McpApprovalDecision.Denied(reason, persistPolicy))
        if (completed) {
            logger.info(
                LogCategory.SYSTEM,
                "Operator denied tool execution",
                mapOf("tool" to req.toolName, "persistPolicy" to persistPolicy),
            )
        }
        return completed
    }

    /**
     * Reject every request that is pending at the instant this method takes its snapshot.
     *
     * New requests may arrive immediately afterwards and are deliberately left alone: this is an
     * operator response to the queue they can see, not a hidden global kill-switch. The snapshot
     * is taken under the same lock that admits requests so a request cannot be half-registered
     * while the bulk decision is assembled. Each caller still removes its own request from
     * [pendingList] in [requestApproval]'s `finally` block, preserving the single cleanup path.
     *
     * Bulk rejection never persists a policy. A burst of unrelated calls must not turn one click
     * into a durable DENY for multiple tools or providers.
     *
     * @return the number of callers whose still-pending decision was completed by this call.
     */
    fun denyAllPending(reason: String = "Operator rejected all pending actions"): Int {
        val pending = synchronized(lock) { activeRequests.values.toList() }
        val denied = pending.count { it.deferred.complete(McpApprovalDecision.Denied(reason)) }
        if (denied > 0) {
            logger.info(
                LogCategory.SYSTEM,
                "Operator denied all pending MCP tool executions",
                mapOf("count" to denied),
            )
        }
        return denied
    }
}

/** Each delivered request belongs to one window until answered, timed out or that window closes. */
suspend fun McpApprovalBus.consumeApprovals(show: (McpApprovalRequest?) -> Unit) {
    requests.collect { request ->
        if (!request.deferred.isCompleted) {
            try {
                show(request)
                request.deferred.await()
            } finally {
                request.deferred.complete(McpApprovalDecision.Denied("Approval window closed"))
                show(null)
            }
        }
    }
}
