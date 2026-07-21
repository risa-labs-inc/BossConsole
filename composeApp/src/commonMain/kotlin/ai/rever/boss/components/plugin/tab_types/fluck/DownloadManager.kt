package ai.rever.boss.components.plugin.tab_types.fluck

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Manages download state and provides reactive updates to UI.
 * Thread-safe and optimized for high-frequency progress updates.
 *
 * Uses internal Map for fast lookups and exposes throttled List for UI consumption.
 */
@OptIn(FlowPreview::class)
class DownloadManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    // Internal mutable state - Map for efficient lookups
    private val _downloadsMap = MutableStateFlow<Map<String, DownloadItem>>(emptyMap())

    // Public exposed state - List sorted by start time, throttled for UI
    val downloads: StateFlow<List<DownloadItem>> = _downloadsMap
        .map { map ->
            map.values.sortedByDescending { it.startedAt }
        }
        .sample(150.milliseconds) // Throttle to 150ms as recommended by GPT-5
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    // Track speed samples for rolling average calculation
    private val speedSamples = mutableMapOf<String, SpeedCalculator>()

    /**
     * Adds a new download to the manager.
     * If download with same ID exists, it will be replaced.
     */
    suspend fun addDownload(item: DownloadItem) {
        mutex.withLock {
            _downloadsMap.value = _downloadsMap.value + (item.id to item)
            speedSamples[item.id] = SpeedCalculator()
        }
    }

    /**
     * Updates download progress with new received/total bytes and speed.
     * Calculates rolling average speed for smoother display.
     */
    suspend fun updateProgress(
        id: String,
        receivedBytes: Long,
        totalBytes: Long?,
        instantSpeed: Double
    ) {
        mutex.withLock {
            val current = _downloadsMap.value[id] ?: return

            // Calculate rolling average speed
            val calculator = speedSamples[id] ?: SpeedCalculator()
            calculator.addSample(instantSpeed)
            val averageSpeed = calculator.getAverage()

            val updated = current.copy(
                receivedBytes = receivedBytes,
                totalBytes = totalBytes ?: current.totalBytes,
                speed = averageSpeed
            )

            _downloadsMap.value = _downloadsMap.value + (id to updated)
        }
    }

    /**
     * Updates download status (e.g., from DOWNLOADING to COMPLETED).
     * For terminal states, records finish time.
     */
    suspend fun updateStatus(id: String, status: DownloadStatus, errorReason: String? = null) {
        mutex.withLock {
            val current = _downloadsMap.value[id] ?: return

            val updated = current.copy(
                status = status,
                finishedAt = if (status.isTerminal()) System.currentTimeMillis() else current.finishedAt,
                errorReason = errorReason
            )

            _downloadsMap.value = _downloadsMap.value + (id to updated)

            // Clean up speed calculator for terminal states
            if (status.isTerminal()) {
                speedSamples.remove(id)
            }
        }
    }

    /**
     * Updates pause/resume capability flags for a download.
     */
    suspend fun updateCapabilities(id: String, canPause: Boolean, canResume: Boolean) {
        mutex.withLock {
            val current = _downloadsMap.value[id] ?: return

            val updated = current.copy(
                canPause = canPause,
                canResume = canResume
            )

            _downloadsMap.value = _downloadsMap.value + (id to updated)
        }
    }

    /**
     * Removes a specific download from the manager.
     */
    suspend fun removeDownload(id: String) {
        mutex.withLock {
            _downloadsMap.value = _downloadsMap.value - id
            speedSamples.remove(id)
        }
    }

    /**
     * Pauses a download by updating its status.
     * Note: Actual JxBrowser pause call happens in FluckEngine (desktop-only).
     * @param id The download ID to pause
     */
    suspend fun pauseDownload(id: String) {
        mutex.withLock {
            val download = _downloadsMap.value[id] ?: return
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
        mutex.withLock {
            val download = _downloadsMap.value[id] ?: return
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
        mutex.withLock {
            val originalKeys = _downloadsMap.value.keys
            val filtered = _downloadsMap.value.filterValues { item ->
                item.status != DownloadStatus.COMPLETED
            }
            _downloadsMap.value = filtered

            // Clean up speed calculators for removed downloads
            val removedIds = originalKeys - filtered.keys
            removedIds.forEach { speedSamples.remove(it) }
        }
    }

    /**
     * Removes all failed and cancelled downloads from the manager.
     */
    suspend fun clearFailedAndCancelled() {
        mutex.withLock {
            val originalKeys = _downloadsMap.value.keys
            val filtered = _downloadsMap.value.filterValues { item ->
                item.status != DownloadStatus.FAILED && item.status != DownloadStatus.CANCELLED
            }
            _downloadsMap.value = filtered

            // Clean up speed calculators for removed downloads
            val removedIds = originalKeys - filtered.keys
            removedIds.forEach { speedSamples.remove(it) }
        }
    }

    /**
     * Gets a specific download by ID.
     */
    fun getDownload(id: String): DownloadItem? {
        return _downloadsMap.value[id]
    }

    /**
     * Gets count of active downloads (downloading or queued).
     */
    fun getActiveCount(): Int {
        return _downloadsMap.value.values.count { it.isActive }
    }

    /**
     * Gets count of completed downloads.
     */
    fun getCompletedCount(): Int {
        return _downloadsMap.value.values.count { it.status == DownloadStatus.COMPLETED }
    }

    private fun DownloadStatus.isTerminal(): Boolean {
        return this in setOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELLED)
    }
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
