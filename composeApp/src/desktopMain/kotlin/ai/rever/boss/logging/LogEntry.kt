package ai.rever.boss.logging

/**
 * Represents a single log entry captured from stdout or stderr.
 *
 * @property timestamp When the log was captured (milliseconds since epoch)
 * @property message The log message text
 * @property source Whether this came from stdout or stderr
 */
data class LogEntry(
    val timestamp: Long,
    val message: String,
    val source: LogSource,
) {
    /**
     * Format timestamp as HH:mm:ss.SSS
     */
    fun formatTimestamp(): String {
        val instant = kotlin.time.Instant.fromEpochMilliseconds(timestamp)
        val dateTime = instant.toString() // ISO 8601 format

        // Extract time portion (HH:mm:ss.SSS)
        return try {
            val timeStart = dateTime.indexOf('T') + 1
            val timeEnd = dateTime.indexOf('Z')
            if (timeStart > 0 && timeEnd > timeStart) {
                dateTime.substring(timeStart, timeEnd).take(12) // HH:mm:ss.SSS
            } else {
                "00:00:00.000"
            }
        } catch (ignored: Exception) {
            // Deliberately unlogged: this formatter runs inside the stdout/stderr
            // capture pipeline, so logging here could feed back into the capture
            // loop. A placeholder timestamp is the safe fallback.
            "00:00:00.000"
        }
    }
}

/**
 * Source of a log entry.
 */
enum class LogSource {
    /**
     * Standard output (System.out)
     */
    STDOUT,

    /**
     * Standard error (System.err)
     */
    STDERR,
}
