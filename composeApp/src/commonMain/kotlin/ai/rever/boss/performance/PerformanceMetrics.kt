package ai.rever.boss.performance

import kotlinx.serialization.Serializable

/**
 * Performance settings for monitoring configuration.
 * Persisted to ~/.boss/performance-settings.json
 */
@Serializable
data class PerformanceSettings(
    val enabled: Boolean = true,
    val showIndicator: Boolean = true,
    val memoryWarningThresholdPercent: Int = 75,
    val memoryCriticalThresholdPercent: Int = 90,
    val cpuWarningThresholdPercent: Int = 70,
    val cpuCriticalThresholdPercent: Int = 90,
    val memorySampleIntervalMs: Long = 1000,
    val cpuSampleIntervalMs: Long = 2000,
    val resourceSampleIntervalMs: Long = 5000,
    val gcSampleIntervalMs: Long = 10000,
    val historyRetentionMinutes: Int = 30,
    /** Max heap per plugin child JVM in MB. Applied on next plugin restart. */
    val pluginJvmHeapMb: Int = DEFAULT_PLUGIN_HEAP_MB,
    /** Initial heap per plugin child JVM in MB. */
    val pluginJvmInitialHeapMb: Int = 64,
) {
    companion object {
        /** Auto-detect: 2% of system RAM per plugin, clamped 256–4096 MB. */
        val DEFAULT_PLUGIN_HEAP_MB: Int =
            run {
                val totalMemMb =
                    try {
                        val osBean =
                            java.lang.management.ManagementFactory
                                .getOperatingSystemMXBean()
                        val method = osBean.javaClass.getMethod("getTotalPhysicalMemorySize")
                        method.isAccessible = true
                        (method.invoke(osBean) as Long) / (1024 * 1024)
                    } catch (_: Exception) {
                        16_384L // fallback 16 GB
                    }
                (totalMemMb / 50).toInt().coerceIn(256, 4096)
            }
    }

    /**
     * Returns a validated copy of settings with values clamped to valid ranges.
     */
    fun validated(): PerformanceSettings =
        copy(
            memoryWarningThresholdPercent = memoryWarningThresholdPercent.coerceIn(1, 100),
            memoryCriticalThresholdPercent = memoryCriticalThresholdPercent.coerceIn(1, 100),
            cpuWarningThresholdPercent = cpuWarningThresholdPercent.coerceIn(1, 100),
            cpuCriticalThresholdPercent = cpuCriticalThresholdPercent.coerceIn(1, 100),
            memorySampleIntervalMs = memorySampleIntervalMs.coerceAtLeast(100),
            cpuSampleIntervalMs = cpuSampleIntervalMs.coerceAtLeast(100),
            resourceSampleIntervalMs = resourceSampleIntervalMs.coerceAtLeast(100),
            gcSampleIntervalMs = gcSampleIntervalMs.coerceAtLeast(100),
            historyRetentionMinutes = historyRetentionMinutes.coerceIn(1, 180),
            pluginJvmHeapMb = pluginJvmHeapMb.coerceIn(128, 8192),
            pluginJvmInitialHeapMb = pluginJvmInitialHeapMb.coerceIn(32, pluginJvmHeapMb),
        )
}

/**
 * Current snapshot of performance metrics.
 */
@Serializable
data class PerformanceSnapshot(
    val timestamp: Long,
    val memory: MemoryMetrics,
    val cpu: CpuMetrics,
    val gc: GcMetrics,
    val resources: ResourceMetrics,
)

/**
 * Memory metrics from JVM.
 */
