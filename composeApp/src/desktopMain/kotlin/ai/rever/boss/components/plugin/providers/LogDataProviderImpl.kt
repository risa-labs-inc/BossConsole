package ai.rever.boss.components.plugin.providers

import ai.rever.boss.logging.GlobalLogCapture
import ai.rever.boss.logging.LogEntry
import ai.rever.boss.logging.LogSource
import ai.rever.boss.plugin.api.LogDataProvider
import ai.rever.boss.plugin.api.LogEntryData
import ai.rever.boss.plugin.api.LogFilterData
import ai.rever.boss.plugin.api.LogSourceData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    /**
     * Coalesces rebuild requests instead of rebuilding once per captured line.
     *
     * [updateLogs] is O(buffered lines): it copies the whole capture buffer, filters it, and
     * allocates a `LogEntryData` for every entry. It used to run **synchronously from the log
     * listener**, which `DesktopLogCapture` invokes from inside `PrintStream.write` - so each
     * line of output rebuilt a list of up to 10,000 entries while holding the `System.out`
     * monitor, and every other thread that wanted to log waited behind it.
     *
     * That made log volume self-amplifying. One 30-frame stack trace is ~30 captured lines, so a
     * component logging a trace a few times a second had the app allocating hundreds of thousands
     * of objects per second and serialising every logging thread in the process - including the
     * UI thread - against a queue full of its own output. A repeating exception anywhere could
     * therefore freeze the whole app, which is what a dead browser handle did in practice.
     *
     * CONFLATED, so a burst collapses to one rebuild: the panel wants the latest snapshot, never
     * the intermediate ones. `trySend` from the listener cannot block or fail on a full buffer,
     * which keeps the write path O(1) whatever the consumer is doing.
     */
    private val rebuildRequests = Channel<Unit>(Channel.CONFLATED)

    /**
     * Not the caller's scope: this outlives any one window, and the provider is process-wide.
     * Default rather than Main, because a rebuild must never run on the UI thread.
     */
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        scope.launch {
            while (true) {
                rebuildRequests.receive()
                updateLogs()
                // Floor between rebuilds. A steady stream of output would otherwise still
                // rebuild per line, since CONFLATED only merges what arrives while busy.
                delay(REBUILD_INTERVAL_MS)
            }
        }

        // Listen for new log entries. Must stay O(1) - see [rebuildRequests].
        logCapture.addListener { _ ->
            rebuildRequests.trySend(Unit)
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
        val filtered =
            when (_filter.value) {
                LogFilterData.ALL -> allLogs
                LogFilterData.STDOUT -> allLogs.filter { it.source == LogSource.STDOUT }
                LogFilterData.STDERR -> allLogs.filter { it.source == LogSource.STDERR }
            }

        // Apply search
        val searched =
            if (_searchQuery.value.isNotEmpty()) {
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

    override fun exportLogs(): String =
        _logs.value.joinToString("\n") { entry ->
            "[${entry.formatTimestamp()}] [${entry.source}] ${entry.message}"
        }

    /**
     * Convert internal LogEntry to API LogEntryData.
     */
    private fun convertToLogEntryData(entry: LogEntry): LogEntryData =
        LogEntryData(
            timestamp = entry.timestamp,
            message = entry.message,
            source =
                when (entry.source) {
                    LogSource.STDOUT -> LogSourceData.STDOUT
                    LogSource.STDERR -> LogSourceData.STDERR
                },
        )

    private companion object {
        /** Minimum gap between log-list rebuilds while output keeps arriving. */
        const val REBUILD_INTERVAL_MS = 150L
    }
}
