package ai.rever.boss.plugin.ipc

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.LogDataProvider
import ai.rever.boss.plugin.api.LogEntryData
import ai.rever.boss.plugin.api.LogFilterData
import ai.rever.boss.plugin.api.LogSourceData
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
import kotlinx.coroutines.runBlocking

/**
 * IPC proxy implementation of LogDataProvider.
 */
class LogDataProviderProxy(
    channel: ManagedChannel,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : LogDataProvider {
    private val stub = LogServiceGrpcKt.LogServiceCoroutineStub(channel)

    private val _logs = MutableStateFlow<List<LogEntryData>>(emptyList())
    override val logs: StateFlow<List<LogEntryData>> = _logs.asStateFlow()

    private val _filter = MutableStateFlow(LogFilterData.ALL)
    override val filter: StateFlow<LogFilterData> = _filter.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    override val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _autoScroll = MutableStateFlow(true)
    override val autoScroll: StateFlow<Boolean> = _autoScroll.asStateFlow()

    init {
        scope.launch { watchLogs() }
        scope.launch { watchFilter() }
        scope.launch { watchSearchQuery() }
        scope.launch { watchAutoScroll() }
    }

    private suspend fun watchLogs() {
        var delayMs = 1_000L
        while (scope.isActive) {
            try {
                stub.watchLogs(Empty.getDefaultInstance()).collect { response ->
                    _logs.value = response.entriesList.map { it.toData() }
                }
                delayMs = 1_000L
            } catch (
                e: kotlinx.coroutines.CancellationException,
            ) {
                throw e
            } catch (_: Exception) {
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    private suspend fun watchFilter() {
        var delayMs = 1_000L
        while (scope.isActive) {
            try {
                stub.watchFilter(Empty.getDefaultInstance()).collect { response ->
                    _filter.value =
                        when (response.filter) {
                            LogFilterType.LOG_FILTER_TYPE_STDOUT -> LogFilterData.STDOUT
                            LogFilterType.LOG_FILTER_TYPE_STDERR -> LogFilterData.STDERR
                            else -> LogFilterData.ALL
                        }
                }
                delayMs = 1_000L
            } catch (
                e: kotlinx.coroutines.CancellationException,
            ) {
                throw e
            } catch (_: Exception) {
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    private suspend fun watchSearchQuery() {
        var delayMs = 1_000L
        while (scope.isActive) {
            try {
                stub.watchSearchQuery(Empty.getDefaultInstance()).collect { response ->
                    _searchQuery.value = response.value
                }
                delayMs = 1_000L
            } catch (
                e: kotlinx.coroutines.CancellationException,
            ) {
                throw e
            } catch (_: Exception) {
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    private suspend fun watchAutoScroll() {
        var delayMs = 1_000L
        while (scope.isActive) {
            try {
                stub.watchAutoScroll(Empty.getDefaultInstance()).collect { response ->
                    _autoScroll.value = response.value
                }
                delayMs = 1_000L
            } catch (
                e: kotlinx.coroutines.CancellationException,
            ) {
                throw e
            } catch (_: Exception) {
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    override fun setFilter(filter: LogFilterData) {
        scope.launch {
            try {
                val protoFilter =
                    when (filter) {
                        LogFilterData.ALL -> LogFilterType.LOG_FILTER_TYPE_ALL
                        LogFilterData.STDOUT -> LogFilterType.LOG_FILTER_TYPE_STDOUT
                        LogFilterData.STDERR -> LogFilterType.LOG_FILTER_TYPE_STDERR
                    }
                stub.setFilter(LogFilterProto.newBuilder().setFilter(protoFilter).build())
            } catch (_: Exception) {
            }
        }
    }

    override fun setSearchQuery(query: String) {
        scope.launch {
            try {
                stub.setSearchQuery(LogStringRequest.newBuilder().setValue(query).build())
            } catch (_: Exception) {
            }
        }
    }

    override fun toggleAutoScroll() {
        scope.launch {
            try {
                stub.toggleAutoScroll(Empty.getDefaultInstance())
            } catch (_: Exception) {
            }
        }
    }

    override fun clearLogs() {
        scope.launch {
            try {
                stub.clearLogs(Empty.getDefaultInstance())
            } catch (_: Exception) {
            }
        }
    }

    override fun exportLogs(): String =
        try {
            runBlocking {
                stub.exportLogs(Empty.getDefaultInstance()).value
            }
        } catch (_: Exception) {
            ""
        }

    private fun LogEntryProto.toData() =
        LogEntryData(
            timestamp = timestamp,
            message = message,
            source =
                when (source) {
                    LogSourceProto.LOG_SOURCE_STDERR -> LogSourceData.STDERR
                    else -> LogSourceData.STDOUT
                },
        )
}