@Serializable
data class MemoryMetrics(
    val heapUsedBytes: Long,
    val heapMaxBytes: Long,
    val heapCommittedBytes: Long,
    val nonHeapUsedBytes: Long,
    val nonHeapCommittedBytes: Long,
    val memoryPools: List<MemoryPoolInfo> = emptyList(),
    /**
     * Physical memory held across every process BOSS owns, or 0 when it could not be read.
     *
     * 0 means **unknown**, never "none": this JVM is by definition running and holding memory,
     * so a zero here is a failed reading and callers must fall back to the heap figures rather
     * than render it. See `ProcessFootprint` for how it is attributed and how it is biased.
     *
     * All six fields below default so that an older exported snapshot still deserialises, and so
     * that a platform with no footprint reader produces a valid snapshot rather than none.
     */
    val footprintBytes: Long = 0L,
    val footprintHostBytes: Long = 0L,
    val footprintBrowserBytes: Long = 0L,
    val footprintPluginBytes: Long = 0L,
    /** Machine-wide available memory, or 0 when unreadable. See `SystemMemory.availableBytes`. */
    val systemAvailableBytes: Long = 0L,
    val systemTotalBytes: Long = 0L,
) {
    val heapUsagePercent: Float
        get() = if (heapMaxBytes > 0) (heapUsedBytes.toFloat() / heapMaxBytes) * 100f else 0f

    /** Whether the footprint reading succeeded and may be displayed. */
    val footprintKnown: Boolean
        get() = footprintBytes > 0L

    val footprintMB: Float
        get() = footprintBytes / (1024f * 1024f)

    /**
     * Free machine memory as a fraction in `0.0..1.0`, or null when either reading failed.
     *
     * Null is distinct from 0.0 deliberately and for the same reason as in
     * `SystemMemory.freeFraction`: an unreadable bean is not evidence of a full machine, and
     * treating it as one would paint the indicator red on a machine with plenty of room.
     */

    /**
     * Machine memory in use, or 0 when either reading failed.
     *
     * Derived rather than sampled, because "used" has no single definition worth arguing about:
     * this is simply total minus what `SystemMemory.availableBytes` calls available, so the pair
     * shown to the user always adds up to the total beside it. On macOS that available figure
     * counts inactive and purgeable pages as reclaimable, which is the honest answer to "could a
     * large allocation succeed" and therefore makes this the honest answer to "how much is
     * genuinely spoken for".
     */
    val systemUsedBytes: Long
        get() =
            if (systemTotalBytes <= 0L || systemAvailableBytes <= 0L) {
                0L
            } else {
                systemTotalBytes - systemAvailableBytes
            }

    val systemAvailableFraction: Double?
        get() =
            if (systemTotalBytes <= 0L || systemAvailableBytes <= 0L) {
                null
            } else {
                (systemAvailableBytes.toDouble() / systemTotalBytes.toDouble()).coerceIn(0.0, 1.0)
            }

    val heapUsedMB: Float
        get() = heapUsedBytes / (1024f * 1024f)

    val heapMaxMB: Float
        get() = heapMaxBytes / (1024f * 1024f)

    val heapCommittedMB: Float
        get() = heapCommittedBytes / (1024f * 1024f)

    val nonHeapUsedMB: Float
        get() = nonHeapUsedBytes / (1024f * 1024f)

    val nonHeapCommittedMB: Float
        get() = nonHeapCommittedBytes / (1024f * 1024f)
}

/**
 * Information about a single memory pool (Eden, Survivor, Old Gen, Metaspace, etc.)
 */
@Serializable
data class MemoryPoolInfo(
    val name: String,
    val type: String, // HEAP or NON_HEAP
    val usedBytes: Long,
    val maxBytes: Long,
    val committedBytes: Long,
) {
    val usedMB: Float
        get() = usedBytes / (1024f * 1024f)

    val maxMB: Float
        get() = if (maxBytes > 0) maxBytes / (1024f * 1024f) else committedBytes / (1024f * 1024f)

    val usagePercent: Float
        get() =
            if (maxBytes > 0) {
                (usedBytes.toFloat() / maxBytes) * 100f
            } else if (committedBytes > 0) {
                (usedBytes.toFloat() / committedBytes) * 100f
            } else {
                0f
            }
}

/**
 * CPU metrics from JVM and OS.
 */
@Serializable
data class CpuMetrics(
    val processLoad: Double, // 0.0-1.0, JVM process CPU usage
    val systemLoad: Double, // 0.0-1.0, overall system CPU usage
    val availableProcessors: Int,
    val activeThreadCount: Int,
    val threads: List<ThreadInfo> = emptyList(),
) {
    val processLoadPercent: Float
        get() = (processLoad * 100).toFloat()

    val systemLoadPercent: Float
        get() = (systemLoad * 100).toFloat()
}

/**
 * Information about a single JVM thread.
 */
@Serializable
data class ThreadInfo(
    val id: Long,
    val name: String,
    val state: String, // RUNNABLE, WAITING, BLOCKED, etc.
    val cpuTimeMs: Long, // CPU time in milliseconds
    val userTimeMs: Long, // User time in milliseconds
    val blockedCount: Long, // Times blocked
    val waitedCount: Long, // Times waited
)

/**
 * Garbage collection metrics.
 */
@Serializable
data class GcMetrics(
    val collectionCount: Long,
    val collectionTimeMs: Long,
    /** Time spent in GC since last sample (not individual GC event duration) */
    val gcTimeSinceLastSampleMs: Long,
    val gcCollectors: List<GcCollectorInfo>,
)

/**
 * Information about a single GC collector.
 */
@Serializable
data class GcCollectorInfo(
    val name: String,
    val collectionCount: Long,
    val collectionTimeMs: Long,
    val lastGcInfo: LastGcInfo? = null,
)

/**
 * Information about the last GC event for a collector.
 */
@Serializable
data class LastGcInfo(
    val startTime: Long, // Timestamp when GC started
    val durationMs: Long, // Duration of the GC
    val memoryBeforeBytes: Long,
    val memoryAfterBytes: Long,
) {
    val memoryReclaimedBytes: Long
        get() = memoryBeforeBytes - memoryAfterBytes

    val memoryReclaimedMB: Float
        get() = memoryReclaimedBytes / (1024f * 1024f)

    val memoryBeforeMB: Float
        get() = memoryBeforeBytes / (1024f * 1024f)

    val memoryAfterMB: Float
        get() = memoryAfterBytes / (1024f * 1024f)
}

/**
 * Resource counts (browser tabs, terminals, etc.).
 */
