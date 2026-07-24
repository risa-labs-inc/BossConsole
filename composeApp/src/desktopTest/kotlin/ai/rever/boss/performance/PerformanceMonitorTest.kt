package ai.rever.boss.performance

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for Performance Monitoring functionality.
 *
 * Tests cover:
 * - Settings validation (threshold clamping)
 * - Health status calculation from snapshots
 * - Resource provider registration/cleanup lifecycle
 */
class PerformanceMonitorTest {
    @BeforeEach
    fun setup() {
        // Ensure clean state before each test
        PerformanceMonitor.clearResourceProviders()
    }

    @AfterEach
    fun teardown() {
        // Clean up after each test
        PerformanceMonitor.stop()
        PerformanceMonitor.clearResourceProviders()
    }

    // ==================== SETTINGS VALIDATION TESTS ====================

    @Test
    fun `test settings validation clamps memory warning threshold`() {
        val settings = PerformanceSettings(memoryWarningThresholdPercent = 150)
        val validated = settings.validated()
        assertEquals(100, validated.memoryWarningThresholdPercent, "Should clamp to 100 max")

        val settingsLow = PerformanceSettings(memoryWarningThresholdPercent = -10)
        val validatedLow = settingsLow.validated()
        assertEquals(1, validatedLow.memoryWarningThresholdPercent, "Should clamp to 1 min")
    }

    @Test
    fun `test settings validation clamps memory critical threshold`() {
        val settings = PerformanceSettings(memoryCriticalThresholdPercent = 200)
        val validated = settings.validated()
        assertEquals(100, validated.memoryCriticalThresholdPercent, "Should clamp to 100 max")
    }

    @Test
    fun `test settings validation clamps cpu warning threshold`() {
        val settings = PerformanceSettings(cpuWarningThresholdPercent = -50)
        val validated = settings.validated()
        assertEquals(1, validated.cpuWarningThresholdPercent, "Should clamp to 1 min")
    }

    @Test
    fun `test settings validation clamps cpu critical threshold`() {
        val settings = PerformanceSettings(cpuCriticalThresholdPercent = 999)
        val validated = settings.validated()
        assertEquals(100, validated.cpuCriticalThresholdPercent, "Should clamp to 100 max")
    }

    @Test
    fun `test settings validation clamps sample intervals`() {
        val settings =
            PerformanceSettings(
                memorySampleIntervalMs = 10, // Too low
                cpuSampleIntervalMs = 50, // Too low
                resourceSampleIntervalMs = 0, // Too low
                gcSampleIntervalMs = -100, // Negative
            )
        val validated = settings.validated()

        assertEquals(100, validated.memorySampleIntervalMs, "Should clamp to 100ms min")
        assertEquals(100, validated.cpuSampleIntervalMs, "Should clamp to 100ms min")
        assertEquals(100, validated.resourceSampleIntervalMs, "Should clamp to 100ms min")
        assertEquals(100, validated.gcSampleIntervalMs, "Should clamp to 100ms min")
    }

    @Test
    fun `test settings validation clamps history retention`() {
        val settingsLow = PerformanceSettings(historyRetentionMinutes = 0)
        val validatedLow = settingsLow.validated()
        assertEquals(1, validatedLow.historyRetentionMinutes, "Should clamp to 1 minute min")

        val settingsHigh = PerformanceSettings(historyRetentionMinutes = 10000)
        val validatedHigh = settingsHigh.validated()
        assertEquals(180, validatedHigh.historyRetentionMinutes, "Should clamp to 180 minutes (3h) max")
    }

    @Test
    fun `test default settings are valid`() {
        val defaultSettings = PerformanceSettings()
        val validated = defaultSettings.validated()

        // Default values should not change after validation
        assertEquals(defaultSettings, validated, "Default settings should already be valid")
    }

    // ==================== HEALTH STATUS CALCULATION TESTS ====================

    @Test
    fun `test health status GOOD when below warning thresholds`() {
        val snapshot = createSnapshot(memoryUsagePercent = 50f, cpuUsagePercent = 40f)
        val settings =
            PerformanceSettings(
                memoryWarningThresholdPercent = 75,
                memoryCriticalThresholdPercent = 90,
                cpuWarningThresholdPercent = 70,
                cpuCriticalThresholdPercent = 90,
            )

        val health = PerformanceHealth.fromSnapshot(snapshot, settings)

        assertEquals(HealthStatus.GOOD, health.memoryStatus, "Memory should be GOOD")
        assertEquals(HealthStatus.GOOD, health.cpuStatus, "CPU should be GOOD")
        assertEquals(HealthStatus.GOOD, health.overall, "Overall should be GOOD")
    }

