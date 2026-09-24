package ai.rever.boss.performance

import ai.rever.boss.config.SystemMemory
import ai.rever.boss.plugin.pathutils.BossDirectories
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import java.lang.management.MemoryMXBean
import java.lang.management.MemoryPoolMXBean
import java.lang.management.OperatingSystemMXBean
import java.lang.management.ThreadMXBean
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Global singleton for performance monitoring.
 *
 * Usage:
 * - main.kt: Call PerformanceMonitor.start() at app startup
 * - Status bar: Observe PerformanceMonitor.currentSnapshot
 * - Panel: Access PerformanceMonitor.history for charts
 */
object PerformanceMonitor {
    private val logger = BossLogger.forComponent("PerformanceMonitor")
    private val memoryMXBean: MemoryMXBean = ManagementFactory.getMemoryMXBean()
    private val memoryPoolMXBeans: List<MemoryPoolMXBean> = ManagementFactory.getMemoryPoolMXBeans()
    private val osMXBean: OperatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean()
    private val threadMXBean: ThreadMXBean = ManagementFactory.getThreadMXBean()
    private val gcMXBeans: List<GarbageCollectorMXBean> = ManagementFactory.getGarbageCollectorMXBeans()

    // Sun/Oracle specific for process CPU load and GC info
    private val sunOSBean: com.sun.management.OperatingSystemMXBean? =
        osMXBean as? com.sun.management.OperatingSystemMXBean
    private val sunGcBeans: List<com.sun.management.GarbageCollectorMXBean> =
        gcMXBeans.mapNotNull { it as? com.sun.management.GarbageCollectorMXBean }

    private val _currentSnapshot = MutableStateFlow<PerformanceSnapshot?>(null)
    val currentSnapshot: StateFlow<PerformanceSnapshot?> = _currentSnapshot.asStateFlow()

    private val _currentHealth =
        MutableStateFlow(
            PerformanceHealth(HealthStatus.GOOD, HealthStatus.GOOD, HealthStatus.GOOD),
        )
    val currentHealth: StateFlow<PerformanceHealth> = _currentHealth.asStateFlow()

    // Max history entries, bounding retained snapshots regardless of the retention
    // setting. Entries are appended on every significant change, so at the 1s floor
    // for the memory tick this is one hour of history; the age cap in
    // PerformanceSettings.MAX_HISTORY_RETENTION_MINUTES bounds it the other way.
    internal const val MAX_HISTORY_SIZE = 3_600

    // Use ArrayDeque as a circular buffer for efficient history management
    // Memory implications: entries are compacted via PerformanceSnapshot.forHistory
    // before they land here, so a full buffer is bounded megabytes, not the tens of
    // megabytes a full top-thread list per entry used to cost.
    // The historyBuffer is the source of truth; _history StateFlow is updated every 10 seconds
    // to avoid excessive allocations. Each StateFlow update creates an immutable list copy.
    // For UI charts that need real-time data, use currentSnapshot instead of history.
    // For historical analysis, consider paginating history access or using historyBuffer directly.
    private val historyBuffer = ArrayDeque<PerformanceSnapshot>(MAX_HISTORY_SIZE)
    private val _history = MutableStateFlow<List<PerformanceSnapshot>>(emptyList())
    val history: StateFlow<List<PerformanceSnapshot>> = _history.asStateFlow()

    // Resource count providers (registered by BossApp)
    private var browserTabCountProvider: (() -> Int)? = null
    private var terminalCountProvider: (() -> Int)? = null
    private var editorTabCountProvider: (() -> Int)? = null
    private var panelCountProvider: (() -> Int)? = null
    private var windowCountProvider: (() -> Int)? = null

    private var monitoringJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var lastGcTime: Long = 0
    private var lastHistoryUpdate: Long = 0
    private var historyModified: Boolean = false // Track if buffer changed since last StateFlow update
    private const val HISTORY_UPDATE_INTERVAL_MS = 10_000L // Update history StateFlow every 10 seconds

