package ai.rever.boss.plugin.ipc

import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.FilterOperator
import ai.rever.boss.plugin.api.QueryFilter
import ai.rever.boss.plugin.api.QueryRange
import ai.rever.boss.plugin.api.SupabaseDataProvider
import io.grpc.ManagedChannel

/**
 * IPC proxy implementation of SupabaseDataProvider.
 */
class SupabaseDataProviderProxy(
    channel: ManagedChannel,
) : SupabaseDataProvider {

    private val stub = SupabaseServiceGrpcKt.SupabaseServiceCoroutineStub(channel)

    override suspend fun select(
        table: String,
        columns: String,
        filters: List<QueryFilter>,
        range: QueryRange?,
    ): Result<String> = try {
        val builder = SupabaseSelectRequest.newBuilder()
            .setTable(table)
            .setColumns(columns)
        filters.forEach { f ->
            builder.addFilters(
                QueryFilterProto.newBuilder()
                    .setColumn(f.column)
                    .setOperator(f.operator.toProto())
                    .setValue(f.value)
                    .build()
            )
        }
        if (range != null) {
            builder.setRangeFrom(range.from.toInt()).setRangeTo(range.to.toInt())
        }
        val resp = stub.select(builder.build())
        if (resp.success) Result.success(resp.jsonData)
        else Result.failure(Exception(resp.errorMessage))
    } catch (e: Exception) { Result.failure(e) }

    override suspend fun rpc(function: String, parameters: String): Result<String> = try {
        val resp = stub.rpc(
            SupabaseRpcRequest.newBuilder()
                .setFunction(function)
                .setParametersJson(parameters)
                .build()
        )
        if (resp.success) Result.success(resp.jsonData)
        else Result.failure(Exception(resp.errorMessage))
    } catch (e: Exception) { Result.failure(e) }

    private fun FilterOperator.toProto(): QueryOperatorProto = when (this) {
        FilterOperator.EQ -> QueryOperatorProto.QUERY_OPERATOR_EQ
        FilterOperator.NEQ -> QueryOperatorProto.QUERY_OPERATOR_NEQ
        FilterOperator.GT -> QueryOperatorProto.QUERY_OPERATOR_GT
        FilterOperator.GTE -> QueryOperatorProto.QUERY_OPERATOR_GTE
        FilterOperator.LT -> QueryOperatorProto.QUERY_OPERATOR_LT
        FilterOperator.LTE -> QueryOperatorProto.QUERY_OPERATOR_LTE
        FilterOperator.LIKE -> QueryOperatorProto.QUERY_OPERATOR_LIKE
        FilterOperator.ILIKE -> QueryOperatorProto.QUERY_OPERATOR_ILIKE
        FilterOperator.IN -> QueryOperatorProto.QUERY_OPERATOR_IN
        FilterOperator.IS -> QueryOperatorProto.QUERY_OPERATOR_IS
    }
}
