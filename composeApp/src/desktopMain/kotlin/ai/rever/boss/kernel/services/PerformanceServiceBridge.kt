package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.ChildProcessData
import ai.rever.boss.plugin.api.PerformanceDataProvider
import ai.rever.boss.plugin.api.PerformanceSettingsData
import ai.rever.boss.plugin.api.PerformanceSnapshotData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

class PerformanceServiceBridge(
    private val provider: PerformanceDataProvider,
) : PerformanceServiceGrpcKt.PerformanceServiceCoroutineImplBase() {

    override fun watchCurrentSnapshot(request: Empty): Flow<PerformanceSnapshotProto> = flow {
        provider.currentSnapshot.collect { snapshot ->
            if (snapshot != null) {
                emit(snapshot.toProto())
            }
        }
    }

    override fun watchHistory(request: Empty): Flow<PerformanceHistoryResponse> = flow {
        provider.history.collect { history ->
            emit(
                PerformanceHistoryResponse.newBuilder()
                    .addAllSnapshots(history.map { it.toProto() })
                    .build()
            )
        }
    }

    override fun watchSettings(request: Empty): Flow<PerformanceSettingsProto> = flow {
        provider.settings.collect { settings ->
            emit(settings.toProto())
        }
    }

    override suspend fun requestGC(request: Empty): Empty {
        provider.requestGC()
        return Empty.getDefaultInstance()
    }

    override suspend fun exportMetrics(request: Empty): ExportMetricsResponse {
        val result = provider.exportMetrics()
        return result.fold(
            onSuccess = { path ->
                ExportMetricsResponse.newBuilder()
                    .setSuccess(true)
                    .setFilePath(path)
                    .build()
            },
            onFailure = { error ->
                ExportMetricsResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(error.message ?: "Export failed")
                    .build()
            }
        )
    }

    override suspend fun updateSettings(request: PerformanceSettingsProto): Empty {
        provider.updateSettings(request.toData())
        return Empty.getDefaultInstance()
    }

    private fun PerformanceSnapshotData.toProto(): PerformanceSnapshotProto =
        PerformanceSnapshotProto.newBuilder()
            .setTimestamp(timestamp)
            .setHeapUsedBytes(heapUsedBytes)
            .setHeapMaxBytes(heapMaxBytes)
            .setHeapCommittedBytes(heapCommittedBytes)
            .setHeapUsagePercent(heapUsagePercent)
            .setNonHeapUsedBytes(nonHeapUsedBytes)
            .setNonHeapCommittedBytes(nonHeapCommittedBytes)
            .setProcessLoadPercent(processLoadPercent)
            .setSystemLoadPercent(systemLoadPercent)
            .setActiveThreadCount(activeThreadCount)
            .setAvailableProcessors(availableProcessors)
            .setGcCollectionCount(gcCollectionCount)
            .setGcCollectionTimeMs(gcCollectionTimeMs)
            .setBrowserTabCount(browserTabCount)
            .setTerminalCount(terminalCount)
            .setEditorTabCount(editorTabCount)
            .setPanelCount(panelCount)
            .setWindowCount(windowCount)
            .addAllChildProcesses(childProcesses.map { it.toProto() })
            .build()

    private fun ChildProcessData.toProto(): ChildProcessProto =
        ChildProcessProto.newBuilder()
            .setProcessId(processId)
            .setPluginId(pluginId)
            .setDisplayName(displayName)
            .setPid(pid)
            .setState(state)
            .setHeapUsedBytes(heapUsedBytes)
            .setHeapMaxBytes(heapMaxBytes)
            .setActiveThreads(activeThreads)
            .setUptimeMs(uptimeMs)
            .setRestartCount(restartCount)
            .setBridgeConnected(bridgeConnected)
            .build()

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

    private fun PerformanceSettingsProto.toData(): PerformanceSettingsData =
        PerformanceSettingsData(
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
}