    // Thread detail and memory-pool detail refresh on their own slow cadences rather
    // than on their metrics' ticks: both build per-entry metadata lists that only the
    // panel's detail views read, so refreshing them every 1-2s paid full price for
    // data nobody looks at between redraws.
    private val threadSampler = TopThreadSampler(threadMXBean)
    private var lastMemoryPoolScanMs: Long = 0
    private const val MEMORY_POOL_SAMPLE_INTERVAL_MS = 10_000L

    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    /**
     * Start performance monitoring.
     * Should be called once in main.kt after GlobalLogCapture.start()
     */
    fun start() {
        if (monitoringJob != null) return

        logger.debug(LogCategory.SYSTEM, "Starting performance monitor")

        monitoringJob =
            scope.launch {
                var memoryTick = 0L
                var cpuTick = 0L
                var resourceTick = 0L
                var gcTick = 0L

                while (isActive) {
                    val settings = PerformanceSettingsManager.currentSettings.value

                    if (!settings.enabled) {
                        // Clear stale history when monitoring is disabled
                        if (historyBuffer.isNotEmpty()) {
                            historyBuffer.clear()
                            _history.value = emptyList()
                        }
                        delay(1000)
                        continue
                    }

                    val now = System.currentTimeMillis()
                    // Initialize with actual metrics on first run to avoid NPE
                    var memory = _currentSnapshot.value?.memory ?: collectMemoryMetrics()
                    var cpu = _currentSnapshot.value?.cpu ?: collectCpuMetrics()
                    var gc = _currentSnapshot.value?.gc ?: collectGcMetrics()
                    var resources = _currentSnapshot.value?.resources ?: collectResourceMetrics()

                    // Sample memory. Pool metadata refreshes on its own slower cadence;
                    // between scans the previous pool list is carried forward.
                    if (now - memoryTick >= settings.memorySampleIntervalMs) {
                        memory =
                            if (now - lastMemoryPoolScanMs >= MEMORY_POOL_SAMPLE_INTERVAL_MS) {
                                lastMemoryPoolScanMs = now
                                collectMemoryMetrics()
                            } else {
                                collectMemoryMetrics(memoryPools = memory.memoryPools)
                            }
                        memoryTick = now
                    }

                    // Sample CPU
                    if (now - cpuTick >= settings.cpuSampleIntervalMs) {
                        cpu = collectCpuMetrics()
                        cpuTick = now
                    }

                    // Sample GC
                    if (now - gcTick >= settings.gcSampleIntervalMs) {
                        gc = collectGcMetrics()
                        gcTick = now
                    }

                    // Sample resources
                    if (now - resourceTick >= settings.resourceSampleIntervalMs) {
                        resources = collectResourceMetrics()
                        resourceTick = now
                    }

                    val snapshot =
                        PerformanceSnapshot(
                            timestamp = now,
                            memory = memory,
                            cpu = cpu,
                            gc = gc,
                            resources = resources,
                        )

                    // Only update if values changed to avoid unnecessary recomposition
                    val current = _currentSnapshot.value
                    if (current == null || hasSignificantChange(current, snapshot)) {
                        _currentSnapshot.value = snapshot
                        _currentHealth.value = PerformanceHealth.fromSnapshot(snapshot, settings)
                    }

                    // Update history buffer (cheap - just pointer manipulation)
                    // Only add to buffer when there's a significant change
                    val shouldAddToHistory = current == null || hasSignificantChange(current, snapshot)

                    if (shouldAddToHistory) {
                        val retentionMinutes =
                            minOf(
                                settings.historyRetentionMinutes,
                                PerformanceSettings.MAX_HISTORY_RETENTION_MINUTES,
                            )
                        val cutoff = now - (retentionMinutes * 60 * 1000)

                        // Remove old entries from front
                        while (historyBuffer.isNotEmpty() && historyBuffer.first().timestamp < cutoff) {
                            historyBuffer.removeFirst()
                            historyModified = true
                        }

                        // Add the compacted form - the full snapshot stays live-only
                        historyBuffer.addLast(snapshot.forHistory())
                        historyModified = true

                        // Enforce max size (shouldn't happen often with proper retention)
                        while (historyBuffer.size > MAX_HISTORY_SIZE) {
                            historyBuffer.removeFirst()
                            historyModified = true
                        }
                    }

                    // Update StateFlow less frequently (expensive - creates full list copy)
                    // Only update if: (1) enough time passed AND (2) history actually changed
                    if (now - lastHistoryUpdate > HISTORY_UPDATE_INTERVAL_MS && historyModified) {
                        _history.value = historyBuffer.toList()
                        lastHistoryUpdate = now
                        historyModified = false
                    }

                    delay(
                        minOf(
                            settings.memorySampleIntervalMs,
                            settings.cpuSampleIntervalMs,
                        ),
                    )
                }
            }
    }

