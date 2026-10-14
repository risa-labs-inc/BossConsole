package ai.rever.boss.components.plugin.tab_types.fluck

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * Manages download state and provides reactive updates to UI.
 * Thread-safe and optimized for high-frequency progress updates.
 *
 * Uses internal Map for fast lookups and exposes throttled List for UI consumption.
 * Progress ticks mutate a single map entry (O(1)) rather than copying the whole
 * map, and the sorted snapshot is produced only when a sampled emission goes out
 * (~150ms cadence) rather than once per tick (b21).
 */
@OptIn(FlowPreview::class)
class DownloadManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Guards [downloadsById] and [speedSamples]; all access is under this lock. */
    private val stateLock = Any()

    // Internal mutable state - Map for efficient lookups
    private val downloadsById = mutableMapOf<String, DownloadItem>()

    /**
     * Bumped once per mutation. [downloads] samples it, so a burst of per-chunk
     * progress ticks costs one StateFlow write each while the expensive sort
     * runs at most once per sampling period, only when something changed.
     */
    private val mutationTick = MutableStateFlow(0L)

    /**
     * Test hook: how many times the sorted snapshot has actually been produced.
     * Asserts that rapid progress updates do not re-sort per tick.
     */
    internal var sortedSnapshotCount = 0
        private set

    // Public exposed state - List sorted by start time, throttled for UI
    val downloads: StateFlow<List<DownloadItem>> =
        mutationTick
            .sample(150.milliseconds) // Throttle to 150ms as recommended by GPT-5
            .map { sortedSnapshot() }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    // Track speed samples for rolling average calculation
    private val speedSamples = mutableMapOf<String, SpeedCalculator>()

    private fun sortedSnapshot(): List<DownloadItem> =
        synchronized(stateLock) {
            sortedSnapshotCount++
            downloadsById.values.sortedByDescending { it.startedAt }
        }

    private fun markChanged() {
        mutationTick.update { it + 1 }
    }

    /**
     * Adds a new download to the manager.
     * If download with same ID exists, it will be replaced.
     */
    suspend fun addDownload(item: DownloadItem) {
        synchronized(stateLock) {
            downloadsById[item.id] = item
            speedSamples[item.id] = SpeedCalculator()
        }
        markChanged()
    }

    /**
     * Updates download progress with new received/total bytes and speed.
     * Calculates rolling average speed for smoother display.
     */
    suspend fun updateProgress(
        id: String,
        receivedBytes: Long,
        totalBytes: Long?,
        instantSpeed: Double,
    ) {
        synchronized(stateLock) {
            val current = downloadsById[id] ?: return

            // Calculate rolling average speed
            val calculator = speedSamples[id] ?: SpeedCalculator()
            calculator.addSample(instantSpeed)
            val averageSpeed = calculator.getAverage()

            downloadsById[id] =
                current.copy(
                    receivedBytes = receivedBytes,
                    totalBytes = totalBytes ?: current.totalBytes,
                    speed = averageSpeed,
                )
        }
        markChanged()
    }

    /**
     * Updates download status (e.g., from DOWNLOADING to COMPLETED).
     * For terminal states, records finish time.
     */
    suspend fun updateStatus(
        id: String,
        status: DownloadStatus,
        errorReason: String? = null,
    ) {
        synchronized(stateLock) {
            val current = downloadsById[id] ?: return

            downloadsById[id] =
                current.copy(
                    status = status,
                    finishedAt = if (status.isTerminal()) System.currentTimeMillis() else current.finishedAt,
                    errorReason = errorReason,
                )

            // Clean up speed calculator for terminal states
            if (status.isTerminal()) {
                speedSamples.remove(id)
            }
        }
        markChanged()
    }

    /**
     * Updates pause/resume capability flags for a download.
     */
    suspend fun updateCapabilities(
        id: String,
        canPause: Boolean,
        canResume: Boolean,
    ) {
        synchronized(stateLock) {
            val current = downloadsById[id] ?: return

            downloadsById[id] =
                current.copy(
                    canPause = canPause,
                    canResume = canResume,
                )
        }
        markChanged()
    }

    /**
     * Removes a specific download from the manager.
     */
    suspend fun removeDownload(id: String) {
        synchronized(stateLock) {
            downloadsById.remove(id)
            speedSamples.remove(id)
        }
        markChanged()
    }

    /**
     * Pauses a download by updating its status.
     * Note: Actual JxBrowser pause call happens in FluckEngine (desktop-only).
     * @param id The download ID to pause
     */
    suspend fun pauseDownload(id: String) {
        synchronized(stateLock) {
            val download = downloadsById[id] ?: return
            if (download.canPause && download.status == DownloadStatus.DOWNLOADING) {
                // Status will be updated by DownloadPaused event from JxBrowser
                // This method exists for validation and future platform-specific implementations
            }
        }
    }

    /**
     * Resumes a paused download by updating its status.
     * Note: Actual JxBrowser resume call happens in FluckEngine (desktop-only).
     * @param id The download ID to resume
     */
    suspend fun resumeDownload(id: String) {
        synchronized(stateLock) {
            val download = downloadsById[id] ?: return
            if (download.canResume && download.status == DownloadStatus.PAUSED) {
                // Status will be updated by DownloadResumed event from JxBrowser
                // This method exists for validation and future platform-specific implementations
            }
        }
    }

    /**
     * Removes all completed downloads from the manager.
     */
    suspend fun clearCompleted() {
        synchronized(stateLock) {
            // Clean up speed calculators for removed downloads
            downloadsById.values
                .filter { it.status == DownloadStatus.COMPLETED }
                .map { it.id }
                .forEach {
                    downloadsById.remove(it)
                    speedSamples.remove(it)
                }
        }
        markChanged()
    }

    /**
     * Removes all failed and cancelled downloads from the manager.
     */
    suspend fun clearFailedAndCancelled() {
        synchronized(stateLock) {
            downloadsById.values
                .filter { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELLED }
                .map { it.id }
                .forEach {
                    downloadsById.remove(it)
                    speedSamples.remove(it)
                }
        }
        markChanged()
    }

    /**
     * Gets a specific download by ID.
     */
    fun getDownload(id: String): DownloadItem? = synchronized(stateLock) { downloadsById[id] }

    /**
     * Gets count of active downloads (downloading or queued).
     */
    fun getActiveCount(): Int = synchronized(stateLock) { downloadsById.values.count { it.isActive } }

    /**
     * Gets count of completed downloads.
     */
    fun getCompletedCount(): Int =
        synchronized(stateLock) {
            downloadsById.values.count { it.status == DownloadStatus.COMPLETED }
        }

    private fun DownloadStatus.isTerminal(): Boolean =
        this in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELLED)
}

/**
 * Helper class for calculating rolling average of download speeds.
 * Uses a sliding window to smooth out speed fluctuations.
 */
private class SpeedCalculator {
    private val samples = mutableListOf<Double>()
    private val maxSize = 10

    fun addSample(speed: Double) {
        if (samples.size >= maxSize) {
            samples.removeAt(0)
        }
        samples.add(speed)
    }

    fun getAverage(): Double {
        if (samples.isEmpty()) return 0.0
        return samples.average()
    }
}
