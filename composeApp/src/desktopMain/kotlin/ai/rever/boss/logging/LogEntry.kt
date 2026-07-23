package ai.rever.boss.logging

import BossDarkError
import BossDarkTextPrimary
import androidx.compose.ui.graphics.Color

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
    val source: LogSource
) {
    /**
     * Get the color to display this log entry.
     * stdout = primary text, stderr = alert red
     */
    val color: Color
        get() = when (source) {
            LogSource.STDOUT -> BossDarkTextPrimary
            LogSource.STDERR -> BossDarkError
        }

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
        } catch (e: Exception) {
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
    STDERR
}
