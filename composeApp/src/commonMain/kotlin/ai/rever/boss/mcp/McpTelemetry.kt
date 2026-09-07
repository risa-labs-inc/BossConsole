package ai.rever.boss.mcp

import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicLong

/**
 * Status of an MCP tool invocation through the BOSS harness.
 */
@Serializable
enum class McpCallStatus {
    /** Currently executing. */
    RUNNING,

    /** Suspended awaiting operator approval (Human-in-the-Loop policy). */
    AWAITING_APPROVAL,

    /** Operator approved the call and it resumed execution. */
    APPROVED,

    /** Operator denied the call. */
    DENIED,

    /** Completed successfully without error flag. */
    SUCCESS,

    /** Tool handler returned isError = true or threw an exception. */
    ERROR,

    /** Tool execution exceeded invoke timeout. */
    TIMEOUT,

    /** Tool withheld by operator kill-switch or unpermitted. */
    BLOCKED,

    /** Caller coroutine was cancelled. */
    CANCELLED,
}

/**
 * An immutable audit record of an MCP tool call executed by an AI agent.
 */
@Serializable
data class McpCallRecord(
    val callId: String,
    val toolName: String,
    val providerId: String? = null,
    val arguments: String,
    val startTimeEpochMs: Long,
    val durationMs: Long = 0L,
    val status: McpCallStatus,
    val resultPayload: String? = null,
    val errorMessage: String? = null,
    val requiresApproval: Boolean = false,
)

/**
 * Aggregated telemetry metrics for all agent MCP invocations in the current session.
 */
@Serializable
data class McpTelemetryStats(
    val totalCalls: Long = 0L,
    val successCount: Long = 0L,
    val errorCount: Long = 0L,
    val activeInFlight: Int = 0,
    val pendingApprovalsCount: Int = 0,
    val avgDurationMs: Double = 0.0,
)

/**
 * Operator's decision on an approval-gated tool call.
 */
sealed interface ApprovalDecision {
    data class Approved(val modifiedArgs: String? = null) : ApprovalDecision
    data class Denied(val reason: String = "Execution denied by operator") : ApprovalDecision
}

/**
 * A pending human approval request holding a deferred coroutine handle.
 */
data class ApprovalRequest(
    val callId: String,
    val toolName: String,
    val arguments: String,
    val timestampMs: Long,
    val deferredResponse: CompletableDeferred<ApprovalDecision>,
)

/**
 * Central telemetry recorder and Human-in-the-Loop (HITL) gatekeeper for MCP tool calls.
 *
 * All operations are thread-safe and non-blocking: records are stored in a bounded ring-buffer
 * to guarantee zero memory leaks and predictable JVM performance during long-running agent tasks.
 */
object McpTelemetryRecorder {
    private val logger = BossLogger.forComponent("McpTelemetryRecorder")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    /** Maximum records retained in memory. Oldest evicted first. */
    const val MAX_RECORDS = 200

    /** Default approval timeout: 60 seconds. Fails closed on timeout. */
    const val DEFAULT_APPROVAL_TIMEOUT_MS = 60_000L

    /** Maximum payload size stored per call (8 KB) to prevent UI lag / memory bloat. */
    private const val MAX_PAYLOAD_CHARS = 8192

    private val idCounter = AtomicLong(1000L)
    private val lock = Any()

    private val _records = MutableStateFlow<List<McpCallRecord>>(emptyList())
    val records: StateFlow<List<McpCallRecord>> = _records.asStateFlow()

    private val _stats = MutableStateFlow(McpTelemetryStats())
    val stats: StateFlow<McpTelemetryStats> = _stats.asStateFlow()

    private val _pendingApprovals = MutableStateFlow<List<ApprovalRequest>>(emptyList())
    val pendingApprovals: StateFlow<List<ApprovalRequest>> = _pendingApprovals.asStateFlow()

    /** Tools configured by operator to always require interactive confirmation. */
    private val _toolsRequiringApproval = MutableStateFlow<Set<String>>(emptySet())
    val toolsRequiringApproval: StateFlow<Set<String>> = _toolsRequiringApproval.asStateFlow()

    /** Global Safe Mode: when true, ALL tools require human approval. */
    private val _globalSafeMode = MutableStateFlow(false)
    val globalSafeMode: StateFlow<Boolean> = _globalSafeMode.asStateFlow()

    fun nextCallId(): String = "call-${idCounter.incrementAndGet()}"

    fun isApprovalRequired(toolName: String): Boolean {
        if (_globalSafeMode.value) return true
        return toolName in _toolsRequiringApproval.value
    }

