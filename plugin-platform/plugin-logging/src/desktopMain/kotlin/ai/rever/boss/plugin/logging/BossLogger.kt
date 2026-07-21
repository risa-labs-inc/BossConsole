package ai.rever.boss.plugin.logging

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/**
 * Log levels for BOSS logging.
 */
enum class LogLevel(val priority: Int) {
    TRACE(0),
    DEBUG(1),
    INFO(2),
    WARN(3),
    ERROR(4),
    OFF(5);

    companion object {
        fun fromString(value: String): LogLevel {
            return entries.find { it.name.equals(value, ignoreCase = true) } ?: INFO
        }
    }
}

/**
 * Log category for filtering and organizing logs.
 */
enum class LogCategory {
    AUTH,           // Authentication flows (login, logout, session)
    PASSKEY,        // Passkey/WebAuthn operations
    BROWSER,        // Browser/JxBrowser operations
    TERMINAL,       // Terminal/BossTerm operations
    NETWORK,        // Network requests and connectivity
    UI,             // UI components and navigation
    SYSTEM,         // System-level operations (startup, shutdown)
    EDITOR,         // Code editor operations
    FILE,           // File operations
    WORKSPACE,      // Workspace management
    GENERAL         // General/uncategorized
}

/**
 * Log entry for structured logging.
 *
 * @param timestamp Epoch milliseconds when the log entry was created (cheap to collect)
 */
data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val category: LogCategory,
    val component: String,
    val message: String,
    val data: Map<String, Any?>? = null,
    val error: Throwable? = null
)

/**
 * Listener for log events.
 */
fun interface LogListener {
    fun onLog(entry: LogEntry)
}

/**
 * Central BOSS logging facility with structured logging support.
 *
 * Features:
 * - Configurable log levels (global and per-category)
 * - Structured log entries with metadata
 * - Log listeners for UI integration (Console panel)
 * - File logging support
 * - SLF4J integration
 * - Data sanitization for sensitive information
 *
 * ## Usage
 * ```kotlin
 * // Get a logger for a component
 * val logger = BossLogger.forComponent("EmailAuthService")
 *
 * // Log messages
 * logger.info(LogCategory.AUTH, "User signed in")
 * logger.error(LogCategory.NETWORK, "Connection failed", error = exception)
 * logger.debug(LogCategory.SYSTEM, "Config loaded", data = mapOf("key" to "value"))
 * ```
 *
 * ## Security
 * Always use LogSanitizer for sensitive data:
 * ```kotlin
 * logger.info(LogCategory.AUTH, "Processing login",
 *     data = mapOf("email" to LogSanitizer.maskEmail(email)))
 * ```
 */
object BossLogger {
    private val slf4jLogger: Logger = LoggerFactory.getLogger("ai.rever.boss")

    /** Global log level - messages below this level are ignored */
    @Volatile
    var globalLevel: LogLevel = LogLevel.INFO
        private set

    /** Per-category log levels (override global) */
    private val categoryLevels = mutableMapOf<LogCategory, LogLevel>()
    private val categoryLock = Any()

    /** Log listeners for UI integration */
    private val listeners = mutableListOf<LogListener>()
    private val listenersLock = Any()

    /** File logging */
    private var logFile: File? = null
    private var fileLoggingEnabled = false
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    /** File rotation settings */
    private var maxFileSize: Long = 10 * 1024 * 1024 // 10 MB default
    private var maxBackupFiles: Int = 5
    private var stackTraceDepth: Int = 20 // Configurable stack trace depth (20 for deep call stacks)

