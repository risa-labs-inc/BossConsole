package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.ChildProcessData
import ai.rever.boss.plugin.api.PerformanceDataProvider
import ai.rever.boss.plugin.api.PerformanceSettingsData
import ai.rever.boss.plugin.api.PerformanceSnapshotData
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Kernel-side bridge for `PerformanceService`.
 *
 * **Every call requires a verified caller identity (BossConsole#53)**, the same requirement and
 * helper shape introduced for the Secret Service in PR #505. The watchers expose the host's live
 * heap, thread and window telemetry, and requestGC/updateSettings mutate the running process,
 * so a request with no credential to attribute it to is refused rather than honoured. Streaming
 * RPCs revalidate the caller on every emission so a mid-stream revocation is honoured, the same
 * shape [LogServiceBridge] uses.
 */
// One method per RPC the generated service base class declares, plus identity and mapping helpers.
@Suppress("TooManyFunctions")
class PerformanceServiceBridge(
    private val provider: PerformanceDataProvider,
) : PerformanceServiceGrpcKt.PerformanceServiceCoroutineImplBase() {
    override fun watchCurrentSnapshot(request: Empty): Flow<PerformanceSnapshotProto> {
        val currentIdentity = ProcessIdentityInterceptor.CURRENT_IDENTITY.get()
        return flow {
            val caller =
                currentIdentity?.invoke()
                    ?: refuseIdentity("watchCurrentSnapshot")
            provider.currentSnapshot.collect { snapshot ->
                if (currentIdentity.invoke() != caller) {
                    refuseIdentity("watchCurrentSnapshot")
                }
                if (snapshot != null) {
                    emit(snapshot.toProto())
                }
            }
        }
    }

    override fun watchHistory(request: Empty): Flow<PerformanceHistoryResponse> {
        val currentIdentity = ProcessIdentityInterceptor.CURRENT_IDENTITY.get()
        return flow {
            val caller =
                currentIdentity?.invoke()
                    ?: refuseIdentity("watchHistory")
            provider.history.collect { history ->
                if (currentIdentity.invoke() != caller) {
                    refuseIdentity("watchHistory")
                }
                emit(
                    PerformanceHistoryResponse
                        .newBuilder()
                        .addAllSnapshots(history.map { it.toProto() })
                        .build(),
                )
            }
        }
    }

    override fun watchSettings(request: Empty): Flow<PerformanceSettingsProto> {
        val currentIdentity = ProcessIdentityInterceptor.CURRENT_IDENTITY.get()
        return flow {
            val caller =
                currentIdentity?.invoke()
                    ?: refuseIdentity("watchSettings")
            provider.settings.collect { settings ->
                if (currentIdentity.invoke() != caller) {
                    refuseIdentity("watchSettings")
                }
                emit(settings.toProto())
            }
        }
    }

    override suspend fun requestGC(request: Empty): Empty {
        authenticatedCallerOrRefuse("requestGC")
        provider.requestGC()
        return Empty.getDefaultInstance()
    }

    override suspend fun exportMetrics(request: Empty): ExportMetricsResponse {
        authenticatedCallerOrRefuse("exportMetrics")
        val result = provider.exportMetrics()
        return result.fold(
            onSuccess = { path ->
                ExportMetricsResponse
                    .newBuilder()
                    .setSuccess(true)
                    .setFilePath(path)
                    .build()
            },
            onFailure = { error ->
                ExportMetricsResponse
                    .newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(error.message ?: "Export failed")
                    .build()
            },
        )
    }

    override suspend fun updateSettings(request: PerformanceSettingsProto): Empty {
        authenticatedCallerOrRefuse("updateSettings")
        provider.updateSettings(request.toData())
        return Empty.getDefaultInstance()
    }

    /**
     * Unary RPCs only: the verified identity, or a thrown `PERMISSION_DENIED` when there is none.
     * Streaming RPCs revalidate the caller on every emission with CURRENT_IDENTITY instead.
     *
     * Mirrors the helper introduced by PR #505 (BossConsole#53) - fails closed rather than let a
     * request with no credential fall through to [provider] with nothing to attribute it to.
     */
    private fun authenticatedCallerOrRefuse(rpc: String): String =
        ProcessIdentityInterceptor.AUTHENTICATED_PROCESS_ID.get() ?: refuseIdentity(rpc)

    private fun refuseIdentity(rpc: String): Nothing {
        logger.warn(
            LogCategory.AUTH,
            "Refused $rpc: no current verified process identity on this call",
            mapOf("rpc" to rpc),
        )
        throw StatusException(Status.PERMISSION_DENIED.withDescription(NO_IDENTITY))
    }

    private fun PerformanceSnapshotData.toProto(): PerformanceSnapshotProto =
        PerformanceSnapshotProto
            .newBuilder()
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
        ChildProcessProto
            .newBuilder()
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
        PerformanceSettingsProto
            .newBuilder()
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

    private companion object {
        val logger = BossLogger.forComponent("PerformanceServiceBridge")

        const val NO_IDENTITY = "This call presented no verified process identity"
    }
}
