package ai.rever.boss.mcp.telemetry

/** One span, reduced to the fields an agent can act on. */
internal data class BufferedSpan(
    val traceId: String,
    val spanId: String,
    val parentSpanId: String?,
    val name: String,
    val serviceName: String?,
    val startUnixNano: Long,
    val durationMs: Long,
    /** `UNSET`, `OK` or `ERROR`, from the OTLP status code. */
    val status: String,
    val errorMessage: String?,
    val attributes: Map<String, String>,
) {
    /**
     * Rough retained size, used for the buffer's byte ceiling.
     *
     * An estimate on purpose. The exact figure would need an object layout walk, and the ceiling
     * exists to stop unbounded growth rather than to account for memory precisely. Two chars per
     * character covers the JVM's UTF-16 representation, and the constant absorbs object headers
     * and map overhead.
     */
    val estimatedBytes: Int
        get() {
            val text =
                traceId.length + spanId.length + (parentSpanId?.length ?: 0) + name.length +
                    (serviceName?.length ?: 0) + status.length + (errorMessage?.length ?: 0) +
                    attributes.entries.sumOf { it.key.length + it.value.length }
            return text * BYTES_PER_CHAR + FIXED_OVERHEAD_BYTES
        }

    private companion object {
        const val BYTES_PER_CHAR = 2
        const val FIXED_OVERHEAD_BYTES = 256
    }
}

/**
 * A bounded FIFO buffer of received spans.
 *
 * Bounded on **two** axes, and both are load bearing. A count alone does not bound memory,
 * because one span carrying a large attribute can be arbitrarily big; a byte ceiling alone
 * degrades badly when spans are tiny, since millions of small entries still cost per object
 * overhead the estimate understates. Whichever limit is reached first evicts the oldest.
 *
 * Synchronized rather than lock free: writes come from the receiver's handler threads and reads
 * from an MCP handler coroutine, the critical sections are a few pointer moves, and a correct
 * simple thing beats a clever one for a buffer nothing hot depends on.
 */
internal class SpanBuffer(
    private val maxSpans: Int = DEFAULT_MAX_SPANS,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val spans = ArrayDeque<BufferedSpan>()

    private var retainedBytes: Long = 0

    /** Total spans accepted since start, including those since evicted. */
    private var accepted: Long = 0

    @Synchronized
    fun add(span: BufferedSpan) {
        spans.addLast(span)
        retainedBytes += span.estimatedBytes
        accepted++
        evictWhileOverCapacity()
    }

    @Synchronized
    fun addAll(incoming: List<BufferedSpan>) {
        incoming.forEach { add(it) }
    }

    /**
     * Matching spans, newest first.
     *
     * Newest first because a stale trace is rarely the one being investigated, and [limit] then
     * truncates the least interesting end rather than the most recent.
     */
    @Synchronized
    fun query(
        serviceName: String?,
        minDurationMs: Long,
        errorOnly: Boolean,
        limit: Int,
    ): List<BufferedSpan> =
        spans
            .asReversed()
            .asSequence()
            .filter { serviceName == null || it.serviceName == serviceName }
            .filter { it.durationMs >= minDurationMs }
            .filter { !errorOnly || it.status == "ERROR" }
            .take(limit.coerceIn(1, MAX_QUERY_LIMIT))
            .toList()

    @Synchronized
    fun stats(): BufferStats =
        BufferStats(
            retained = spans.size,
            retainedBytes = retainedBytes,
            acceptedTotal = accepted,
            maxSpans = maxSpans,
            maxBytes = maxBytes,
        )

    @Synchronized
    fun clear() {
        spans.clear()
        retainedBytes = 0
    }

    /**
     * The two ceilings need different handling, which is why this is not one loop.
     *
     * A count is always satisfiable by evicting. A byte ceiling is not: one span carrying an
     * attribute larger than the whole ceiling would evict every other span and still be over,
     * leaving the buffer empty and the next span repeating it. So the byte pass keeps a floor of
     * one entry, and holding a single oversized span is accepted as the lesser failure.
     */
    private fun evictWhileOverCapacity() {
        while (spans.size > maxSpans) {
            retainedBytes -= spans.removeFirst().estimatedBytes
        }
        while (spans.size > 1 && retainedBytes > maxBytes) {
            retainedBytes -= spans.removeFirst().estimatedBytes
        }
    }

    internal companion object {
        const val DEFAULT_MAX_SPANS: Int = 10_000
        const val DEFAULT_MAX_BYTES: Long = 64L * 1024 * 1024
        const val MAX_QUERY_LIMIT: Int = 50
    }
}

internal data class BufferStats(
    val retained: Int,
    val retainedBytes: Long,
    val acceptedTotal: Long,
    val maxSpans: Int,
    val maxBytes: Long,
)