    /** Async file writing */
    private val fileWriteChannel = Channel<LogEntry>(
        capacity = 1000,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private var fileWriteJob: Job? = null
    private val fileWriterLock = Any()
    @OptIn(ExperimentalCoroutinesApi::class)
    private val fileWriteScope = CoroutineScope(
        Dispatchers.IO.limitedParallelism(1) + SupervisorJob()
    )

    /** Maximum log entries to keep in memory (for UI) */
    private const val MAX_LOG_ENTRIES = 1000
    private val recentLogs = ArrayDeque<LogEntry>(MAX_LOG_ENTRIES)
    private val recentLogsLock = Any()

    /** Drop tracking for buffer overflow */
    private val droppedLogCount = AtomicLong(0)
    @Volatile
    private var lastDropWarningTime = 0L
    private const val DROP_WARNING_INTERVAL_MS = 60_000L // 1 minute

    /** Shutdown hook tracking */
    @Volatile
    private var shutdownHookRegistered = false
    @Volatile
    private var isShutdown = false
    private val shutdownLock = Any()

    /**
     * Configure the logger from environment or system properties.
     *
     * Checks for:
     * - BOSS_LOG_LEVEL environment variable
     * - boss.log.level system property
     * - Defaults to INFO for production, DEBUG if "dev" mode detected
     */
    fun configureFromEnvironment() {
        // Check environment variable first
        val envLevel = System.getenv("BOSS_LOG_LEVEL")
        if (envLevel != null) {
            globalLevel = LogLevel.fromString(envLevel)
            return
        }

        // Check system property
        val propLevel = System.getProperty("boss.log.level")
        if (propLevel != null) {
            globalLevel = LogLevel.fromString(propLevel)
            return
        }

        // Default based on whether we're in dev mode
        val isDevMode = System.getProperty("boss.dev.mode")?.toBoolean() == true ||
                System.getenv("BOSS_DEV_MODE")?.toBoolean() == true
        globalLevel = if (isDevMode) LogLevel.DEBUG else LogLevel.INFO
    }

    /**
     * Initialize the logger and register JVM shutdown hook.
     * Safe to call multiple times - shutdown hook is only registered once.
     *
     * Call this early in application startup to ensure logs are flushed on shutdown.
     */
    fun initialize() {
        synchronized(shutdownLock) {
            if (!shutdownHookRegistered) {
                Runtime.getRuntime().addShutdownHook(Thread {
                    shutdown()
                })
                shutdownHookRegistered = true
            }
        }
    }

    /**
     * Configure the logger with explicit settings.
     */
    fun configure(config: BossLoggerConfig) {
        globalLevel = config.globalLevel
        maxFileSize = config.maxFileSize
        maxBackupFiles = config.maxBackupFiles
        stackTraceDepth = config.stackTraceDepth

        synchronized(categoryLock) {
            categoryLevels.clear()
            categoryLevels.putAll(config.categoryLevels)
        }

        if (config.fileLoggingEnabled && config.logFilePath != null) {
            enableFileLogging(File(config.logFilePath))
        } else {
            disableFileLogging()
        }
    }

    /**
     * Set the global log level.
     */
    fun setGlobalLevel(level: LogLevel) {
        globalLevel = level
    }

    /**
     * Set log level for a specific category.
     */
    fun setCategoryLevel(category: LogCategory, level: LogLevel) {
        synchronized(categoryLock) {
            categoryLevels[category] = level
        }
    }

    /**
     * Clear category-specific log level (use global).
     */
    fun clearCategoryLevel(category: LogCategory) {
        synchronized(categoryLock) {
            categoryLevels.remove(category)
        }
    }

    /**
     * Get effective log level for a category.
     */
    fun getEffectiveLevel(category: LogCategory): LogLevel {
        return synchronized(categoryLock) {
            categoryLevels[category] ?: globalLevel
        }
    }

    /**
     * Enable file logging.
     */
    fun enableFileLogging(file: File) {
        try {
            file.parentFile?.mkdirs()
            logFile = file
            fileLoggingEnabled = true
            startFileWriter()
        } catch (e: Exception) {
            slf4jLogger.warn("Failed to enable file logging: ${e.message}")
        }
    }

    /**
     * Disable file logging.
     */
    fun disableFileLogging() {
        fileLoggingEnabled = false
        stopFileWriter()
        logFile = null
    }

    /**
     * Start the background file writer coroutine.
     * Synchronized to prevent multiple consumers if called concurrently.
     */
    private fun startFileWriter() {
        synchronized(fileWriterLock) {
            if (fileWriteJob?.isActive == true) return

            fileWriteJob = fileWriteScope.launch {
                for (entry in fileWriteChannel) {
                    try {
                        writeToFileAsync(entry)
                    } catch (e: Exception) {
                        // Avoid recursive logging
                        slf4jLogger.warn("Failed to write to log file: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Stop the background file writer coroutine.
     * Synchronized to coordinate with startFileWriter().
     */
    private fun stopFileWriter() {
        synchronized(fileWriterLock) {
            fileWriteJob?.cancel()
            fileWriteJob = null
        }
    }

    /**
     * Shutdown the logger and release resources.
     * Safe to call multiple times - only executes once.
     *
     * Called automatically on JVM shutdown if initialize() was called.
     * Can also be called manually for explicit cleanup.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun shutdown() {
        // Fast path without lock (double-checked locking)
        if (isShutdown) return
        synchronized(shutdownLock) {
            if (isShutdown) return
            isShutdown = true
        }

        try {
            // Drain remaining entries with timeout
            runBlocking {
                withTimeoutOrNull(5000) {
                    while (!fileWriteChannel.isEmpty) {
                        delay(100)
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore exceptions during shutdown drain
        }

        stopFileWriter()
        fileWriteScope.cancel()

        // Log final drop count if any
        val dropped = droppedLogCount.get()
        if (dropped > 0) {
            slf4jLogger.warn("BossLogger shutdown: $dropped log entries were dropped due to buffer overflow")
        }
    }

    /**
     * Add a log listener.
     */
    fun addListener(listener: LogListener) {
        synchronized(listenersLock) {
            listeners.add(listener)
        }
    }

    /**
     * Remove a log listener.
     */
    fun removeListener(listener: LogListener) {
        synchronized(listenersLock) {
            listeners.remove(listener)
        }
    }

    /**
     * Get recent log entries.
     */
    fun getRecentLogs(
        limit: Int = 100,
        category: LogCategory? = null,
        minLevel: LogLevel = LogLevel.TRACE
    ): List<LogEntry> {
        return synchronized(recentLogsLock) {
            recentLogs
                .filter { entry ->
                    entry.level.priority >= minLevel.priority &&
                    (category == null || entry.category == category)
                }
                .takeLast(limit)
        }
    }

    /**
     * Clear recent logs.
     */
    fun clearLogs() {
        synchronized(recentLogsLock) {
            recentLogs.clear()
        }
    }

    /**
     * Log a message.
     */
    internal fun log(entry: LogEntry) {
        val effectiveLevel = getEffectiveLevel(entry.category)
        if (entry.level.priority < effectiveLevel.priority) {
            return
        }

        // Store in recent logs
        synchronized(recentLogsLock) {
            if (recentLogs.size >= MAX_LOG_ENTRIES) {
                recentLogs.removeFirst()
            }
            recentLogs.addLast(entry)
        }

        // Format message for SLF4J
        val formattedMessage = buildString {
            append("[${entry.category}]")
            append(" ${entry.component}: ${entry.message}")
            if (entry.data != null) {
                append(" | ${entry.data}")
            }
        }

        // Log to SLF4J (which outputs to stdout, captured by GlobalLogCapture)
        when (entry.level) {
            LogLevel.TRACE -> slf4jLogger.trace(formattedMessage, entry.error)
            LogLevel.DEBUG -> slf4jLogger.debug(formattedMessage, entry.error)
            LogLevel.INFO -> slf4jLogger.info(formattedMessage, entry.error)
            LogLevel.WARN -> slf4jLogger.warn(formattedMessage, entry.error)
            LogLevel.ERROR -> slf4jLogger.error(formattedMessage, entry.error)
            LogLevel.OFF -> { /* no-op */ }
        }

        // Queue for async file logging
        if (fileLoggingEnabled) {
            val result = fileWriteChannel.trySend(entry)
            if (result.isFailure) {
                val count = droppedLogCount.incrementAndGet()
                val now = System.currentTimeMillis()
                if (now - lastDropWarningTime > DROP_WARNING_INTERVAL_MS) {
                    lastDropWarningTime = now
                    slf4jLogger.warn("BossLogger: $count log entries dropped due to buffer overflow")
                }
            }
        }

        // Notify listeners
        notifyListeners(entry)
    }

    /**
     * Format epoch milliseconds to human-readable timestamp.
     * Called lazily only when writing to file (not on hot path).
     */
    private fun formatTimestamp(millis: Long): String {
        return Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .format(dateFormatter)
    }

    /**
     * Write log entry to file asynchronously.
     * Includes file rotation when size limit is exceeded.
     */
    private fun writeToFileAsync(entry: LogEntry) {
        val file = logFile ?: return
        try {
            // Check if rotation is needed before writing
            if (file.exists() && file.length() >= maxFileSize) {
                rotateLogFiles(file)
            }

            val line = buildString {
                append(formatTimestamp(entry.timestamp))
                append(" [${entry.level.name.padEnd(5)}]")
                append(" [${entry.category.name}]")
                append(" ${entry.component}: ${entry.message}")
                if (entry.data != null) {
                    append(" | ${entry.data}")
                }
                if (entry.error != null) {
                    append("\n  Exception: ${entry.error.message}")
                    // Use configurable stack trace depth
                    val frames = if (stackTraceDepth <= 0) {
                        entry.error.stackTrace.toList()
                    } else {
                        entry.error.stackTrace.take(stackTraceDepth)
                    }
                    frames.forEach { frame ->
                        append("\n    at $frame")
                    }
                    if (stackTraceDepth > 0 && entry.error.stackTrace.size > stackTraceDepth) {
                        append("\n    ... ${entry.error.stackTrace.size - stackTraceDepth} more frames")
                    }
                }
                append("\n")
            }
            file.appendText(line)
        } catch (e: Exception) {
            // Avoid recursive logging
            slf4jLogger.warn("Failed to write to log file: ${e.message}")
        }
    }

    /**
     * Rotate log files when size limit is exceeded.
     * boss.log -> boss.log.1 -> boss.log.2 -> ... -> deleted
     */
    private fun rotateLogFiles(currentFile: File) {
        try {
            val baseName = currentFile.absolutePath

            // Delete oldest backup if at limit
            val oldestBackup = File("$baseName.$maxBackupFiles")
            if (oldestBackup.exists()) {
                oldestBackup.delete()
            }

            // Shift existing backups (boss.log.4 -> boss.log.5, etc.)
            for (i in (maxBackupFiles - 1) downTo 1) {
                val backup = File("$baseName.$i")
                if (backup.exists()) {
                    backup.renameTo(File("$baseName.${i + 1}"))
                }
            }

            // Move current file to .1
            if (currentFile.exists()) {
                currentFile.renameTo(File("$baseName.1"))
            }

            // Create fresh log file
            currentFile.createNewFile()
        } catch (e: SecurityException) {
            slf4jLogger.error("Permission denied rotating log files, disabling file logging: ${e.message}")
            disableFileLogging()
        } catch (e: Exception) {
            slf4jLogger.warn("Failed to rotate log files: ${e.message}")
        }
    }

    private fun notifyListeners(entry: LogEntry) {
        val listenersCopy = synchronized(listenersLock) {
            listeners.toList()
        }
        listenersCopy.forEach { listener ->
            try {
                listener.onLog(entry)
            } catch (e: Exception) {
                // Avoid recursive logging
                slf4jLogger.warn("Log listener error: ${e.message}")
            }
        }
    }

    /**
     * Create a component-specific logger.
     */
    fun forComponent(componentName: String): ComponentLogger {
        return ComponentLogger(componentName)
    }
}

/**
 * Configuration for BossLogger.
 *
 * @param globalLevel Default log level for all categories
 * @param categoryLevels Per-category log level overrides
 * @param fileLoggingEnabled Whether to enable file logging
 * @param logFilePath Path to the log file (required if fileLoggingEnabled is true)
 * @param maxFileSize Maximum log file size in bytes before rotation (default: 10 MB)
 * @param maxBackupFiles Maximum number of backup log files to keep (default: 5)
 * @param stackTraceDepth Number of stack trace frames to log (0 = unlimited, default: 10)
 */
data class BossLoggerConfig(
    val globalLevel: LogLevel = LogLevel.INFO,
    val categoryLevels: Map<LogCategory, LogLevel> = emptyMap(),
    val fileLoggingEnabled: Boolean = false,
    val logFilePath: String? = null,
    val maxFileSize: Long = 10 * 1024 * 1024, // 10 MB
    val maxBackupFiles: Int = 5,
    val stackTraceDepth: Int = 10
)

/**
 * Component-specific logger for convenient logging.
 */
class ComponentLogger(private val componentName: String) {

    fun trace(
        category: LogCategory,
        message: String,
        data: Map<String, Any?>? = null
    ) {
        log(LogLevel.TRACE, category, message, data, null)
    }

    fun debug(
        category: LogCategory,
        message: String,
        data: Map<String, Any?>? = null
    ) {
        log(LogLevel.DEBUG, category, message, data, null)
    }

    fun info(
        category: LogCategory,
        message: String,
        data: Map<String, Any?>? = null
    ) {
        log(LogLevel.INFO, category, message, data, null)
    }

    fun warn(
        category: LogCategory,
        message: String,
        data: Map<String, Any?>? = null,
        error: Throwable? = null
    ) {
        log(LogLevel.WARN, category, message, data, error)
    }

    fun error(
        category: LogCategory,
        message: String,
        data: Map<String, Any?>? = null,
        error: Throwable? = null
    ) {
        log(LogLevel.ERROR, category, message, data, error)
    }

    private fun log(
        level: LogLevel,
        category: LogCategory,
        message: String,
        data: Map<String, Any?>?,
        error: Throwable?
    ) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            category = category,
            component = componentName,
            message = message,
            data = data,
            error = error
        )
        BossLogger.log(entry)
    }
}
