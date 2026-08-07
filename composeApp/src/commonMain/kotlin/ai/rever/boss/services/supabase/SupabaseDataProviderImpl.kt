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
        range: QueryRange?,
    ): Result<String> =
        try {
            val result =
                client
                    .from(table)
                    .select(
                        io.github.jan.supabase.postgrest.query.Columns
                            .raw(columns),
                    ) {
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
            val safe = sanitizeSupabaseFailure("select($table)", e)
            logger.error(
                LogCategory.NETWORK,
                "Supabase select failed",
                data = mapOf("table" to table),
                error = safe,
            )
            Result.failure(Exception("Select on '$table' failed: ${safe.message}"))
        }

    override suspend fun rpc(
        function: String,
        parameters: String,
    ): Result<String> =
        try {
            val params: JsonElement = supabaseJson.parseToJsonElement(parameters)
            val result =
                client.postgrest.rpc(
                    function = function,
                    parameters = params,
                )
            Result.success(result.data)
        } catch (e: Exception) {
            // Sanitised in the REQUEST direction too, which is easy to miss: `parameters` is
            // caller-supplied, and a plugin calling create_secret puts the new password in it.
            //
            // Sanitised ONCE and used for both the log and the returned exception. Doing only
            // the log is worse than useless here: the caller is a plugin, the plugin is at
            // least as likely to log what it gets, and the document would have been stripped
            // from our log and handed straight to it.
            val safe = sanitizeSupabaseFailure("rpc($function)", e)
            logger.error(
                LogCategory.NETWORK,
                "Supabase rpc failed",
                data = mapOf("function" to function),
                error = safe,
            )
            Result.failure(Exception("RPC '$function' failed: ${safe.message}"))
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
