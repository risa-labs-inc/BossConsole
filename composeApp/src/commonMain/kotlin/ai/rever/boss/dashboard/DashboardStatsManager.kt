package ai.rever.boss.dashboard

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val dashboardStatsLogger = BossLogger.forComponent("DashboardStatsManager")

/**
 * Data class for daily activity tracking.
 */
@Serializable
data class DailyActivity(
    val date: String = LocalDate.now().format(DateTimeFormatter.ISO_DATE),
    val filesOpened: Int = 0,
    val pagesVisited: Int = 0,
    val terminalSessions: Int = 0,
)

/**
 * Data class for overall dashboard statistics.
 */
@Serializable
data class DashboardStats(
    val totalFilesOpened: Int = 0,
    val totalBrowserPagesVisited: Int = 0,
    val totalTerminalSessions: Int = 0,
    val todayActivity: DailyActivity = DailyActivity(),
    val sessionStartTime: Long = System.currentTimeMillis(),
)

/**
 * Manages dashboard statistics and activity tracking.
 * Persists to ~/.boss/dashboard-stats.json
 *
 * Thread-safe: All file I/O operations run on Dispatchers.IO.
 * Uses StateFlow for reactive UI updates.
 */
// Cohesive stats lifecycle: loading, tracking, debounced saves and the shutdown flush all own
// the same persistence file; splitting it would scatter one responsibility.
@Suppress("TooManyFunctions")
object DashboardStatsManager {
    private const val SAVE_DEBOUNCE_MS = 5000L // Debounce saves to max once per 5 seconds
    private val settingsFile = BossDirectories.resolve("dashboard-stats.json")
    private val json =
        Json {
            prettyPrint = false
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var saveJob: Job? = null
    private val saveJobLock = Any()

    private val _stats = MutableStateFlow(DashboardStats())
    val stats: StateFlow<DashboardStats> = _stats.asStateFlow()

    private val _sessionStartTime = MutableStateFlow(System.currentTimeMillis())
    val sessionStartTime: StateFlow<Long> = _sessionStartTime.asStateFlow()

    private val initialLoadJob: Job =
        scope.launch {
            loadAsync()
            // Reset daily activity if it's a new day
            checkAndResetDailyActivity()
        }

    /**
     * Lets desktop tests establish their persisted-state baseline after the singleton's
     * asynchronous startup load has settled. Production callers must not wait for this work.
     */
    internal suspend fun awaitInitialLoadForTesting() {
        initialLoadJob.join()
    }

    /**
     * Load stats from disk asynchronously.
     */
    private suspend fun loadAsync() =
        withContext(Dispatchers.IO) {
            try {
                settingsFile.parentFile?.mkdirs()

                if (settingsFile.exists()) {
                    val content = settingsFile.readText()
                    val data = json.decodeFromString<DashboardStats>(content)
                    _stats.value = data
                    dashboardStatsLogger.debug(LogCategory.SYSTEM, "Loaded stats")
                }
            } catch (e: Exception) {
                dashboardStatsLogger.warn(LogCategory.SYSTEM, "Error loading stats", error = e)
            }
        }

    /**
     * Save stats to disk with debouncing.
     * Cancels any pending save and schedules a new one after SAVE_DEBOUNCE_MS.
     *
     * The cancel-then-assign dance is wrapped in [saveJobLock] - without it, two recorders
     * arriving concurrently (a navigation event firing recordPageVisit while recordFileOpen
     * is in flight) can race the lock-less `var` and leak a reference to the prior debounced
     * job, which then fires and writes stale stats on top of newer ones. The shape is exactly
     * what the sibling `RecentFilesManager.scheduleSave` (lines 249-256) and
     * `RecentBrowserPagesManager.scheduleSave` (lines 318-331) use, both pinned by tests.
     */
    private fun scheduleSave() {
        synchronized(saveJobLock) {
            saveJob?.cancel()
            saveJob =
                scope.launch {
                    delay(SAVE_DEBOUNCE_MS)
                    saveImmediately()
                }
        }
    }

    /**
     * Write any debounced save out now, from the shutdown sequence - a stat recorded within
     * [SAVE_DEBOUNCE_MS] of quitting would otherwise be lost. Same idiom as
     * `RecentFilesManager.flushPendingSaves`.
     */
    suspend fun flushPendingSaves() {
        val pending =
            synchronized(saveJobLock) {
                val active = saveJob?.isActive == true
                saveJob?.cancel()
                saveJob = null
                active
            }
        if (pending) saveImmediately()
    }

    /**
     * Immediately save stats to disk (bypasses debounce).
     */
    private suspend fun saveImmediately() =
        withContext(Dispatchers.IO) {
            try {
                settingsFile.parentFile?.mkdirs()
                val content = json.encodeToString(DashboardStats.serializer(), _stats.value)
                // Atomic: `writeText` truncates and then streams, so a crash mid-write leaves a
                // half-written JSON file that the next load refuses to decode - and the dashboard
                // numbers a user has been watching all session reset to zero. `atomicWriteText`
                // stages the bytes in a sibling temp file and moves it into place, matching the
                // shape every other small JSON file in the host uses.
                settingsFile.atomicWriteText(content)
            } catch (e: Exception) {
                dashboardStatsLogger.warn(LogCategory.SYSTEM, "Error saving stats", error = e)
            }
        }

    /**
     * Check if it's a new day and reset daily activity if needed.
     * Uses atomic update to prevent race conditions.
     */
    private fun checkAndResetDailyActivity() {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
        _stats.update { current ->
            if (current.todayActivity.date != today) {
                // New day - reset daily activity
                scheduleSave()
                current.copy(todayActivity = DailyActivity(date = today))
            } else {
                current
            }
        }
    }

    /**
     * Record a file open event.
     * Uses atomic update to prevent race conditions from rapid calls.
     */
    fun recordFileOpen() {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
        _stats.update { current ->
            val activity =
                if (current.todayActivity.date != today) {
                    DailyActivity(date = today, filesOpened = 1)
                } else {
                    current.todayActivity.copy(filesOpened = current.todayActivity.filesOpened + 1)
                }
            current.copy(
                totalFilesOpened = current.totalFilesOpened + 1,
                todayActivity = activity,
            )
        }
        scheduleSave()
    }

    /**
     * Record a browser page visit.
     * Uses atomic update to prevent race conditions from rapid calls.
     */
    fun recordPageVisit() {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
        _stats.update { current ->
            val activity =
                if (current.todayActivity.date != today) {
                    DailyActivity(date = today, pagesVisited = 1)
                } else {
                    current.todayActivity.copy(pagesVisited = current.todayActivity.pagesVisited + 1)
                }
            current.copy(
                totalBrowserPagesVisited = current.totalBrowserPagesVisited + 1,
                todayActivity = activity,
            )
        }
        scheduleSave()
    }

    /**
     * Record a terminal session start.
     * Uses atomic update to prevent race conditions from rapid calls.
     */
    fun recordTerminalSession() {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_DATE)
        _stats.update { current ->
            val activity =
                if (current.todayActivity.date != today) {
                    DailyActivity(date = today, terminalSessions = 1)
                } else {
                    current.todayActivity.copy(terminalSessions = current.todayActivity.terminalSessions + 1)
                }
            current.copy(
                totalTerminalSessions = current.totalTerminalSessions + 1,
                todayActivity = activity,
            )
        }
        scheduleSave()
    }

    /**
     * Get the current session duration in milliseconds.
     */
    fun getSessionDurationMs(): Long = System.currentTimeMillis() - _sessionStartTime.value

    /**
     * Format session duration as human-readable string.
     */
    fun formatSessionDuration(): String {
        val durationMs = getSessionDurationMs()
        val seconds = (durationMs / 1000) % 60
        val minutes = (durationMs / (1000 * 60)) % 60
        val hours = durationMs / (1000 * 60 * 60)

        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    /**
     * Reset all statistics.
     */
    fun resetStats() {
        _stats.value = DashboardStats()
        _sessionStartTime.value = System.currentTimeMillis()
        scheduleSave()
    }
}
