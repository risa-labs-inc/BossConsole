package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.LogDataProvider
import ai.rever.boss.plugin.api.LogEntryData
import ai.rever.boss.plugin.api.LogFilterData
import ai.rever.boss.plugin.api.LogSourceData
import ai.rever.boss.logging.GlobalLogCapture
import ai.rever.boss.logging.LogEntry
import ai.rever.boss.logging.LogSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Implementation of LogDataProvider that wraps GlobalLogCapture.
 *
 * This adapter bridges the plugin-api LogDataProvider interface to the
 * main application's GlobalLogCapture singleton. Dynamic plugins use this
 * to access log data without classloader isolation issues.
 *
 * The GlobalLogCapture singleton is started in main.kt at app startup,
 * so this provider has access to ALL logs from application start.
 */
class LogDataProviderImpl : LogDataProvider {
    // Access the main app's log capture singleton
    private val logCapture = GlobalLogCapture.getLogCapture()

    // All logs (filtered by current filter and search)
    private val _logs = MutableStateFlow<List<LogEntryData>>(emptyList())
    override val logs: StateFlow<List<LogEntryData>> = _logs.asStateFlow()

    // Current filter
    private val _filter = MutableStateFlow(LogFilterData.ALL)
    override val filter: StateFlow<LogFilterData> = _filter.asStateFlow()

    // Search query
    private val _searchQuery = MutableStateFlow("")
    override val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Auto-scroll enabled
    private val _autoScroll = MutableStateFlow(true)
    override val autoScroll: StateFlow<Boolean> = _autoScroll.asStateFlow()

    init {
        // Listen for new log entries
        logCapture.addListener { _ ->
            updateLogs()
        }

        // Initial load (will load all logs from app startup)
        updateLogs()
    }

    /**
     * Update filtered logs based on current filter and search.
     */
    private fun updateLogs() {
        val allLogs = logCapture.getLogs()

        // Apply filter
        val filtered = when (_filter.value) {
            LogFilterData.ALL -> allLogs
            LogFilterData.STDOUT -> allLogs.filter { it.source == LogSource.STDOUT }
            LogFilterData.STDERR -> allLogs.filter { it.source == LogSource.STDERR }
        }

        // Apply search
        val searched = if (_searchQuery.value.isNotEmpty()) {
            filtered.filter {
                it.message.contains(_searchQuery.value, ignoreCase = true)
            }
        } else {
            filtered
        }

        // Convert to API data classes
        _logs.value = searched.map { convertToLogEntryData(it) }
    }

    override fun setFilter(filter: LogFilterData) {
        _filter.value = filter
        updateLogs()
    }

    override fun setSearchQuery(query: String) {
        _searchQuery.value = query
        updateLogs()
    }

    override fun toggleAutoScroll() {
        _autoScroll.value = !_autoScroll.value
    }

    override fun clearLogs() {
        logCapture.clear()
        updateLogs()
    }

    override fun exportLogs(): String {
        return _logs.value.joinToString("\n") { entry ->
            "[${entry.formatTimestamp()}] [${entry.source}] ${entry.message}"
        }
    }

    /**
     * Convert internal LogEntry to API LogEntryData.
     */
    private fun convertToLogEntryData(entry: LogEntry): LogEntryData {
        return LogEntryData(
            timestamp = entry.timestamp,
            message = entry.message,
            source = when (entry.source) {
                LogSource.STDOUT -> LogSourceData.STDOUT
                LogSource.STDERR -> LogSourceData.STDERR
            }
        )
    }
}
