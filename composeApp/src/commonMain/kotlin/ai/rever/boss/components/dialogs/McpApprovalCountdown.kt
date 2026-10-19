package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpApprovalRequest

// Approval countdown helpers for [McpApprovalDialog], kept in their own file so the
// dialog file stays under detekt's TooManyFunctions threshold.

internal const val APPROVAL_COUNTDOWN_TICK_MS = 250L
internal const val APPROVAL_EXPIRY_WARNING_MS = 10_000L

internal fun approvalMillisRemaining(
    request: McpApprovalRequest,
    nowMs: Long,
): Long {
    val timeout = request.timeoutMs.coerceAtLeast(0L)
    val deadline =
        if (request.requestedAt > Long.MAX_VALUE - timeout) {
            Long.MAX_VALUE
        } else {
            request.requestedAt + timeout
        }
    // A wall-clock correction must not make an approval live longer than its configured timeout.
    return (deadline - nowMs).coerceIn(0L, timeout)
}

internal fun approvalExpiryLabel(remainingMs: Long): String {
    if (remainingMs <= 0L) return "Approval expired"
    val totalSeconds = (remainingMs + 999L) / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "Expires in $minutes:${seconds.toString().padStart(2, '0')}"
}