    fun setToolRequiresApproval(toolName: String, required: Boolean) {
        _toolsRequiringApproval.update { current ->
            if (required) current + toolName else current - toolName
        }
        logger.info(
            LogCategory.SYSTEM,
            "MCP approval policy updated",
            mapOf("tool" to toolName, "requiresApproval" to required),
        )
    }

    fun setGlobalSafeMode(enabled: Boolean) {
        _globalSafeMode.value = enabled
        logger.info(
            LogCategory.SYSTEM,
            "MCP Global Safe Mode toggled",
            mapOf("safeMode" to enabled),
        )
    }

    /**
     * Record the initiation of a call (status = RUNNING).
     */
    fun recordStart(
        callId: String,
        toolName: String,
        arguments: String,
        providerId: String? = null,
        requiresApproval: Boolean = false,
    ): McpCallRecord {
        val sanitizedArgs = maskSecrets(arguments)
        val record = McpCallRecord(
            callId = callId,
            toolName = toolName,
            providerId = providerId,
            arguments = sanitizedArgs,
            startTimeEpochMs = System.currentTimeMillis(),
            status = McpCallStatus.RUNNING,
            requiresApproval = requiresApproval,
        )
        synchronized(lock) {
            val updated = (listOf(record) + _records.value).take(MAX_RECORDS)
            _records.value = updated
            recomputeStats()
        }
        return record
    }

    /**
     * Record when a call is withheld by operator kill-switch or permission denial.
     */
    fun recordBlocked(
        callId: String,
        toolName: String,
        arguments: String,
        reason: String,
    ) {
        val record = McpCallRecord(
            callId = callId,
            toolName = toolName,
            arguments = maskSecrets(arguments),
            startTimeEpochMs = System.currentTimeMillis(),
            durationMs = 0L,
            status = McpCallStatus.BLOCKED,
            errorMessage = reason,
        )
        synchronized(lock) {
            val updated = (listOf(record) + _records.value).take(MAX_RECORDS)
            _records.value = updated
            recomputeStats()
        }
    }

    /**
     * Request human approval for a sensitive tool call.
     * Suspends asynchronously until operator confirms or timeout elapses.
     */
    suspend fun requestApproval(
        callId: String,
        toolName: String,
        arguments: String,
        timeoutMs: Long = DEFAULT_APPROVAL_TIMEOUT_MS,
    ): ApprovalDecision {
        val deferred = CompletableDeferred<ApprovalDecision>()
        val request = ApprovalRequest(
            callId = callId,
            toolName = toolName,
            arguments = maskSecrets(arguments),
            timestampMs = System.currentTimeMillis(),
            deferredResponse = deferred,
        )

        // Mark record as AWAITING_APPROVAL in list
        val awaitingRecord = McpCallRecord(
            callId = callId,
            toolName = toolName,
            arguments = request.arguments,
            startTimeEpochMs = request.timestampMs,
            status = McpCallStatus.AWAITING_APPROVAL,
            requiresApproval = true,
        )

        synchronized(lock) {
            _pendingApprovals.update { it + request }
            _records.value = (listOf(awaitingRecord) + _records.value).take(MAX_RECORDS)
            recomputeStats()
        }

        val decision = withTimeoutOrNull(timeoutMs) {
            deferred.await()
        } ?: ApprovalDecision.Denied("Approval timed out after ${timeoutMs / 1000}s (Fail-Closed)")

        // Remove from pending
        _pendingApprovals.update { list -> list.filterNot { it.callId == callId } }

        // Update record status with approval outcome
        synchronized(lock) {
            _records.update { list ->
                list.map { r ->
                    if (r.callId == callId) {
                        when (decision) {
                            is ApprovalDecision.Approved -> r.copy(status = McpCallStatus.APPROVED)
                            is ApprovalDecision.Denied -> r.copy(
                                status = McpCallStatus.DENIED,
                                errorMessage = decision.reason,
                                durationMs = System.currentTimeMillis() - r.startTimeEpochMs,
                            )
                        }
                    } else r
                }
            }
            recomputeStats()
        }

        return decision
    }

    /**
     * Called by UI to resolve a pending approval.
     */
    fun resolveApproval(callId: String, decision: ApprovalDecision) {
        val pending = _pendingApprovals.value.firstOrNull { it.callId == callId }
        if (pending != null) {
            pending.deferredResponse.complete(decision)
        }
    }

