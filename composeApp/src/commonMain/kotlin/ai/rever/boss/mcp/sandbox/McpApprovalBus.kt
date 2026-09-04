package ai.rever.boss.mcp.sandbox

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Event bus and coordinator connecting suspendable MCP tool invocations to the Compose UI.
 */
interface McpApprovalBus : McpApprovalHandler {
    val approvalRequests: Flow<McpApprovalRequest>

    suspend fun submitRequest(request: McpApprovalRequest): Boolean
}

/**
 * Default implementation of [McpApprovalBus].
 *
 * Queues approval requests via a buffered [Channel] so concurrent requests are preserved in order.
 * Fails closed (returns `false`) on timeout, cancellation, or buffer overflow.
 */
class DefaultMcpApprovalBus(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    capacity: Int = 16,
) : McpApprovalBus {
    private val logger = BossLogger.forComponent("McpApprovalBus")
    private val channel = Channel<McpApprovalRequest>(capacity = capacity)

    override val approvalRequests: Flow<McpApprovalRequest> = channel.receiveAsFlow()

    override suspend fun requestApproval(
        toolName: String,
        args: McpToolArgs,
        assessment: McpRiskAssessment,
    ): Boolean {
        val request =
            McpApprovalRequest(
                toolName = toolName,
                riskLevel = assessment.level,
                reason = assessment.reason,
            )
        return submitRequest(request)
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    override suspend fun submitRequest(request: McpApprovalRequest): Boolean {
        val sent = channel.trySend(request).isSuccess
        if (!sent) {
            logger.warn(
                LogCategory.SYSTEM,
                "Approval request queue full; failing closed",
                mapOf("tool" to request.toolName),
            )
            request.answer.complete(false)
            return false
        }

        try {
            val result =
                withTimeoutOrNull(timeoutMs) {
                    request.answer.await()
                }

            if (result == null) {
                logger.warn(
                    LogCategory.SYSTEM,
                    "Approval request timed out; failing closed",
                    mapOf("tool" to request.toolName),
                )
                request.answer.complete(false)
                return false
            }

            return result
        } catch (e: Throwable) {
            // Fail closed on caller cancellation or unhandled exception so UI unmounts dialog
            request.answer.complete(false)
            throw e
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
    }
}

/** Process-wide singleton instance for production host wiring. */
object McpApprovalEventBus : McpApprovalBus by DefaultMcpApprovalBus()
