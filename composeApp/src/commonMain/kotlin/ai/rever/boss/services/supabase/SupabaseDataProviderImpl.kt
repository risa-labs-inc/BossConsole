package ai.rever.boss.services.supabase

import ai.rever.boss.plugin.api.FilterOperator
import ai.rever.boss.plugin.api.QueryFilter
import ai.rever.boss.plugin.api.QueryRange
import ai.rever.boss.plugin.api.SupabaseDataProvider
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Implementation of SupabaseDataProvider that delegates to the Supabase SDK.
 *
 * Maps generic QueryFilter enums to Supabase SDK filter calls and
 * returns raw JSON strings from Postgrest responses.
 */
class SupabaseDataProviderImpl : SupabaseDataProvider {

    private val logger = BossLogger.forComponent("SupabaseDataProvider")
    private val client get() = SupabaseConfig.client

    override suspend fun select(
        table: String,
        columns: String,
        filters: List<QueryFilter>,
        range: QueryRange?
    ): Result<String> {
        return try {
            val result = client.from(table)
                .select(io.github.jan.supabase.postgrest.query.Columns.raw(columns)) {
                    if (range != null) {
                        range(range.from, range.to)
                    }
                    filter {
                        for (f in filters) {
                            applyFilter(f)
                        }
                    }
                }
            Result.success(result.data)
        } catch (e: Exception) {
            logger.error(LogCategory.NETWORK, "Supabase select failed", data = mapOf("table" to table), error = e)
            Result.failure(Exception("Select on '$table' failed: ${e.message}"))
        }
    }

    override suspend fun rpc(
        function: String,
        parameters: String
    ): Result<String> {
        return try {
            val params: JsonElement = Json.parseToJsonElement(parameters)
            val result = client.postgrest.rpc(
                function = function,
                parameters = params
            )
            Result.success(result.data)
        } catch (e: Exception) {
            logger.error(LogCategory.NETWORK, "Supabase rpc failed", data = mapOf("function" to function), error = e)
            Result.failure(Exception("RPC '$function' failed: ${e.message}"))
        }
    }

    private fun io.github.jan.supabase.postgrest.query.filter.PostgrestFilterBuilder.applyFilter(f: QueryFilter) {
        when (f.operator) {
            FilterOperator.EQ -> eq(f.column, f.value)
            FilterOperator.NEQ -> neq(f.column, f.value)
            FilterOperator.GT -> gt(f.column, f.value)
            FilterOperator.GTE -> gte(f.column, f.value)
            FilterOperator.LT -> lt(f.column, f.value)
            FilterOperator.LTE -> lte(f.column, f.value)
            FilterOperator.LIKE -> like(f.column, f.value)
            FilterOperator.ILIKE -> ilike(f.column, f.value)
            FilterOperator.IN -> isIn(f.column, f.value.split(",").map { it.trim() })
            FilterOperator.IS -> exact(f.column, f.value.toBooleanStrictOrNull())
        }
    }
}