    /**
     * Stop performance monitoring.
     * Also clears resource providers to prevent memory leaks during abnormal termination.
     */
    fun stop() {
        monitoringJob?.cancel()
        monitoringJob = null
        // Cancel scope to release all coroutines and prevent memory leaks during hot reloads
        scope.coroutineContext[Job]?.cancel()
        clearResourceProviders()
        logger.debug(LogCategory.SYSTEM, "Stopped performance monitor")
    }

    /**
     * Register resource count providers from BossApp.
     * Call clearResourceProviders() on disposal to prevent memory leaks.
     */
    fun registerResourceProviders(
        browserTabs: () -> Int,
        terminals: () -> Int,
        editorTabs: () -> Int,
        panels: () -> Int,
        windows: () -> Int,
    ) {
        browserTabCountProvider = browserTabs
        terminalCountProvider = terminals
        editorTabCountProvider = editorTabs
        panelCountProvider = panels
        windowCountProvider = windows
    }

    /**
     * Clear resource providers to prevent memory leaks.
     * Should be called when BossApp is disposed.
     */
    fun clearResourceProviders() {
        browserTabCountProvider = null
        terminalCountProvider = null
        editorTabCountProvider = null
        panelCountProvider = null
        windowCountProvider = null
    }

    private fun collectMemoryPools(): List<MemoryPoolInfo> =
        memoryPoolMXBeans.map { pool ->
            val usage = pool.usage
            MemoryPoolInfo(
                name = pool.name,
                type = pool.type.name,
                usedBytes = usage?.used ?: 0L,
                maxBytes = usage?.max ?: -1L,
                committedBytes = usage?.committed ?: 0L,
            )
        }

    private fun collectMemoryMetrics(memoryPools: List<MemoryPoolInfo> = collectMemoryPools()): MemoryMetrics {
        val heapUsage = memoryMXBean.heapMemoryUsage
        val nonHeapUsage = memoryMXBean.nonHeapMemoryUsage

        // Both of these are cached behind their own TTLs (ProcessFootprint.CACHE_TTL_MS and
        // SystemMemory.CACHE_TTL_MS), so sampling them on every memory tick does not spawn a
        // subprocess on every memory tick. Zero from either means "unreadable"; MemoryMetrics
        // maps that to footprintKnown = false and systemAvailableFraction = null rather than to
        // a number, and the indicator falls back to the heap reading.
        val footprint = ProcessFootprint.current()

        return MemoryMetrics(
            heapUsedBytes = heapUsage.used,
            heapMaxBytes = heapUsage.max,
            heapCommittedBytes = heapUsage.committed,
            nonHeapUsedBytes = nonHeapUsage.used,
            nonHeapCommittedBytes = nonHeapUsage.committed,
            memoryPools = memoryPools,
            footprintBytes = footprint?.totalBytes ?: 0L,
            footprintHostBytes = footprint?.hostBytes ?: 0L,
            footprintBrowserBytes = footprint?.browserBytes ?: 0L,
            footprintPluginBytes = footprint?.pluginBytes ?: 0L,
            systemAvailableBytes = SystemMemory.availableBytes(),
            systemTotalBytes = SystemMemory.totalPhysicalBytes(),
        )
    }

    private fun collectCpuMetrics(): CpuMetrics {
        val processLoad = sunOSBean?.processCpuLoad ?: -1.0
        val systemLoad = sunOSBean?.cpuLoad ?: osMXBean.systemLoadAverage

        // Top threads refresh on the sampler's slower cadence, not every CPU tick.
        val threads = threadSampler.sample()

        return CpuMetrics(
            processLoad = if (processLoad >= 0) processLoad else 0.0,
            systemLoad = if (systemLoad >= 0) systemLoad else 0.0,
            availableProcessors = osMXBean.availableProcessors,
            activeThreadCount = threadMXBean.threadCount,
            threads = threads,
        )
    }