@Serializable
data class ResourceMetrics(
    val browserTabCount: Int,
    val terminalCount: Int,
    val editorTabCount: Int,
    val panelCount: Int,
    val windowCount: Int,
    val browserTabs: List<BrowserTabInfo> = emptyList(),
    val terminals: List<TerminalInfo> = emptyList(),
    val editorTabs: List<EditorTabResourceInfo> = emptyList(),
)

/**
 * Information about an open browser tab.
 */
@Serializable
data class BrowserTabInfo(
    val id: String,
    val title: String,
    val url: String,
    val isActive: Boolean = false,
)

/**
 * Information about an open terminal session.
 */
@Serializable
data class TerminalInfo(
    val id: String,
    val title: String,
    val workingDirectory: String = "",
    val isActive: Boolean = false,
)

/**
 * Information about an open editor tab.
 */
@Serializable
data class EditorTabResourceInfo(
    val id: String,
    val fileName: String,
    val filePath: String,
    val isModified: Boolean = false,
    val isActive: Boolean = false,
)

/**
 * Health status for indicators.
 */
enum class HealthStatus {
    GOOD, // Green
    WARNING, // Yellow/Orange
    CRITICAL, // Red
}

/**
 * Combined health status for status bar indicator.
 */
data class PerformanceHealth(
    val memoryStatus: HealthStatus,
    val cpuStatus: HealthStatus,
    val overall: HealthStatus,
) {
    companion object {
        fun fromSnapshot(
            snapshot: PerformanceSnapshot,
            settings: PerformanceSettings,
        ): PerformanceHealth {
            val memoryPercent = snapshot.memory.heapUsagePercent
            val cpuPercent = snapshot.cpu.processLoadPercent

            val heapStatus =
                when {
                    memoryPercent >= settings.memoryCriticalThresholdPercent -> HealthStatus.CRITICAL
                    memoryPercent >= settings.memoryWarningThresholdPercent -> HealthStatus.WARNING
                    else -> HealthStatus.GOOD
                }

            // The worst of the two, not a replacement for the heap reading. Heap exhaustion is
            // still a real way to die and dropping its signal to gain the other would trade one
            // blind spot for another; this only ever adds a reason to warn.
            val memoryStatus = maxOf(heapStatus, MemoryPressure.statusFor(snapshot.memory.systemAvailableFraction))

            val cpuStatus =
                when {
                    cpuPercent >= settings.cpuCriticalThresholdPercent -> HealthStatus.CRITICAL
                    cpuPercent >= settings.cpuWarningThresholdPercent -> HealthStatus.WARNING
                    else -> HealthStatus.GOOD
                }

            val overall =
                when {
                    memoryStatus == HealthStatus.CRITICAL || cpuStatus == HealthStatus.CRITICAL -> HealthStatus.CRITICAL
                    memoryStatus == HealthStatus.WARNING || cpuStatus == HealthStatus.WARNING -> HealthStatus.WARNING
                    else -> HealthStatus.GOOD
                }

            return PerformanceHealth(memoryStatus, cpuStatus, overall)
        }
    }
}

/**
 * When the machine itself, rather than the JVM heap, counts as under memory pressure.
 *
 * Lives here in common code so that the status-bar colour and `MemoryPressureWatchdog` cannot
 * drift apart. That they agree is the point of the feature, not an incidental tidiness: the
 * watchdog tightens the resource tier and raises an interruptive modal after pressure has been
 * sustained for a minute, and before this the user got no warning at all - the first sign of a
 * decision already taken was the dialog. Sharing the critical threshold means the indicator
 * turns red exactly when the watchdog starts its sustain clock, so the modal arrives as a
 * confirmation of something the user has been watching rather than as an ambush.
 */
object MemoryPressure {
    /**
     * At or below this fraction available, the machine is in trouble.
     *
     * **Not calibrated against a measured allocation failure**, and inherited as-is from
     * `MemoryPressureWatchdog.PRESSURE_THRESHOLD`, whose own documentation says the same. The
     * indicator is the instrument that can finally calibrate it: it puts the number in front of
     * a user on every session and records it into performance history, so the reading before a
     * real PartitionAlloc abort becomes recoverable evidence instead of something that has to be
     * reproduced deliberately.
     */
    const val CRITICAL_AVAILABLE_FRACTION = 0.12

    /**
     * Where to start warning.
     *
     * Roughly double the critical fraction, so that on a machine trending downward there is a
     * visible amber band before red rather than a single step from fine to acting.
     */
    const val WARNING_AVAILABLE_FRACTION = 0.25

    /**
     * Health implied by a free-memory fraction. A null fraction is GOOD, not CRITICAL: unknown
     * must never be read as pressure.
     */
    fun statusFor(availableFraction: Double?): HealthStatus =
        when {
            availableFraction == null -> HealthStatus.GOOD
            availableFraction <= CRITICAL_AVAILABLE_FRACTION -> HealthStatus.CRITICAL
            availableFraction <= WARNING_AVAILABLE_FRACTION -> HealthStatus.WARNING
            else -> HealthStatus.GOOD
        }
}
