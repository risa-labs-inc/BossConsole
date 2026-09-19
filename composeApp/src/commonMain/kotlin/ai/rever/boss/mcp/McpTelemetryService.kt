package ai.rever.boss.mcp

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.math.round

/**
 * Execution telemetry snapshot for a single MCP tool.
 *
 * @property tool The tool identifier.
 * @property totalInvocations Total number of calls recorded for this tool in the process lifetime.
 * @property totalErrors Total number of failed invocations recorded in the process lifetime.
 * @property errorPercentage Rolling window error rate as a percentage (0.0 to 100.0).
 * @property p50Ms Approximate 50th percentile (median) execution latency in milliseconds.
 * @property p99Ms Approximate 99th percentile execution latency in milliseconds.
 * @property windowSize Number of samples currently held in the sliding window ring buffer.
 */
@Serializable
data class ToolTelemetryStats(
    val tool: String,
    val totalInvocations: Long,
    val totalErrors: Long,
    val errorPercentage: Double,
    val p50Ms: Long,
    val p99Ms: Long,
    val windowSize: Int,
) {
    val toolName: String get() = tool
    val invocations: Long get() = totalInvocations
    val errors: Long get() = totalErrors
}

/**
 * Thread-safe rolling window circular buffer for recording tool execution latencies and errors.
 *
 * Maintains unboxed arrays to achieve O(1) appends without allocating objects on the hot path.
 * When full, the oldest duration and error state are overwritten.
 */
internal class RollingWindowBuffer(
    val capacity: Int = McpTelemetryService.DEFAULT_WINDOW_CAPACITY,
) {
    private val bufferLock = Any()
    private val durations = LongArray(capacity)
    private val errors = BooleanArray(capacity)
    private var head = 0
    private var count = 0
    private var windowErrors = 0
    private var totalInvocations = 0L
    private var totalErrors = 0L

    fun record(
        durationMs: Long,
        isError: Boolean,
    ) {
        synchronized(bufferLock) {
            totalInvocations++
            if (isError) {
                totalErrors++
            }

            if (count == capacity) {
                if (errors[head]) {
                    windowErrors--
                }
            } else {
                count++
            }

            durations[head] = durationMs
            errors[head] = isError
            if (isError) {
                windowErrors++
            }

            head = (head + 1) % capacity
        }
    }

    fun snapshot(toolName: String): ToolTelemetryStats {
        synchronized(bufferLock) {
            if (count <= 0) {
                return ToolTelemetryStats(
                    tool = toolName,
                    totalInvocations = totalInvocations,
                    totalErrors = totalErrors,
                    errorPercentage = 0.0,
                    p50Ms = 0L,
                    p99Ms = 0L,
                    windowSize = 0,
                )
            }

            // Slice-Aware Percentile Sorting:
            // Only copy and sort active window elements (0 until count). Never sort unwritten 0L slots.
            val active = LongArray(count)
            for (i in 0 until count) {
                active[i] = durations[i]
            }
            active.sort()

            val p50 = computePercentile(active, 50.0)
            val p99 = computePercentile(active, 99.0)
            val errorPct = (windowErrors.toDouble() / count) * 100.0
            val roundedErrorPct = roundToTwoDecimals(errorPct)

            return ToolTelemetryStats(
                tool = toolName,
                totalInvocations = totalInvocations,
                totalErrors = totalErrors,
                errorPercentage = roundedErrorPct,
                p50Ms = p50,
                p99Ms = p99,
                windowSize = count,
            )
        }
    }
}

internal fun computePercentile(
    sorted: LongArray,
    percentile: Double,
): Long {
    if (sorted.isEmpty()) return 0L
    val rank = kotlin.math.ceil((percentile / 100.0) * sorted.size).toInt()
    val index = (rank - 1).coerceIn(0, sorted.lastIndex)
    return sorted[index]
}

internal fun roundToTwoDecimals(value: Double): Double {
    if (value.isNaN() || value.isInfinite()) return 0.0
    return round(value * 100.0) / 100.0
}