    /**
     * Record completion of an MCP tool invocation.
     */
    fun recordComplete(
        callId: String,
        result: McpToolResult,
        durationMs: Long,
    ) {
        val status = if (result.isError) McpCallStatus.ERROR else McpCallStatus.SUCCESS
        val truncatedPayload = truncate(result.text, MAX_PAYLOAD_CHARS)
        synchronized(lock) {
            _records.update { list ->
                list.map { r ->
                    if (r.callId == callId) {
                        r.copy(
                            status = status,
                            durationMs = durationMs,
                            resultPayload = truncatedPayload,
                            errorMessage = if (result.isError) truncatedPayload else null,
                        )
                    } else r
                }
            }
            recomputeStats()
        }
    }

    /**
     * Record a timeout cancellation.
     */
    fun recordTimeout(
        callId: String,
        durationMs: Long,
        timeoutMessage: String,
    ) {
        synchronized(lock) {
            _records.update { list ->
                list.map { r ->
                    if (r.callId == callId) {
                        r.copy(
                            status = McpCallStatus.TIMEOUT,
                            durationMs = durationMs,
                            errorMessage = timeoutMessage,
                        )
                    } else r
                }
            }
            recomputeStats()
        }
    }

    /**
     * Record caller cancellation (structured concurrency).
     */
    fun recordCancelled(
        callId: String,
        durationMs: Long,
    ) {
        synchronized(lock) {
            _records.update { list ->
                list.map { r ->
                    if (r.callId == callId) {
                        r.copy(
                            status = McpCallStatus.CANCELLED,
                            durationMs = durationMs,
                            errorMessage = "Invocation cancelled by caller",
                        )
                    } else r
                }
            }
            recomputeStats()
        }
    }

    /**
     * Record an uncaught failure / exception.
     */
    fun recordFailure(
        callId: String,
        throwable: Throwable,
        durationMs: Long,
    ) {
        val err = throwable.message ?: throwable::class.simpleName ?: "Unknown error"
        synchronized(lock) {
            _records.update { list ->
                list.map { r ->
                    if (r.callId == callId) {
                        r.copy(
                            status = McpCallStatus.ERROR,
                            durationMs = durationMs,
                            errorMessage = err,
                        )
                    } else r
                }
            }
            recomputeStats()
        }
    }

    /**
     * Clear all recorded history (does not clear active pending approvals).
     */
    fun clear() {
        synchronized(lock) {
            _records.value = emptyList()
            recomputeStats()
        }
    }

    /**
     * Export all retained records as formatted JSON.
     */
    fun exportAsJson(): String {
        return synchronized(lock) {
            json.encodeToString(_records.value)
        }
    }

    private fun recomputeStats() {
        val snapshot = _records.value
        val total = snapshot.size.toLong()
        val success = snapshot.count { it.status == McpCallStatus.SUCCESS }.toLong()
        val errors = snapshot.count { it.status == McpCallStatus.ERROR || it.status == McpCallStatus.TIMEOUT }.toLong()
        val inFlight = snapshot.count { it.status == McpCallStatus.RUNNING }
        val pending = _pendingApprovals.value.size

        val completed = snapshot.filter { it.durationMs > 0L }
        val avg = if (completed.isNotEmpty()) {
            completed.map { it.durationMs }.average()
        } else {
            0.0
        }

        _stats.value = McpTelemetryStats(
            totalCalls = total,
            successCount = success,
            errorCount = errors,
            activeInFlight = inFlight,
            pendingApprovalsCount = pending,
            avgDurationMs = avg,
        )
    }

    private fun truncate(text: String?, max: Int): String? {
        if (text == null) return null
        return if (text.length <= max) text else text.take(max) + "\n... [truncated ${text.length - max} chars]"
    }

    /**
     * Redact known secret and credential keys from JSON arguments for safe operator display.
     */
    fun maskSecrets(rawJson: String): String {
        if (rawJson.isBlank()) return rawJson
        return try {
            val element = json.parseToJsonElement(rawJson)
            val masked = maskElement(element)
            json.encodeToString(masked)
        } catch (_: Throwable) {
            rawJson
        }
    }

    private val SENSITIVE_KEY_PATTERNS = setOf(
        "password", "secret", "token", "apikey", "api_key", "credential", "auth", "privatekey", "private_key"
    )

    private fun maskElement(element: JsonElement): JsonElement {
        return when (element) {
            is JsonObject -> {
                val newMap = mutableMapOf<String, JsonElement>()
                for ((key, value) in element) {
                    val isSensitive = SENSITIVE_KEY_PATTERNS.any { key.lowercase().contains(it) }
                    newMap[key] = if (isSensitive && value is JsonPrimitive) {
                        JsonPrimitive("***REDACTED***")
                    } else {
                        maskElement(value)
                    }
                }
                JsonObject(newMap)
            }
            is JsonArray -> JsonArray(element.map { maskElement(it) })
            else -> element
        }
    }
}
