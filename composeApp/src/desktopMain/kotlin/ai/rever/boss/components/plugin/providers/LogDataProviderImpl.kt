package ai.rever.boss.components.plugin.providers

import ai.rever.boss.logging.DesktopLogCapture
import ai.rever.boss.logging.GlobalLogCapture
import ai.rever.boss.logging.LogEntry
import ai.rever.boss.logging.LogSource
import ai.rever.boss.plugin.api.LogDataProvider
import ai.rever.boss.plugin.api.LogEntryData
import ai.rever.boss.plugin.api.LogFilterData
import ai.rever.boss.plugin.api.LogSourceData
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 *
 * The capture is process-wide; THIS is not. `DefaultPlugin` builds one per window, so each
 * instance registers its own listener on that singleton and runs its own rebuild coroutine -
 * hence [dispose], which `DefaultPlugin.dispose()` calls when its window goes away.
 */
class LogDataProviderImpl(
    // Injectable so the coalescing can be tested. Defaults to the singleton the app runs on.
    private val logCapture: DesktopLogCapture = GlobalLogCapture.getLogCapture(),
    private val rebuildIntervalMs: Long = REBUILD_INTERVAL_MS,
) : LogDataProvider,
    DisposableProvider {
    private val logger = BossLogger.forComponent("LogDataProviderImpl")

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
     * Owned here, and cancelled by [dispose].
     *
     * Default rather than Main, because a rebuild must never run on the UI thread. NOT tied to
     * a caller's scope, but not immortal either: `DefaultPlugin` is created per window, so this
     * provider is too, and a `while (true)` consumer left running would mean every window ever
     * opened still rebuilding the log list with nobody observing it.
     */
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Held so [dispose] can unregister it - listeners are compared by identity. */
    private val listener: (LogEntry) -> Unit = { rebuildRequests.trySend(Unit) }

    init {
        scope.launch {
            while (true) {
                rebuildRequests.receive()
                // Guarded because this is the only consumer: an exception here would end the
                // coroutine and the panel would silently stop updating for the rest of the
                // session. SupervisorJob does not help - it is the same coroutine.
                //
                // Generic on purpose: the point is that no rebuild failure, of any kind, may
                // take the consumer with it. Nothing here has a specific recoverable failure
                // worth naming - it is a copy, a filter and a map.
                @Suppress("TooGenericExceptionCaught")
                try {
                    updateLogs()
                } catch (e: Exception) {
                    logger.warn(LogCategory.SYSTEM, "Log list rebuild failed", error = e)
                }
                // Floor between rebuilds. A steady stream of output would otherwise still
                // rebuild per line, since CONFLATED only merges what arrives while busy.
                delay(rebuildIntervalMs)
            }
        }

        // Listen for new log entries. Must stay O(1) - see [rebuildRequests].
        logCapture.addListener(listener)

        // Initial load (will load all logs from app startup)
        updateLogs()
    }

    /**
     * Stop rebuilding and stop listening.
     *
     * Both halves matter: cancelling the scope alone leaves a listener on the process-wide
     * capture that keeps calling `trySend` on a channel nobody reads, and unregistering alone
     * leaves the consumer parked forever on `receive`.
     */
    override fun dispose() {
        logCapture.removeListener(listener)
        scope.cancel()
    }

    /**
     * Rebuilds performed since construction.
     *
     * A test seam, and the only workable one: the thing worth asserting is "one captured line
     * does not cost one rebuild", and neither sampling `logs.value` nor collecting it can
     * measure that. `StateFlow` conflates, so a collector is not shown every value, and a
     * poller cannot count 2000 rebuilds that all happen inside a millisecond - a first version
     * of the test did exactly that and passed against the per-line rebuild it was written for.
     */
    @Volatile
    internal var rebuildCount: Int = 0
        private set

    /**
     * Update filtered logs based on current filter and search.
     */
    private fun updateLogs() {
        rebuildCount++
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

    // The setters below request a rebuild rather than performing one. Every write to
    // `_logs.value` then comes from the single consumer coroutine, which is what keeps a
    // background rebuild from landing after a filter change and repainting the previous
    // filter's contents - with nothing to correct it until the next log line, which in a
    // quiet app may never come.

    override fun setFilter(filter: LogFilterData) {
        _filter.value = filter
        rebuildRequests.trySend(Unit)
    }

    override fun setSearchQuery(query: String) {
        _searchQuery.value = query
        rebuildRequests.trySend(Unit)
    }

    override fun toggleAutoScroll() {
        _autoScroll.value = !_autoScroll.value
    }

    override fun clearLogs() {
        logCapture.clear()
        rebuildRequests.trySend(Unit)
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