/**
 * Headless, sliding-window telemetry aggregator over MCP tool invocations.
 *
 * Subscribes to [McpOperationLedger] events and maintains in-memory ring buffers per tool.
 * Exposes statistical calculations (call volume, p50/p99 latency, failure rates) both via
 * the registered `mcp__boss__tool_stats` tool and the CLI exporter `boss status --tools`.
 */
class McpTelemetryService(
    private val ledger: McpOperationLedger? = null,
    val defaultWindowCapacity: Int = DEFAULT_WINDOW_CAPACITY,
) : McpToolProvider {
    companion object {
        const val DEFAULT_WINDOW_CAPACITY = 500
        const val MAX_TRACKED_TOOLS = 256
        const val PROVIDER_ID = "boss-telemetry"
        const val TOOL_NAME = "tool_stats"
    }

    override val providerId: String = PROVIDER_ID

    private val windowsLock = Any()
    private val windows = mutableMapOf<String, RollingWindowBuffer>()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        ledger?.addListener { record ->
            recordInvocation(
                toolName = record.toolName,
                durationMs = record.durationMs,
                isError = record.isError,
            )
        }
    }

    /**
     * Record a tool execution directly.
     */
    fun recordInvocation(
        toolName: String,
        durationMs: Long,
        isError: Boolean,
    ) {
        val buffer = getOrCreateBuffer(toolName) ?: return
        buffer.record(durationMs, isError)
    }

    private fun getOrCreateBuffer(toolName: String): RollingWindowBuffer? =
        synchronized(windowsLock) {
            windows[toolName] ?: run {
                if (windows.size >= MAX_TRACKED_TOOLS) {
                    null
                } else {
                    val newBuffer = RollingWindowBuffer(defaultWindowCapacity)
                    windows[toolName] = newBuffer
                    newBuffer
                }
            }
        }

    /**
     * Get statistics for a specific tool. If the tool has never been called, returns safe zero-state stats.
     */
    fun getStats(toolName: String): ToolTelemetryStats {
        val buffer = synchronized(windowsLock) { windows[toolName] }
        return buffer?.snapshot(toolName) ?: ToolTelemetryStats(
            tool = toolName,
            totalInvocations = 0L,
            totalErrors = 0L,
            errorPercentage = 0.0,
            p50Ms = 0L,
            p99Ms = 0L,
            windowSize = 0,
        )
    }

    /**
     * Get statistics for all recorded tools, sorted alphabetically by tool name.
     */
    fun getAllStats(): List<ToolTelemetryStats> {
        val entries = synchronized(windowsLock) { windows.entries.toList() }
        return entries
            .map { (name, buffer) -> buffer.snapshot(name) }
            .sortedBy { it.tool }
    }

    /**
     * Export all statistics as a serializable [JsonElement] for status payloads.
     */
    fun toJsonElement(): JsonElement =
        buildJsonObject {
            put("tools", json.encodeToJsonElement(getAllStats()))
        }

    fun clear() {
        synchronized(windowsLock) {
            windows.clear()
        }
    }

    override fun tools(): List<McpToolDefinition> =
        listOf(
            McpToolDefinition(
                name = TOOL_NAME,
                description =
                    "Returns real-time execution statistics " +
                        "(call volume, p50/p99 latency, failure rates) for MCP tools.",
                inputSchema =
                    """
                    {
                        "type": "object",
                        "properties": {
                            "tool": {
                                "type": "string",
                                "description": "Optional specific tool name to query statistics for."
                            }
                        }
                    }
                    """.trimIndent(),
                handler = McpToolHandler { args -> handleToolStats(args) },
                readOnly = true,
            ),
        )

    private fun handleToolStats(args: McpToolArgs): McpToolResult {
        val requestedTool = args.string("tool") ?: args.string("toolName")
        return if (!requestedTool.isNullOrBlank()) {
            val normalized = requestedTool.removePrefix(McpToolRegistryImpl.CLIENT_TOOL_PREFIX)
            val stats = getStats(normalized)
            McpToolResult(json.encodeToString(stats))
        } else {
            val allStats = getAllStats()
            val result =
                buildJsonObject {
                    put("tools", json.encodeToJsonElement(allStats))
                }
            McpToolResult(result.toString())
        }
    }
}
