package ai.rever.boss.mcp

import ai.rever.boss.mcp.sandbox.McpRiskAssessment
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
    val requestedAt: Long = System.currentTimeMillis(),
    val deferred: CompletableDeferred<McpApprovalDecision> = CompletableDeferred(),
)

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
    @Suppress("ReturnCount") // Both active and delivery queues must reject overflow before awaiting an answer.
    suspend fun requestApproval(
        toolName: String,
        providerId: String,
        arguments: Map<String, Any?>,
        timeoutMs: Long = defaultTimeoutMs,
        riskAssessment: McpRiskAssessment? = null,
    ): McpApprovalDecision {
        val request =
            McpApprovalRequest(
                toolName = toolName,
                providerId = providerId,
                arguments = McpArgumentSanitizer.sanitize(arguments),
                timeoutMs = timeoutMs,
                riskAssessment = riskAssessment,
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
            synchronized(lock) {
                activeRequests.remove(request.id)
                _pendingList.update { list -> list.filterNot { it.id == request.id } }
            }
            return McpApprovalDecision.QueueFull
        }

        logger.info(
            LogCategory.SYSTEM,
            "Approval requested for MCP tool",
            mapOf("tool" to toolName, "requestId" to request.id, "timeoutMs" to timeoutMs),
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
            synchronized(lock) {
                activeRequests.remove(request.id)
                _pendingList.update { list -> list.filterNot { it.id == request.id } }
            }
        }
    }

    /**
     * Approve the request with [requestId].
     */
    fun approve(
        requestId: String,
        trustForSession: Boolean = false,
        persistPolicy: Boolean = false,
    ): Boolean {
        val req = activeRequests[requestId] ?: return false
        val completed = req.deferred.complete(McpApprovalDecision.Approved(trustForSession, persistPolicy))
        if (completed) {
            logger.info(
                LogCategory.SYSTEM,
                "Operator approved tool execution",
                mapOf("tool" to req.toolName, "trustForSession" to trustForSession, "persistPolicy" to persistPolicy),
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
