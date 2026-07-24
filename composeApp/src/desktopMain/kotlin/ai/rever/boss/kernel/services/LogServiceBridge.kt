package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.LogDataProvider
import ai.rever.boss.plugin.api.LogFilterData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class LogServiceBridge(
    private val provider: LogDataProvider,
) : LogServiceGrpcKt.LogServiceCoroutineImplBase() {
    override fun watchLogs(request: Empty): Flow<LogListResponse> =
        flow {
            provider.logs.collect { logs ->
                emit(
                    LogListResponse
                        .newBuilder()
                        .addAllEntries(
                            logs.map { entry ->
                                LogEntryProto
                                    .newBuilder()
                                    .setTimestamp(entry.timestamp)
                                    .setMessage(entry.message)
                                    .setSource(
                                        when (entry.source) {
                                            ai.rever.boss.plugin.api.LogSourceData.STDOUT -> LogSourceProto.LOG_SOURCE_STDOUT
                                            ai.rever.boss.plugin.api.LogSourceData.STDERR -> LogSourceProto.LOG_SOURCE_STDERR
                                        },
                                    ).build()
                            },
                        ).build(),
                )
            }
        }

    override fun watchFilter(request: Empty): Flow<LogFilterProto> =
        flow {
            provider.filter.collect { filter ->
                emit(
                    LogFilterProto
                        .newBuilder()
                        .setFilter(
                            when (filter) {
                                LogFilterData.ALL -> LogFilterType.LOG_FILTER_TYPE_ALL
                                LogFilterData.STDOUT -> LogFilterType.LOG_FILTER_TYPE_STDOUT
                                LogFilterData.STDERR -> LogFilterType.LOG_FILTER_TYPE_STDERR
                            },
                        ).build(),
                )
            }
        }

    override fun watchSearchQuery(request: Empty): Flow<LogStringResponse> =
        flow {
            provider.searchQuery.collect { query ->
                emit(LogStringResponse.newBuilder().setValue(query).build())
            }
        }

    override fun watchAutoScroll(request: Empty): Flow<LogBoolResponse> =
        flow {
            provider.autoScroll.collect { enabled ->
                emit(LogBoolResponse.newBuilder().setValue(enabled).build())
            }
        }

    override suspend fun setFilter(request: LogFilterProto): Empty {
        val filter =
            when (request.filter) {
                LogFilterType.LOG_FILTER_TYPE_STDOUT -> LogFilterData.STDOUT
                LogFilterType.LOG_FILTER_TYPE_STDERR -> LogFilterData.STDERR
                else -> LogFilterData.ALL
            }
        provider.setFilter(filter)
        return Empty.getDefaultInstance()
    }

    override suspend fun setSearchQuery(request: LogStringRequest): Empty {
        provider.setSearchQuery(request.value)
        return Empty.getDefaultInstance()
    }

    override suspend fun toggleAutoScroll(request: Empty): Empty {
        provider.toggleAutoScroll()
        return Empty.getDefaultInstance()
    }

    override suspend fun clearLogs(request: Empty): Empty {
        provider.clearLogs()
        return Empty.getDefaultInstance()
    }

    override suspend fun exportLogs(request: Empty): LogStringResponse {
        val result = provider.exportLogs()
        return LogStringResponse.newBuilder().setValue(result).build()
    }
}