    private fun collectGcMetrics(): GcMetrics {
        // Create a map of last GC info by collector name
        val lastGcInfoMap =
            sunGcBeans.associate { gc ->
                val gcInfo = gc.lastGcInfo
                gc.name to
                    gcInfo?.let { info ->
                        val memoryBefore = info.memoryUsageBeforeGc.values.sumOf { it.used }
                        val memoryAfter = info.memoryUsageAfterGc.values.sumOf { it.used }
                        LastGcInfo(
                            startTime = info.startTime,
                            durationMs = info.duration,
                            memoryBeforeBytes = memoryBefore,
                            memoryAfterBytes = memoryAfter,
                        )
                    }
            }

        val collectors =
            gcMXBeans.map { gc ->
                GcCollectorInfo(
                    name = gc.name,
                    collectionCount = gc.collectionCount,
                    collectionTimeMs = gc.collectionTime,
                    lastGcInfo = lastGcInfoMap[gc.name],
                )
            }

        val totalCount = collectors.sumOf { it.collectionCount }
        val totalTime = collectors.sumOf { it.collectionTimeMs }
        val gcTimeSinceLastSample = totalTime - lastGcTime

        lastGcTime = totalTime

        return GcMetrics(
            collectionCount = totalCount,
            collectionTimeMs = totalTime,
            gcTimeSinceLastSampleMs = gcTimeSinceLastSample,
            gcCollectors = collectors,
        )
    }

    private fun collectResourceMetrics(): ResourceMetrics =
        ResourceMetrics(
            browserTabCount = safeInvoke(browserTabCountProvider) { 0 },
            terminalCount = safeInvoke(terminalCountProvider) { 0 },
            editorTabCount = safeInvoke(editorTabCountProvider) { 0 },
            panelCount = safeInvoke(panelCountProvider) { 0 },
            windowCount = safeInvoke(windowCountProvider) { 0 },
        )

    /**
     * Safely invoke a provider function, catching any exceptions.
     * This prevents concurrent modification or other errors from crashing the monitoring loop.
     */
    private inline fun <T> safeInvoke(
        noinline provider: (() -> T)?,
        default: () -> T,
    ): T =
        try {
            provider?.invoke() ?: default()
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Provider error", error = e)
            default()
        }

    /**
     * Check if there's a significant change between snapshots to avoid unnecessary updates.
     * Thresholds: memory 1MB, CPU 1%, GC count change, resource count change
     */
    private fun hasSignificantChange(
        old: PerformanceSnapshot,
        new: PerformanceSnapshot,
    ): Boolean {
        // Memory: 1MB threshold
        val memoryDelta = kotlin.math.abs(old.memory.heapUsedBytes - new.memory.heapUsedBytes)
        if (memoryDelta > 1024 * 1024) return true

        // CPU: 1% threshold
        val cpuDelta = kotlin.math.abs(old.cpu.processLoadPercent - new.cpu.processLoadPercent)
        if (cpuDelta > 1.0f) return true

        // GC count changed
        if (old.gc.collectionCount != new.gc.collectionCount) return true

        // Resource counts changed
        if (old.resources != new.resources) return true

        return false
    }

    /**
     * Force garbage collection (for debugging only)
     */
    fun requestGC() {
        System.gc()
        logger.debug(LogCategory.SYSTEM, "GC requested")
    }

    /**
     * Export metrics history to a JSON file.
     * Returns Result with file path on success, or error on failure.
     */
    suspend fun exportMetrics(): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val exportFile = BossDirectories.resolve(exportFileName(LocalDateTime.now()))
                exportFile.parentFile?.mkdirs()

                val historyData = _history.value
                if (historyData.isEmpty()) {
                    return@withContext Result.failure(IllegalStateException("No metrics data to export"))
                }

                val content =
                    json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(PerformanceSnapshot.serializer()),
                        historyData,
                    )
                exportFile.writeText(content)

                Result.success(exportFile.absolutePath)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Name for a metrics export taken at [time]. Formatted with [Locale.ROOT] so the name is
     * the same on every machine: the JVM default locale is not safe here - a Thai-Buddhist
     * locale renders the year as 2568, and Arabic or Persian locales render the digits in
     * their own script, so the file lands on disk with non-ASCII characters in its name.
     */
    internal fun exportFileName(time: LocalDateTime): String = "performance-export-${EXPORT_STAMP.format(time)}.json"

    private val EXPORT_STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss", Locale.ROOT)
}
