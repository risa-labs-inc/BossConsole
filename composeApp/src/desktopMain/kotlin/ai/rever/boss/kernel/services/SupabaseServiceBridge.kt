package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.FilterOperator
import ai.rever.boss.plugin.api.QueryFilter
import ai.rever.boss.plugin.api.QueryRange
import ai.rever.boss.plugin.api.SupabaseDataProvider

class SupabaseServiceBridge(
    private val provider: SupabaseDataProvider,
) : SupabaseServiceGrpcKt.SupabaseServiceCoroutineImplBase() {

    override suspend fun select(request: SupabaseSelectRequest): SupabaseJsonResponse {
        val filters = request.filtersList.map { filter ->
            QueryFilter(
                column = filter.column,
                operator = when (filter.operator) {
                    QueryOperatorProto.QUERY_OPERATOR_EQ -> FilterOperator.EQ
                    QueryOperatorProto.QUERY_OPERATOR_NEQ -> FilterOperator.NEQ
                    QueryOperatorProto.QUERY_OPERATOR_GT -> FilterOperator.GT
                    QueryOperatorProto.QUERY_OPERATOR_GTE -> FilterOperator.GTE
                    QueryOperatorProto.QUERY_OPERATOR_LT -> FilterOperator.LT
                    QueryOperatorProto.QUERY_OPERATOR_LTE -> FilterOperator.LTE
                    QueryOperatorProto.QUERY_OPERATOR_LIKE -> FilterOperator.LIKE
                    QueryOperatorProto.QUERY_OPERATOR_ILIKE -> FilterOperator.ILIKE
                    QueryOperatorProto.QUERY_OPERATOR_IN -> FilterOperator.IN
                    QueryOperatorProto.QUERY_OPERATOR_IS -> FilterOperator.IS
                    else -> FilterOperator.EQ
                },
                value = filter.value,
            )
        }
        val range = if (request.rangeFrom >= 0 && request.rangeTo > 0) {
            QueryRange(request.rangeFrom.toLong(), request.rangeTo.toLong())
        } else null

        val result = provider.select(
            table = request.table,
            columns = request.columns,
            filters = filters,
            range = range,
        )

        return result.fold(
            onSuccess = { json ->
                SupabaseJsonResponse.newBuilder()
                    .setSuccess(true)
                    .setJsonData(json)
                    .build()
            },
            onFailure = { error ->
                SupabaseJsonResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(error.message ?: "Unknown error")
                    .build()
            }
        )
    }

    override suspend fun rpc(request: SupabaseRpcRequest): SupabaseJsonResponse {
        val result = provider.rpc(
            function = request.function,
            parameters = request.parametersJson,
        )

        return result.fold(
            onSuccess = { json ->
                SupabaseJsonResponse.newBuilder()
                    .setSuccess(true)
                    .setJsonData(json)
                    .build()
            },
            onFailure = { error ->
                SupabaseJsonResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(error.message ?: "Unknown error")
                    .build()
            }
        )
    }
}
