package ai.rever.boss.mcp

import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CancellationException
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

    ;

    val isUnsuccessful: Boolean
        get() =
            when (this) {
                ERROR, TIMEOUT, DENIED, BLOCKED, CANCELLED -> true
                else -> false
            }
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
    data class Approved(
        val modifiedArgs: String? = null,
    ) : ApprovalDecision

    data class Denied(
        val reason: String = "Execution denied by operator",
        val timedOut: Boolean = false,
    ) : ApprovalDecision
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
@Suppress("TooManyFunctions") // Cohesive lifecycle API for one recorder and its approval state.
object McpTelemetryRecorder {
    private val logger = BossLogger.forComponent("McpTelemetryRecorder")
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = true
        }

    /** Maximum records retained in memory. Oldest evicted first. */
    const val MAX_PENDING_APPROVALS = 16
    private const val MAX_ARGUMENT_DEPTH = 64

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

    fun setToolRequiresApproval(
        toolName: String,
        required: Boolean,
    ) {
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
        val record =
            McpCallRecord(
                callId = callId,
                toolName = toolName,
                providerId = providerId,
                arguments = sanitizedArgs,
                startTimeEpochMs = System.currentTimeMillis(),
                status = McpCallStatus.RUNNING,
                requiresApproval = requiresApproval,
            )
        synchronized(lock) {
            val updated = (listOf(record) + _records.value.filterNot { it.callId == callId }).take(MAX_RECORDS)
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
        val record =
            McpCallRecord(
                callId = callId,
                toolName = toolName,
                arguments = maskSecrets(arguments),
                startTimeEpochMs = System.currentTimeMillis(),
                durationMs = 0L,
                status = McpCallStatus.BLOCKED,
                errorMessage = reason,
            )
        synchronized(lock) {
            val updated = (listOf(record) + _records.value.filterNot { it.callId == callId }).take(MAX_RECORDS)
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
        val request =
            ApprovalRequest(
                callId = callId,
                toolName = toolName,
                arguments = maskSecrets(arguments),
                timestampMs = System.currentTimeMillis(),
                deferredResponse = deferred,
            )

        // Mark record as AWAITING_APPROVAL in list
        val awaitingRecord =
            McpCallRecord(
                callId = callId,
                toolName = toolName,
                arguments = request.arguments,
                startTimeEpochMs = request.timestampMs,
                status = McpCallStatus.AWAITING_APPROVAL,
                requiresApproval = true,
            )

        synchronized(lock) {
            if (_pendingApprovals.value.size >= MAX_PENDING_APPROVALS) {
                val reason = "Too many pending approval requests"
                recordBlocked(callId, toolName, arguments, reason)
                return ApprovalDecision.Denied(reason)
            }
            _pendingApprovals.update { it + request }
            val remainingRecords = _records.value.filterNot { it.callId == callId }
            _records.value = (listOf(awaitingRecord) + remainingRecords).take(MAX_RECORDS)
            recomputeStats()
        }

        val decision =
            try {
                withTimeoutOrNull(timeoutMs) { deferred.await() }
                    ?: ApprovalDecision.Denied("Approval timed out after ${timeoutMs / 1000}s", timedOut = true)
            } catch (cancelled: CancellationException) {
                recordCancelled(callId, System.currentTimeMillis() - request.timestampMs)
                throw cancelled
            } finally {
                deferred.complete(ApprovalDecision.Denied("Approval request expired"))
                synchronized(lock) {
                    _pendingApprovals.update { list -> list.filterNot { it.callId == callId } }
                    recomputeStats()
                }
            }

        recordApprovalDecision(callId, decision)

        return decision
    }

    private fun recordApprovalDecision(
        callId: String,
        decision: ApprovalDecision,
    ) {
        // Update record status with approval outcome
        synchronized(lock) {
            _records.update { list ->
                list.map { r ->
                    if (r.callId == callId) {
                        when (decision) {
                            is ApprovalDecision.Approved -> {
                                r.copy(status = McpCallStatus.APPROVED)
                            }

                            is ApprovalDecision.Denied -> {
                                r.copy(
                                    status = if (decision.timedOut) McpCallStatus.TIMEOUT else McpCallStatus.DENIED,
                                    errorMessage =
                                        if (decision.timedOut) "Approval timed out" else "Approval denied by operator",
                                    durationMs = System.currentTimeMillis() - r.startTimeEpochMs,
                                )
                            }
                        }
                    } else {
                        r
                    }
                }
            }
            recomputeStats()
        }
    }

    /**
     * Called by UI to resolve a pending approval.
     */
    fun resolveApproval(
        callId: String,
        decision: ApprovalDecision,
    ) {
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
        val truncatedPayload = "[Tool output omitted from shared history]"
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
                    } else {
                        r
                    }
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
                    } else {
                        r
                    }
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
                    } else {
                        r
                    }
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
        val err = "Tool handler failed (${throwable::class.simpleName ?: "unknown error"})"
        synchronized(lock) {
            _records.update { list ->
                list.map { r ->
                    if (r.callId == callId) {
                        r.copy(
                            status = McpCallStatus.ERROR,
                            durationMs = durationMs,
                            errorMessage = err,
                        )
                    } else {
                        r
                    }
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
            _records.value =
                _records.value.filter {
                    it.status == McpCallStatus.RUNNING || it.status == McpCallStatus.AWAITING_APPROVAL ||
                        it.status == McpCallStatus.APPROVED
                }
            recomputeStats()
        }
    }

    /**
     * Export all retained records as formatted JSON.
     */
    fun exportAsJson(): String =
        synchronized(lock) {
            json.encodeToString(_records.value)
        }

    private fun recomputeStats() {
        val snapshot = _records.value
        val total = snapshot.size.toLong()
        val success = snapshot.count { it.status == McpCallStatus.SUCCESS }.toLong()
        val errors = snapshot.count { it.status.isUnsuccessful }.toLong()
        val inFlight = snapshot.count { it.status == McpCallStatus.RUNNING }
        val pending = _pendingApprovals.value.size

        val completed = snapshot.filter { it.durationMs > 0L }
        val avg =
            if (completed.isNotEmpty()) {
                completed.map { it.durationMs }.average()
            } else {
                0.0
            }

        _stats.value =
            McpTelemetryStats(
                totalCalls = total,
                successCount = success,
                errorCount = errors,
                activeInFlight = inFlight,
                pendingApprovalsCount = pending,
                avgDurationMs = avg,
            )
    }

    /**
     * Redact known secret and credential keys from JSON arguments for safe operator display.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount") // Invalid or oversized JSON is omitted before publication.
    fun maskSecrets(rawJson: String): String {
        if (rawJson.length > MAX_PAYLOAD_CHARS) return "[Arguments omitted: too large]"
        if (hasExcessiveNesting(rawJson)) return "[Arguments omitted: too deeply nested]"
        if (rawJson.isBlank()) return rawJson
        return try {
            val element = json.parseToJsonElement(rawJson)
            val masked = maskElement(element)
            json.encodeToString(masked)
        } catch (_: Exception) {
            "[Arguments omitted: invalid JSON]"
        }
    }

    private val SENSITIVE_KEY_PATTERNS =
        setOf(
            "password",
            "secret",
            "token",
            "apikey",
            "api_key",
            "credential",
            "auth",
            "privatekey",
            "private_key",
        )

    private fun maskElement(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> {
                val newMap = mutableMapOf<String, JsonElement>()
                for ((key, value) in element) {
                    val isSensitive = SENSITIVE_KEY_PATTERNS.any { key.lowercase().contains(it) }
                    newMap[key] =
                        if (isSensitive) {
                            JsonPrimitive("***REDACTED***")
                        } else {
                            maskElement(value)
                        }
                }
                JsonObject(newMap)
            }

            is JsonArray -> {
                JsonArray(element.map { maskElement(it) })
            }

            is JsonPrimitive -> {
                if (element.isString) JsonPrimitive(sanitizeArgumentText(element.content)) else element
            }

            else -> {
                element
            }
        }

    private val credentialShape =
        Regex(
            """(?i)(?:Bearer\s+[^\s"',;}]+|""" +
                """(?:gh[pousr]_|github_pat_|sk[-_]|pk[-_])[A-Za-z0-9_-]{8,}|""" +
                """eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*)""",
        )
    private val credentialAssignment =
        Regex(
            """(?i)(?:password|token|secret|api[_-]?key|authorization|credential)""" +
                """\s*[:=]\s*(?:"[^"]*"|'[^']*'|[^\s&,;}]+)""",
        )

    private fun sanitizeArgumentText(text: String): String =
        text.replace(credentialShape, "[REDACTED]").replace(credentialAssignment, "[REDACTED]")

    @Suppress("TooGenericExceptionCaught") // Reject invalid operator edits without dispatching a tool.
    fun canUseEditedArguments(arguments: String): Boolean =
        try {
            arguments.length <= MAX_PAYLOAD_CHARS && !hasExcessiveNesting(arguments) &&
                json.parseToJsonElement(arguments) is JsonObject &&
                !arguments.contains("REDACTED") && !arguments.contains("[Arguments omitted:")
        } catch (_: Exception) {
            false
        }

    /** Bound the parser itself, ignoring delimiters inside correctly escaped JSON strings. */
    private fun hasExcessiveNesting(raw: String): Boolean {
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in raw) {
            if (quoted) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> quoted = false
                }
                continue
            }
            when (char) {
                '"' -> quoted = true
                '{', '[' -> depth++
                '}', ']' -> depth--
            }
            if (depth > MAX_ARGUMENT_DEPTH) return true
        }
        return false
    }
}