    @Test
    fun `test health status WARNING when memory exceeds warning threshold`() {
        val snapshot = createSnapshot(memoryUsagePercent = 80f, cpuUsagePercent = 40f)
        val settings =
            PerformanceSettings(
                memoryWarningThresholdPercent = 75,
                memoryCriticalThresholdPercent = 90,
            )

        val health = PerformanceHealth.fromSnapshot(snapshot, settings)

        assertEquals(HealthStatus.WARNING, health.memoryStatus, "Memory should be WARNING")
        assertEquals(HealthStatus.WARNING, health.overall, "Overall should be WARNING")
    }

    @Test
    fun `test health status CRITICAL when memory exceeds critical threshold`() {
        val snapshot = createSnapshot(memoryUsagePercent = 95f, cpuUsagePercent = 40f)
        val settings =
            PerformanceSettings(
                memoryCriticalThresholdPercent = 90,
            )

        val health = PerformanceHealth.fromSnapshot(snapshot, settings)

        assertEquals(HealthStatus.CRITICAL, health.memoryStatus, "Memory should be CRITICAL")
        assertEquals(HealthStatus.CRITICAL, health.overall, "Overall should be CRITICAL")
    }

    @Test
    fun `test health status WARNING when CPU exceeds warning threshold`() {
        val snapshot = createSnapshot(memoryUsagePercent = 50f, cpuUsagePercent = 75f)
        val settings =
            PerformanceSettings(
                cpuWarningThresholdPercent = 70,
                cpuCriticalThresholdPercent = 90,
            )

        val health = PerformanceHealth.fromSnapshot(snapshot, settings)

        assertEquals(HealthStatus.WARNING, health.cpuStatus, "CPU should be WARNING")
        assertEquals(HealthStatus.WARNING, health.overall, "Overall should be WARNING")
    }

    @Test
    fun `test health status CRITICAL when CPU exceeds critical threshold`() {
        val snapshot = createSnapshot(memoryUsagePercent = 50f, cpuUsagePercent = 95f)
        val settings =
            PerformanceSettings(
                cpuCriticalThresholdPercent = 90,
            )

        val health = PerformanceHealth.fromSnapshot(snapshot, settings)

        assertEquals(HealthStatus.CRITICAL, health.cpuStatus, "CPU should be CRITICAL")
        assertEquals(HealthStatus.CRITICAL, health.overall, "Overall should be CRITICAL")
    }

    @Test
    fun `test overall status is CRITICAL if any component is CRITICAL`() {
        // Memory CRITICAL, CPU GOOD
        val snapshot1 = createSnapshot(memoryUsagePercent = 95f, cpuUsagePercent = 30f)
        val settings =
            PerformanceSettings(
                memoryCriticalThresholdPercent = 90,
                cpuCriticalThresholdPercent = 90,
            )

        val health1 = PerformanceHealth.fromSnapshot(snapshot1, settings)
        assertEquals(HealthStatus.CRITICAL, health1.overall, "Overall should be CRITICAL when memory is CRITICAL")

        // Memory GOOD, CPU CRITICAL
        val snapshot2 = createSnapshot(memoryUsagePercent = 30f, cpuUsagePercent = 95f)
        val health2 = PerformanceHealth.fromSnapshot(snapshot2, settings)
        assertEquals(HealthStatus.CRITICAL, health2.overall, "Overall should be CRITICAL when CPU is CRITICAL")
    }

    @Test
    fun `test overall status is WARNING if any component is WARNING and none CRITICAL`() {
        // Memory WARNING, CPU GOOD
        val snapshot = createSnapshot(memoryUsagePercent = 80f, cpuUsagePercent = 30f)
        val settings =
            PerformanceSettings(
                memoryWarningThresholdPercent = 75,
                memoryCriticalThresholdPercent = 90,
                cpuWarningThresholdPercent = 70,
                cpuCriticalThresholdPercent = 90,
            )

        val health = PerformanceHealth.fromSnapshot(snapshot, settings)
        assertEquals(HealthStatus.WARNING, health.overall, "Overall should be WARNING when memory is WARNING")
    }

    // ==================== MEMORY METRICS TESTS ====================

    @Test
    fun `test memory heap usage percent calculation`() {
        val memoryMetrics =
            MemoryMetrics(
                heapUsedBytes = 512 * 1024 * 1024L, // 512 MB
                heapMaxBytes = 1024 * 1024 * 1024L, // 1024 MB
                heapCommittedBytes = 768 * 1024 * 1024L,
                nonHeapUsedBytes = 100 * 1024 * 1024L,
                nonHeapCommittedBytes = 150 * 1024 * 1024L,
            )

        assertEquals(50f, memoryMetrics.heapUsagePercent, 0.01f, "Heap usage should be 50%")
    }

    @Test
    fun `test memory heap usage MB conversion`() {
        val memoryMetrics =
            MemoryMetrics(
                heapUsedBytes = 256 * 1024 * 1024L, // 256 MB
                heapMaxBytes = 1024 * 1024 * 1024L,
                heapCommittedBytes = 512 * 1024 * 1024L,
                nonHeapUsedBytes = 64 * 1024 * 1024L,
                nonHeapCommittedBytes = 100 * 1024 * 1024L,
            )

        assertEquals(256f, memoryMetrics.heapUsedMB, 0.1f, "Heap used should be 256 MB")
        assertEquals(1024f, memoryMetrics.heapMaxMB, 0.1f, "Heap max should be 1024 MB")
    }

