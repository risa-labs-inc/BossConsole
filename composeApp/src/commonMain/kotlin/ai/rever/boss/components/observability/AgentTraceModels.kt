package ai.rever.boss.components.observability

import kotlin.time.TimeMark
import kotlin.time.TimeSource

enum class TraceStatus {
    RUNNING,
    SUCCESS,
    FAILURE,
    TIMEOUT,
    CANCELLED,
}

data class McpTraceEvent(
    val id: String,
    val toolName: String,
    val argumentsJson: String,
    val startedAtMs: Long,
    val completedAtMs: Long? = null,
    val status: TraceStatus = TraceStatus.RUNNING,
    val resultJson: String? = null,
    val errorMessage: String? = null,
    val durationMs: Long? = null,
    internal val startMark: TimeMark = TimeSource.Monotonic.markNow(),
)
