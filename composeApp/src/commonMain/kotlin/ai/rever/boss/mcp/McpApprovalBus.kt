package ai.rever.boss.mcp

import ai.rever.boss.mcp.sandbox.McpRiskAssessment
import ai.rever.boss.plugin.window.WorkspaceContextToken
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
 *
 * @property contextToken Optional causal workspace context identifying the window,
 *   project, and generation epoch that authorized this invocation.
 */
data class McpApprovalRequest(
    val id: String = UUID.randomUUID().toString(),
    val toolName: String,
    val providerId: String,
    val arguments: Map<String, Any?>,
    val timeoutMs: Long,
    val riskAssessment: McpRiskAssessment? = null,
    val contextToken: WorkspaceContextToken? = null,
    val requestedAt: Long = System.currentTimeMillis(),
    val deferred: CompletableDeferred<McpApprovalDecision> = CompletableDeferred(),
)

/**
 * Central event bus for routing interactive tool approval requests to the UI.
 *
 * Uses a buffered SharedFlow with window-affinity claiming to ensure each request
 * is routed only to the window that initiated it (preventing cross-window prompt stealing),
 * while supporting reactive invalidation if the workspace context shifts while queued.
 */
open class McpApprovalBus(
    private val defaultTimeoutMs: Long = 45_000L,
    private val maxPendingRequests: Int = 4,
) {
    private val logger = BossLogger.forComponent("McpApprovalBus")
    private val lock = Any()

    private val _requests =
        MutableSharedFlow<McpApprovalRequest>(
            replay = 0,
            extraBufferCapacity = maxPendingRequests * 4,
        )
    val requests: Flow<McpApprovalRequest> = _requests.asSharedFlow()
    val subscriptionCount: StateFlow<Int> = _requests.subscriptionCount

    private val activeRequests = ConcurrentHashMap<String, McpApprovalRequest>()
    private val claimedWindows = ConcurrentHashMap<String, String>() // requestId -> windowId

    private val _pendingList = MutableStateFlow<List<McpApprovalRequest>>(emptyList())
    val pendingList: StateFlow<List<McpApprovalRequest>> = _pendingList.asStateFlow()

    /**
     * Request approval from the operator for [toolName].
     *
     * Suspends the calling coroutine until the operator answers via the UI,
     * [timeoutMs] elapses (failing closed), or the context transitions.
     */
    @Suppress("ReturnCount") // Both active and delivery queues must reject overflow before awaiting an answer.
    suspend fun requestApproval(
        toolName: String,
        providerId: String,
        arguments: Map<String, Any?>,
        timeoutMs: Long = defaultTimeoutMs,
        riskAssessment: McpRiskAssessment? = null,
        contextToken: WorkspaceContextToken? = null,
    ): McpApprovalDecision {
        val request =
            McpApprovalRequest(
                toolName = toolName,
                providerId = providerId,
                arguments = McpArgumentSanitizer.sanitize(arguments),
                timeoutMs = timeoutMs,
                riskAssessment = riskAssessment,
                contextToken = contextToken,
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

        if (!_requests.tryEmit(request)) {
            synchronized(lock) {
                activeRequests.remove(request.id)
                _pendingList.update { list -> list.filterNot { it.id == request.id } }
            }
            return McpApprovalDecision.QueueFull
        }

        logger.info(
            LogCategory.SYSTEM,
            "Approval requested for MCP tool",
            mapOf(
                "tool" to toolName,
                "requestId" to request.id,
                "timeoutMs" to timeoutMs,
                "windowId" to contextToken?.windowId,
                "project" to contextToken?.projectPath,
                "generation" to contextToken?.generation,
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
            claimedWindows.remove(request.id)
            synchronized(lock) {
                activeRequests.remove(request.id)
                _pendingList.update { list -> list.filterNot { it.id == request.id } }
            }
        }
    }

    /**
     * Atomically claims [requestId] for [windowId].
     * Returns true if successfully claimed by this window, false if already claimed or ineligible.
     */
    fun claimRequest(requestId: String, windowId: String?): Boolean {
        val req = activeRequests[requestId] ?: return false
        if (req.deferred.isCompleted) return false
        val targetWindow = req.contextToken?.windowId
        if (targetWindow != null && windowId != null && targetWindow != windowId) {
            return false
        }
        val claimKey = windowId ?: "unbound"
        return claimedWindows.putIfAbsent(requestId, claimKey) == null
    }

    /** Releases a window's claim on [requestId]. */
    fun releaseClaim(requestId: String) {
        claimedWindows.remove(requestId)
    }

    /**
     * Reactively invalidates and denies all pending approvals for [windowId] whose
     * generation token has been superseded by [newGeneration].
     */
    fun invalidateForWindow(windowId: String, newGeneration: Long) {
        activeRequests.values.forEach { req ->
            val token = req.contextToken
            if (token != null && token.windowId == windowId && token.generation < newGeneration) {
                logger.info(
                    LogCategory.SYSTEM,
                    "Cancelling stale MCP approval request due to project transition",
                    mapOf("requestId" to req.id, "windowId" to windowId, "oldGen" to token.generation, "newGen" to newGeneration),
                )
                req.deferred.complete(McpApprovalDecision.Denied("Workspace context changed while awaiting operator approval"))
            }
        }
    }

    /**
     * Cancels any approval request targeted to or claimed by a window that is closing.
     */
    fun invalidateForClosedWindow(windowId: String) {
        activeRequests.values.forEach { req ->
            val token = req.contextToken
            val claimedBy = claimedWindows[req.id]
            if ((token != null && token.windowId == windowId) || claimedBy == windowId) {
                logger.info(
                    LogCategory.SYSTEM,
                    "Cancelling MCP approval request due to window close",
                    mapOf("requestId" to req.id, "windowId" to windowId),
                )
                req.deferred.complete(McpApprovalDecision.Denied("Window '$windowId' closed while awaiting operator approval"))
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

/**
 * Consumes approval requests for [targetWindowId]. If [targetWindowId] is specified,
 * filters out requests targeted at other windows, preventing cross-window prompt stealing.
 */
suspend fun McpApprovalBus.consumeApprovals(
    targetWindowId: String? = null,
    show: (McpApprovalRequest?) -> Unit,
) {
    requests.collect { request ->
        val reqWindowId = request.contextToken?.windowId
        if (reqWindowId != null && targetWindowId != null && reqWindowId != targetWindowId) {
            return@collect
        }
        if (claimRequest(request.id, targetWindowId)) {
            if (!request.deferred.isCompleted) {
                try {
                    show(request)
                    request.deferred.await()
                } finally {
                    request.deferred.complete(
                        McpApprovalDecision.Denied(
                            if (targetWindowId != null) "Approval window '$targetWindowId' closed" else "Approval window closed",
                        ),
                    )
                    releaseClaim(request.id)
                    show(null)
                }
            } else {
                releaseClaim(request.id)
            }
        }
    }
}