    @Test
    fun `test memory heap usage percent with zero max`() {
        val memoryMetrics =
            MemoryMetrics(
                heapUsedBytes = 256 * 1024 * 1024L,
                heapMaxBytes = 0, // Edge case: max is 0
                heapCommittedBytes = 512 * 1024 * 1024L,
                nonHeapUsedBytes = 0,
                nonHeapCommittedBytes = 0,
            )

        assertEquals(0f, memoryMetrics.heapUsagePercent, "Should return 0% when max is 0")
    }

    // ==================== CPU METRICS TESTS ====================

    @Test
    fun `test CPU load percent calculation`() {
        val cpuMetrics =
            CpuMetrics(
                processLoad = 0.45, // 45%
                systemLoad = 0.75, // 75%
                availableProcessors = 8,
                activeThreadCount = 50,
            )

        assertEquals(45f, cpuMetrics.processLoadPercent, 0.01f, "Process load should be 45%")
        assertEquals(75f, cpuMetrics.systemLoadPercent, 0.01f, "System load should be 75%")
    }

    // ==================== MEMORY POOL INFO TESTS ====================

    @Test
    fun `test memory pool usage percent with max bytes`() {
        val poolInfo =
            MemoryPoolInfo(
                name = "G1 Eden Space",
                type = "HEAP",
                usedBytes = 100 * 1024 * 1024L,
                maxBytes = 200 * 1024 * 1024L,
                committedBytes = 150 * 1024 * 1024L,
            )

        assertEquals(50f, poolInfo.usagePercent, 0.01f, "Usage should be 50% of max")
    }

    @Test
    fun `test memory pool usage percent falls back to committed when max is negative`() {
        val poolInfo =
            MemoryPoolInfo(
                name = "Metaspace",
                type = "NON_HEAP",
                usedBytes = 50 * 1024 * 1024L,
                maxBytes = -1, // -1 indicates no max
                committedBytes = 100 * 1024 * 1024L,
            )

        assertEquals(50f, poolInfo.usagePercent, 0.01f, "Usage should be 50% of committed when max is -1")
    }

    @Test
    fun `test memory pool max MB falls back to committed when max is negative`() {
        val poolInfo =
            MemoryPoolInfo(
                name = "Metaspace",
                type = "NON_HEAP",
                usedBytes = 50 * 1024 * 1024L,
                maxBytes = -1,
                committedBytes = 128 * 1024 * 1024L,
            )

        assertEquals(128f, poolInfo.maxMB, 0.1f, "Max MB should fall back to committed MB")
    }

    // ==================== LAST GC INFO TESTS ====================

    @Test
    fun `test last GC memory reclaimed calculation`() {
        val gcInfo =
            LastGcInfo(
                startTime = System.currentTimeMillis(),
                durationMs = 50,
                memoryBeforeBytes = 500 * 1024 * 1024L,
                memoryAfterBytes = 200 * 1024 * 1024L,
            )

        assertEquals(300 * 1024 * 1024L, gcInfo.memoryReclaimedBytes, "Should reclaim 300 MB")
        assertEquals(300f, gcInfo.memoryReclaimedMB, 0.1f, "Should reclaim 300 MB")
    }

    // ==================== HELPER FUNCTIONS ====================

    /**
     * Creates a test snapshot with configurable memory and CPU usage.
     */
    private fun createSnapshot(
        memoryUsagePercent: Float,
        cpuUsagePercent: Float,
    ): PerformanceSnapshot {
        // Calculate heap bytes to achieve desired usage percent
        val maxHeap = 1024 * 1024 * 1024L // 1 GB
        val usedHeap = (maxHeap * memoryUsagePercent / 100).toLong()

        return PerformanceSnapshot(
            timestamp = System.currentTimeMillis(),
            memory =
                MemoryMetrics(
                    heapUsedBytes = usedHeap,
                    heapMaxBytes = maxHeap,
                    heapCommittedBytes = maxHeap,
                    nonHeapUsedBytes = 100 * 1024 * 1024L,
                    nonHeapCommittedBytes = 150 * 1024 * 1024L,
                ),
            cpu =
                CpuMetrics(
                    processLoad = cpuUsagePercent / 100.0,
                    systemLoad = 0.5,
                    availableProcessors = 8,
                    activeThreadCount = 50,
                ),
            gc =
                GcMetrics(
                    collectionCount = 10,
                    collectionTimeMs = 500,
                    gcTimeSinceLastSampleMs = 50,
                    gcCollectors = emptyList(),
                ),
            resources =
                ResourceMetrics(
                    browserTabCount = 5,
                    terminalCount = 2,
                    editorTabCount = 10,
                    panelCount = 3,
                    windowCount = 1,
                ),
        )
    }
}
