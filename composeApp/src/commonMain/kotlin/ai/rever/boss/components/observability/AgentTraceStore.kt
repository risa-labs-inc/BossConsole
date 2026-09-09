package ai.rever.boss.components.observability

import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.utils.logging.LogSanitizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object AgentTraceStore {
    private const val MAX_EVENTS = 500
    private const val MAX_PAYLOAD_LENGTH = 5000

    private val _events = MutableStateFlow<List<McpTraceEvent>>(emptyList())
    val events: StateFlow<List<McpTraceEvent>> = _events.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    fun clear() {
        _events.value = emptyList()
    }

    fun startTrace(
        toolName: String,
        rawArgs: String,
    ): String {
        val id =
            java.util.UUID
                .randomUUID()
                .toString()
        val startedAtMs = Clock.System.now().toEpochMilliseconds()

        val sanitizedArgs = sanitizePayload(rawArgs)

        val event =
            McpTraceEvent(
                id = id,
                toolName = toolName,
                argumentsJson = sanitizedArgs,
                startedAtMs = startedAtMs,
            )

        addOrUpdate(event)
        return id
    }

    fun completeTrace(
        id: String,
        result: McpToolResult,
    ) {
        val completedAtMs = Clock.System.now().toEpochMilliseconds()

        val rawResult =
            if (result.isError) {
                result.text ?: "Unknown error"
            } else {
                result.text ?: ""
            }
        val resultPayload = sanitizePayload(rawResult)

        updateEvent(id) {
            it.copy(
                completedAtMs = completedAtMs,
                durationMs = it.startMark.elapsedNow().inWholeMilliseconds,
                status = if (result.isError) TraceStatus.FAILURE else TraceStatus.SUCCESS,
                resultJson = if (!result.isError) resultPayload else null,
                errorMessage = if (result.isError) resultPayload else null,
            )
        }
    }

    fun failTrace(
        id: String,
        error: Throwable,
        isTimeout: Boolean = false,
        isCancelled: Boolean = false,
    ) {
        val completedAtMs = Clock.System.now().toEpochMilliseconds()
        val errorMessage = error.message ?: error::class.simpleName ?: "Unknown error"
        val sanitizedError = sanitizePayload(errorMessage)

        updateEvent(id) {
            it.copy(
                completedAtMs = completedAtMs,
                durationMs = it.startMark.elapsedNow().inWholeMilliseconds,
                status =
                    when {
                        isTimeout -> TraceStatus.TIMEOUT
                        isCancelled -> TraceStatus.CANCELLED
                        else -> TraceStatus.FAILURE
                    },
                errorMessage = sanitizedError,
            )
        }
    }

    private fun addOrUpdate(event: McpTraceEvent) {
        _events.update { current ->
            val existingIndex = current.indexOfFirst { it.id == event.id }
            val next = current.toMutableList()
            if (existingIndex >= 0) {
                next[existingIndex] = event
            } else {
                next.add(0, event)
            }
            next.take(MAX_EVENTS)
        }
    }

    private fun updateEvent(
        id: String,
        transform: (McpTraceEvent) -> McpTraceEvent,
    ) {
        _events.update { current ->
            current.map { if (it.id == id) transform(it) else it }
        }
    }

    // Do not feed incomplete JSON to the free-text sanitizer: quoted keys and
    // escaped values do not follow its name=value grammar.
    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount")
    private fun sanitizePayload(raw: String): String {
        if (raw.length > MAX_PAYLOAD_LENGTH) return "[Payload omitted: exceeds 5000 characters]"
        if (raw.isBlank()) return raw
        val trimmed = raw.trimStart()
        val looksLikeJson = trimmed.first() in "{[\""
        val sanitized =
            if (looksLikeJson) {
                try {
                    json.encodeToString(sanitizeJsonElement(json.parseToJsonElement(raw)))
                } catch (ignored: Exception) {
                    "[Payload omitted: invalid or deeply nested JSON]"
                }
            } else {
                LogSanitizer.sanitizeLogMessage(raw)
            }
        // Redaction can expand short values; the stored/displayed value is bounded too.
        return if (sanitized.length > MAX_PAYLOAD_LENGTH) {
            "[Payload omitted: sanitized output exceeds 5000 characters]"
        } else {
            sanitized
        }
    }

    private fun sanitizeJsonElement(
        element: JsonElement,
        depth: Int = 0,
    ): JsonElement {
        require(depth <= 32) { "JSON nesting exceeds trace limit" }
        return when (element) {
            is JsonObject -> {
                val values =
                    element.mapValues { (_, value) ->
                        if (value is JsonPrimitive) {
                            if (value.isString) value.content else null
                        } else {
                            "..."
                        }
                    }
                val sanitized = LogSanitizer.sanitizeMap(values)
                JsonObject(
                    element.mapValues { (key, value) ->
                        val safeValue = sanitized[key]
                        if (safeValue != values[key]) {
                            JsonPrimitive(safeValue?.toString())
                        } else {
                            sanitizeJsonElement(value, depth + 1)
                        }
                    },
                )
            }

            is JsonArray -> {
                JsonArray(element.map { sanitizeJsonElement(it, depth + 1) })
            }

            is JsonPrimitive -> {
                if (element.isString) JsonPrimitive(LogSanitizer.sanitizeLogMessage(element.content)) else element
            }
        }
    }
}
