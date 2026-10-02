package ai.rever.boss.mcp.telemetry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Decoder for OTLP/HTTP in its **JSON** encoding (`POST /v1/traces`, `Content-Type:
 * application/json`).
 *
 * JSON rather than Protobuf on purpose. OTLP defines both as first class encodings, and every
 * mainstream SDK emits JSON when pointed at an `http/json` endpoint, so this is a real OTLP
 * receiver rather than a lookalike. Protobuf would have meant a code generator, a `protoc`
 * toolchain and a new dependency on the host classpath, for a wire format the sender can simply
 * be asked not to use.
 *
 * Every function here is pure, so the whole decode path is tested against fixture payloads with
 * no socket involved.
 *
 * ## Robustness stance
 *
 * A receiver accepts whatever the network hands it. Anything malformed is **skipped, never
 * guessed at and never thrown**: one bad span in a batch of five must not lose the other four,
 * and a sender with a slightly different shape must not take the endpoint down. The count of
 * what was dropped is returned, so a partial decode is visible rather than silent.
 */
internal object OtlpJson {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    /** Attribute key carrying the emitting service, by OpenTelemetry semantic convention. */
    private const val SERVICE_NAME_KEY = "service.name"

    private const val NANOS_PER_MILLI = 1_000_000L

    /** OTLP `Status.StatusCode`: 0 unset, 1 ok, 2 error. */
    private const val STATUS_ERROR = 2
    private const val STATUS_OK = 1

    private val VALUE_KEYS = listOf("stringValue", "intValue", "doubleValue", "boolValue", "bytesValue")

    data class Decoded(
        val spans: List<BufferedSpan>,
        /** Spans present in the payload that could not be read. */
        val skipped: Int,
    )

    /** Decodes an OTLP/JSON `ExportTraceServiceRequest`. */
    fun decodeTraces(body: String): Decoded {
        val root = parseObject(body) ?: return Decoded(emptyList(), 0)
        val spans = mutableListOf<BufferedSpan>()
        var skipped = 0
        root.arrayOrEmpty("resourceSpans").forEach { element ->
            val batch = decodeResourceSpans(element)
            spans += batch.spans
            skipped += batch.skipped
        }
        return Decoded(spans, skipped)
    }

    /**
     * A body that is not a JSON object at all.
     *
     * Swallowed deliberately. This decodes untrusted network input, the caller's response is the
     * same whatever the parse error was, and logging each one would hand any sender a log spam
     * channel. The count of dropped spans is the visible signal instead.
     */
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    private fun parseObject(body: String): JsonObject? =
        try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (t: Throwable) {
            null
        }

    /** One `ResourceSpans` entry: the resource names the service, the scopes carry the spans. */
    private fun decodeResourceSpans(element: JsonElement): Decoded {
        val resourceSpans = element as? JsonObject ?: return Decoded(emptyList(), 0)
        val serviceName = attributesOf(resourceSpans["resource"] as? JsonObject)[SERVICE_NAME_KEY]

        // scopeSpans is the current name; instrumentationLibrarySpans is the pre 1.0 name some
        // still shipping SDK versions emit. Accepting both costs one line.
        val scopes =
            resourceSpans.arrayOrEmpty("scopeSpans") +
                resourceSpans.arrayOrEmpty("instrumentationLibrarySpans")

        val spans = mutableListOf<BufferedSpan>()
        var skipped = 0
        scopes.flatMap { (it as? JsonObject)?.arrayOrEmpty("spans") ?: emptyList() }.forEach { spanElement ->
            val span = (spanElement as? JsonObject)?.let { toSpan(it, serviceName) }
            if (span == null) skipped++ else spans += span
        }
        return Decoded(spans, skipped)
    }

    /**
     * One span, or null when it cannot be read.
     *
     * The exception is swallowed for the same reason as [parseObject]: a malformed span is
     * counted, not logged and not thrown.
     */
    @Suppress("SwallowedException", "TooGenericExceptionCaught", "ReturnCount")
    private fun toSpan(
        span: JsonObject,
        serviceName: String?,
    ): BufferedSpan? =
        try {
            val traceId = span.stringOrNull("traceId")
            val spanId = span.stringOrNull("spanId")
            if (traceId == null || spanId == null) return null

            val start = span.unsignedLong("startTimeUnixNano")
            val end = span.unsignedLong("endTimeUnixNano")
            val status = span["status"] as? JsonObject
            val statusCode = status?.get("code")?.intOrNull() ?: 0

            BufferedSpan(
                traceId = traceId,
                spanId = spanId,
                parentSpanId = span.stringOrNull("parentSpanId")?.takeIf { it.isNotBlank() },
                name = span.stringOrNull("name") ?: "(unnamed)",
                serviceName = serviceName,
                startUnixNano = start,
                // Clamped at zero: a clock that went backwards between start and end would
                // otherwise produce a negative duration that every min_duration filter matches.
                durationMs = ((end - start) / NANOS_PER_MILLI).coerceAtLeast(0),
                status =
                    when (statusCode) {
                        STATUS_ERROR -> "ERROR"
                        STATUS_OK -> "OK"
                        else -> "UNSET"
                    },
                errorMessage =
                    status
                        ?.stringOrNull("message")
                        ?.takeIf { statusCode == STATUS_ERROR && it.isNotBlank() },
                attributes = attributesOf(span),
            )
        } catch (t: Throwable) {
            null
        }

    /**
     * OTLP `KeyValue` list flattened to a string map.
     *
     * Values are an `AnyValue` union (`stringValue`, `intValue`, `boolValue`, `doubleValue`,
     * `arrayValue`, ...). They are rendered to strings rather than modelled, because the consumer
     * is a language model reading a JSON payload and a typed union buys nothing there.
     */
    private fun attributesOf(owner: JsonObject?): Map<String, String> {
        if (owner == null) return emptyMap()
        val out = linkedMapOf<String, String>()
        owner.arrayOrEmpty("attributes").forEach { entry ->
            val kv = entry as? JsonObject ?: return@forEach
            val key = kv.stringOrNull("key") ?: return@forEach
            anyValueToString(kv["value"])?.let { out[key] = it }
        }
        return out
    }

    private fun anyValueToString(value: JsonElement?): String? {
        val obj = value as? JsonObject ?: return (value as? JsonPrimitive)?.content
        val scalar = VALUE_KEYS.firstNotNullOfOrNull { (obj[it] as? JsonPrimitive)?.content }
        val array =
            (obj["arrayValue"] as? JsonObject)
                ?.arrayOrEmpty("values")
                ?.mapNotNull { anyValueToString(it) }
                ?.joinToString(",")
        return scalar ?: array
    }

    private fun JsonObject.arrayOrEmpty(key: String): JsonArray = (this[key] as? JsonArray) ?: JsonArray(emptyList())

    private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun JsonElement.intOrNull(): Int? = (this as? JsonPrimitive)?.content?.toIntOrNull()

    /**
     * A uint64 nanosecond timestamp.
     *
     * JSON has no 64 bit integer, so the OTLP JSON mapping sends these as **strings**. Some
     * senders ignore that and emit a number anyway, so both are accepted; an unreadable value
     * becomes 0, which makes the span's duration 0 rather than dropping the span.
     */
    private fun JsonObject.unsignedLong(key: String): Long {
        // Block body, not an expression body: ktlintFormat joins up to 140 columns while detekt
        // rejects above 120, and the joined one-liner lands at 121.
        val raw = (this[key] as? JsonPrimitive)?.content
        return raw?.toLongOrNull() ?: 0L
    }
}
