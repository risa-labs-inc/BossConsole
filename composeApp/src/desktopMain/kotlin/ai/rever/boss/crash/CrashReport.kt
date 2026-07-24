package ai.rever.boss.crash

import ai.rever.boss.utils.Version
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogEntry
import ai.rever.boss.utils.logging.LogLevel
import kotlinx.serialization.Serializable

/**
 * Data class representing a crash report.
 *
 * @param signature Unique hash identifying this crash type (for deduplication)
 * @param exceptionType The type of the exception (e.g., "NullPointerException")
 * @param exceptionMessage The exception message (sanitized)
 * @param stackTrace The full stack trace as a string
 * @param systemInfo System information at the time of crash
 * @param appInfo Application information
 * @param timestamp Epoch milliseconds when the crash occurred
 * @param userNotes Optional notes provided by the user
 * @param recentLogs Optional sanitized log entries (user consent required)
 * @param pluginId Plugin the crash is attributed to (a stack frame's class was
 *   defined by that plugin's classloader), or null for host crashes. Routes the
 *   GitHub issue to the plugin's own repository.
 */
data class CrashReport(
    val signature: String,
    val exceptionType: String,
    val exceptionMessage: String,
    val stackTrace: String,
    val systemInfo: SystemInfo,
    val appInfo: AppInfo,
    val timestamp: Long,
    val userNotes: String? = null,
    val recentLogs: List<SanitizedLogEntry>? = null,
    val pluginId: String? = null,
)

/**
 * System information at the time of crash.
 */
data class SystemInfo(
    val osName: String,
    val osVersion: String,
    val osArch: String,
    val javaVersion: String,
    val javaVendor: String,
    val heapUsedMB: Long,
    val heapMaxMB: Long,
    val nonHeapUsedMB: Long,
    val availableProcessors: Int,
)

/**
 * Application information.
 */
data class AppInfo(
    val version: String,
    val platform: String,
    val isDebug: Boolean,
)

/**
 * A sanitized log entry without sensitive metadata.
 * Only includes essential information for debugging.
 */
@Serializable
data class SanitizedLogEntry(
    val timestamp: Long,
    val level: String,
    val category: String,
    val component: String,
    val message: String,
) {
    companion object {
        /**
         * Create a sanitized log entry from a full LogEntry.
         * Removes sensitive data map and error details.
         */
        fun fromLogEntry(entry: LogEntry): SanitizedLogEntry =
            SanitizedLogEntry(
                timestamp = entry.timestamp,
                level = entry.level.name,
                category = entry.category.name,
                component = entry.component,
                message =
                    ai.rever.boss.utils.logging.LogSanitizer
                        .sanitizeLogMessage(entry.message),
            )
    }
}
