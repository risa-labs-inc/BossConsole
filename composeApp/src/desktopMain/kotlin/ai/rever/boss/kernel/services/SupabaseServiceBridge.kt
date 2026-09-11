package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.FilterOperator
import ai.rever.boss.plugin.api.QueryFilter
import ai.rever.boss.plugin.api.QueryRange
import ai.rever.boss.plugin.api.SupabaseDataProvider
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import io.grpc.Status
import io.grpc.StatusException

/**
 * Kernel-side bridge for `SupabaseService` - a generic, table/RPC-name-addressed proxy onto
 * [SupabaseDataProvider], which runs every call on the signed-in user's own Supabase session
 * (`SupabaseConfig.client`, RLS-scoped - see [ai.rever.boss.services.supabase.SupabaseDataProviderImpl]).
 * That is not a narrower grant than [SecretServiceBridge]'s: `rpc` reaches the very same
 * `create_secret`/`update_secret`/share RPCs the Secret Manager UI calls (AGENTS.md's own
 * "Decoding Supabase payloads" section names `create_secret` as exactly this kind of call), and
 * `select` can read any table RLS exposes to that user - this bridge is not scoped to any one
 * feature, it is the whole Postgrest surface.
 *
 * **Every call requires a verified caller identity (BossConsole#53)**, the same requirement and
 * helper shape as [SecretServiceBridge] - see that class's KDoc for the full rationale. Unlike
 * that fix, this one does not narrow *what* an authenticated caller may do here (every legitimate
 * in-process plugin already has the identical unrestricted access via `PluginContext
 * .supabaseDataProvider` - see `DefaultPlugin.kt:528` - so that is an existing, accepted design
 * decision this bridge is not the place to relitigate). What was missing was *whether the caller
 * is even a real, spawned BOSS process at all* - before this, any local process reaching the
 * kernel's IPC socket had the same reach as a legitimately installed plugin, attributable to
 * nobody.
 */
class SupabaseServiceBridge(
    private val provider: SupabaseDataProvider,
) : SupabaseServiceGrpcKt.SupabaseServiceCoroutineImplBase() {
    override suspend fun select(request: SupabaseSelectRequest): SupabaseJsonResponse {
        val caller = authenticatedCallerOrRefuse("select")
        logCall("select", caller, request.table)
        val filters =
            request.filtersList.map { filter ->
                QueryFilter(
                    column = filter.column,
                    operator =
                        when (filter.operator) {
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
        val range =
            if (request.rangeFrom >= 0 && request.rangeTo > 0) {
                QueryRange(request.rangeFrom.toLong(), request.rangeTo.toLong())
            } else {
                null
            }

        val result =
            provider.select(
                table = request.table,
                columns = request.columns,
                filters = filters,
                range = range,
            )

        return result.fold(
            onSuccess = { json ->
                SupabaseJsonResponse
                    .newBuilder()
                    .setSuccess(true)
                    .setJsonData(json)
                    .build()
            },
            onFailure = { error ->
                SupabaseJsonResponse
                    .newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(error.message ?: "Unknown error")
                    .build()
            },
        )
    }

    override suspend fun rpc(request: SupabaseRpcRequest): SupabaseJsonResponse {
        val caller = authenticatedCallerOrRefuse("rpc")
        logCall("rpc", caller, request.function)
        val result =
            provider.rpc(
                function = request.function,
                parameters = request.parametersJson,
            )

        return result.fold(
            onSuccess = { json ->
                SupabaseJsonResponse
                    .newBuilder()
                    .setSuccess(true)
                    .setJsonData(json)
                    .build()
            },
            onFailure = { error ->
                SupabaseJsonResponse
                    .newBuilder()
                    .setSuccess(false)
                    .setErrorMessage(error.message ?: "Unknown error")
                    .build()
            },
        )
    }

    /**
     * The verified identity behind this call, or a thrown `PERMISSION_DENIED` when there is none.
     *
     * Mirrors [SecretServiceBridge]'s own helper (BossConsole#53) - fails closed rather than let a
     * request with no credential fall through to [provider] with nothing to attribute it to.
     */
    private fun authenticatedCallerOrRefuse(rpc: String): String =
        ProcessIdentityInterceptor.AUTHENTICATED_PROCESS_ID.get() ?: run {
            logger.warn(
                LogCategory.AUTH,
                "Refused $rpc: no verified process identity on this call",
                mapOf("rpc" to rpc),
            )
            throw StatusException(Status.PERMISSION_DENIED.withDescription(NO_IDENTITY))
        }

    /**
     * Attributed audit line for every call through this bridge, not only mutations: this proxy
     * has no separate concept of a read-only call (a `select` is as capable of exposing a secret
     * as `rpc("get_user_secrets", ...)` is), so an unattributed read is exactly the exposure
     * BossConsole#53 closes for the narrower Secret/Role bridges.
     */
    private fun logCall(
        rpc: String,
        caller: String,
        target: String,
    ) {
        logger.info(
            LogCategory.AUTH,
            "Supabase proxy call",
            mapOf("rpc" to rpc, "caller" to caller, "target" to target),
        )
    }

    private companion object {
        val logger = BossLogger.forComponent("SupabaseServiceBridge")

        const val NO_IDENTITY = "This call presented no verified process identity"
    }
}
