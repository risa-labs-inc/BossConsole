package ai.rever.boss.plugin.ipc

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.ChildProcessData
import ai.rever.boss.plugin.api.PerformanceDataProvider
import ai.rever.boss.plugin.api.PerformanceSettingsData
import ai.rever.boss.plugin.api.PerformanceSnapshotData
import io.grpc.ManagedChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * IPC proxy implementation of PerformanceDataProvider.
 *
 * Runs in the kernel process and delegates all calls to the
 * performance service via gRPC. Watches snapshot, history, and settings
 * streams with automatic reconnection.
 */
class PerformanceDataProviderProxy(
    channel: ManagedChannel,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : PerformanceDataProvider {

    private val stub = PerformanceServiceGrpcKt.PerformanceServiceCoroutineStub(channel)

    private val _currentSnapshot = MutableStateFlow<PerformanceSnapshotData?>(null)
    override val currentSnapshot: StateFlow<PerformanceSnapshotData?> = _currentSnapshot.asStateFlow()

    private val _history = MutableStateFlow<List<PerformanceSnapshotData>>(emptyList())
    override val history: StateFlow<List<PerformanceSnapshotData>> = _history.asStateFlow()

    private val _settings = MutableStateFlow(PerformanceSettingsData())
    override val settings: StateFlow<PerformanceSettingsData> = _settings.asStateFlow()

    init {
        scope.launch { watchSnapshot() }
        scope.launch { watchHistory() }
        scope.launch { watchSettings() }
    }

    private suspend fun watchSnapshot() {
        var delayMs = 1_000L
        while (scope.isActive) {
            try {
                stub.watchCurrentSnapshot(Empty.getDefaultInstance()).collect { proto ->
                    _currentSnapshot.value = proto.toData()
                }
                delayMs = 1_000L
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { delay(delayMs); delayMs = (delayMs * 2).coerceAtMost(30_000L) }
        }
    }

    private suspend fun watchHistory() {
        var delayMs = 1_000L
        while (scope.isActive) {
            try {
                stub.watchHistory(Empty.getDefaultInstance()).collect { response ->
                    _history.value = response.snapshotsList.map { it.toData() }
                }
                delayMs = 1_000L
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { delay(delayMs); delayMs = (delayMs * 2).coerceAtMost(30_000L) }
        }
    }

    private suspend fun watchSettings() {
        var delayMs = 1_000L
        while (scope.isActive) {
            try {
                stub.watchSettings(Empty.getDefaultInstance()).collect { proto ->
                    _settings.value = proto.toSettingsData()
                }
                delayMs = 1_000L
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { delay(delayMs); delayMs = (delayMs * 2).coerceAtMost(30_000L) }
        }
    }

    override fun requestGC() {
        scope.launch {
            try { stub.requestGC(Empty.getDefaultInstance()) } catch (_: Exception) {}
        }
    }

    override suspend fun exportMetrics(): Result<String> = try {
        val response = stub.exportMetrics(Empty.getDefaultInstance())
        if (response.success) Result.success(response.filePath)
        else Result.failure(Exception(response.errorMessage))
    } catch (e: Exception) { Result.failure(e) }

    override suspend fun updateSettings(settings: PerformanceSettingsData) {
        try {
            stub.updateSettings(settings.toProto())
        } catch (_: Exception) {}
    }

    private fun PerformanceSnapshotProto.toData() = PerformanceSnapshotData(
        timestamp = timestamp,
        heapUsedBytes = heapUsedBytes,
        heapMaxBytes = heapMaxBytes,
        heapCommittedBytes = heapCommittedBytes,
        heapUsagePercent = heapUsagePercent,
        nonHeapUsedBytes = nonHeapUsedBytes,
        nonHeapCommittedBytes = nonHeapCommittedBytes,
        processLoadPercent = processLoadPercent,
        systemLoadPercent = systemLoadPercent,
        activeThreadCount = activeThreadCount,
        availableProcessors = availableProcessors,
        gcCollectionCount = gcCollectionCount,
        gcCollectionTimeMs = gcCollectionTimeMs,
        browserTabCount = browserTabCount,
        terminalCount = terminalCount,
        editorTabCount = editorTabCount,
        panelCount = panelCount,
        windowCount = windowCount,
        childProcesses = childProcessesList.map { it.toData() },
    )

    private fun ChildProcessProto.toData() = ChildProcessData(
        processId = processId,
        pluginId = pluginId,
        displayName = displayName,
        pid = pid,
        state = state,
        heapUsedBytes = heapUsedBytes,
        heapMaxBytes = heapMaxBytes,
        activeThreads = activeThreads,
        uptimeMs = uptimeMs,
        restartCount = restartCount,
        bridgeConnected = bridgeConnected,
    )

    private fun PerformanceSettingsProto.toSettingsData() = PerformanceSettingsData(
        enabled = enabled,
        showIndicator = showIndicator,
        memoryWarningThresholdPercent = memoryWarningThresholdPercent,
        memoryCriticalThresholdPercent = memoryCriticalThresholdPercent,
        cpuWarningThresholdPercent = cpuWarningThresholdPercent,
        cpuCriticalThresholdPercent = cpuCriticalThresholdPercent,
        memorySampleIntervalMs = memorySampleIntervalMs,
        cpuSampleIntervalMs = cpuSampleIntervalMs,
        historyRetentionMinutes = historyRetentionMinutes,
        pluginJvmHeapMb = pluginJvmHeapMb,
        pluginJvmInitialHeapMb = pluginJvmInitialHeapMb,
    )

    private fun PerformanceSettingsData.toProto(): PerformanceSettingsProto =
        PerformanceSettingsProto.newBuilder()
            .setEnabled(enabled)
            .setShowIndicator(showIndicator)
            .setMemoryWarningThresholdPercent(memoryWarningThresholdPercent)
            .setMemoryCriticalThresholdPercent(memoryCriticalThresholdPercent)
            .setCpuWarningThresholdPercent(cpuWarningThresholdPercent)
            .setCpuCriticalThresholdPercent(cpuCriticalThresholdPercent)
            .setMemorySampleIntervalMs(memorySampleIntervalMs)
            .setCpuSampleIntervalMs(cpuSampleIntervalMs)
            .setHistoryRetentionMinutes(historyRetentionMinutes)
            .setPluginJvmHeapMb(pluginJvmHeapMb)
            .setPluginJvmInitialHeapMb(pluginJvmInitialHeapMb)
            .build()
}
