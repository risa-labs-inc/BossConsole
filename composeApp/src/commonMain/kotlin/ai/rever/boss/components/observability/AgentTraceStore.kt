package ai.rever.boss.components.observability

import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.utils.logging.LogSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.*

object AgentTraceStore {
    private const val MAX_EVENTS = 500
    private const val MAX_PAYLOAD_LENGTH = 5000

    private val _events = MutableStateFlow<List<McpTraceEvent>>(emptyList())
    val events: StateFlow<List<McpTraceEvent>> = _events.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    fun clear() {
        _events.value = emptyList()
    }

    fun startTrace(toolName: String, rawArgs: String): String {
        val id = java.util.UUID.randomUUID().toString()
        val startedAtMs = Clock.System.now().toEpochMilliseconds()
        
        val sanitizedArgs = sanitizePayload(rawArgs)

        val event = McpTraceEvent(
            id = id,
            toolName = toolName,
            argumentsJson = sanitizedArgs,
            startedAtMs = startedAtMs
        )
        
        addOrUpdate(event)
        return id
    }

    fun completeTrace(id: String, result: McpToolResult) {
        val completedAtMs = Clock.System.now().toEpochMilliseconds()
        
        val rawResult = if (result.isError) {
            result.text ?: "Unknown error"
        } else {
            result.text ?: ""
        }
        val resultPayload = sanitizePayload(rawResult)
        
        updateEvent(id) {
            it.copy(
                completedAtMs = completedAtMs,
                status = if (result.isError) TraceStatus.FAILURE else TraceStatus.SUCCESS,
                resultJson = if (!result.isError) resultPayload else null,
                errorMessage = if (result.isError) resultPayload else null
            )
        }
    }

    fun failTrace(id: String, error: Throwable, isTimeout: Boolean = false, isCancelled: Boolean = false) {
        val completedAtMs = Clock.System.now().toEpochMilliseconds()
        val errorMessage = error.message ?: error::class.simpleName ?: "Unknown error"
        val sanitizedError = sanitizePayload(errorMessage)
        
        updateEvent(id) {
            it.copy(
                completedAtMs = completedAtMs,
                status = when {
                    isTimeout -> TraceStatus.TIMEOUT
                    isCancelled -> TraceStatus.CANCELLED
                    else -> TraceStatus.FAILURE
                },
                errorMessage = sanitizedError
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

    private fun updateEvent(id: String, transform: (McpTraceEvent) -> McpTraceEvent) {
        _events.update { current ->
            current.map { if (it.id == id) transform(it) else it }
        }
    }

    private fun sanitizePayload(raw: String): String {
        if (raw.isBlank()) return raw
        
        val needsTruncation = raw.length > MAX_PAYLOAD_LENGTH
        val parseableRaw = if (needsTruncation) raw.take(MAX_PAYLOAD_LENGTH) else raw
        
        val looksLikeJson = parseableRaw.trimStart().let { it.startsWith("{") || it.startsWith("[") }
        
        if (looksLikeJson) {
            try {
                if (needsTruncation) {
                    throw Exception("Truncated payload cannot be safely parsed as JSON")
                }
                val element = json.parseToJsonElement(raw)
                val sanitized = sanitizeJsonElement(element)
                return json.encodeToString(sanitized)
            } catch (e: Exception) {
                // Fall through to raw text sanitization
            }
        }
        
        // If parsing fails or it's not JSON, sanitize as a generic string
        val safeRaw = LogSanitizer.sanitizeLogMessage(parseableRaw)
        return if (needsTruncation) "$safeRaw... [TRUNCATED]" else safeRaw
    }
    
    private fun sanitizeJsonElement(element: JsonElement): JsonElement {
        return when (element) {
            is JsonObject -> {
                // Convert to map of String -> Any? to feed LogSanitizer
                val tempMap = element.mapValues { (_, v) ->
                    when (v) {
                        is JsonPrimitive -> if (v.isString) v.content else v.contentOrNull
                        else -> "..."
                    }
                }
                val sanitizedMap = LogSanitizer.sanitizeMap(tempMap)
                
                // Reconstruct JsonObject
                val sanitizedContent = element.mapValues { (k, v) ->
                    val sanitizedValue = sanitizedMap[k]
                    if (sanitizedValue == "[REDACTED]") {
                        JsonPrimitive("[REDACTED]")
                    } else if (v is JsonObject || v is JsonArray) {
                        sanitizeJsonElement(v) // Recurse
                    } else {
                        v
                    }
                }
                JsonObject(sanitizedContent)
            }
            is JsonArray -> {
                JsonArray(element.map { sanitizeJsonElement(it) })
            }
            else -> element
        }
    }
}
