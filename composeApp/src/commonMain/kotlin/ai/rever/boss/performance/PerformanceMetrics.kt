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
) {
    val heapUsagePercent: Float
        get() = if (heapMaxBytes > 0) (heapUsedBytes.toFloat() / heapMaxBytes) * 100f else 0f

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

            val memoryStatus =
                when {
                    memoryPercent >= settings.memoryCriticalThresholdPercent -> HealthStatus.CRITICAL
                    memoryPercent >= settings.memoryWarningThresholdPercent -> HealthStatus.WARNING
                    else -> HealthStatus.GOOD
                }

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
