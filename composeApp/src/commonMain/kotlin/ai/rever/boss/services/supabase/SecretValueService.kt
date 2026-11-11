package ai.rever.boss.services.supabase

import ai.rever.boss.services.supabase.models.SecretSummary
import ai.rever.boss.services.supabase.models.SecretValues
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/**
 * The v2 secret reads (20261111190000): a metadata-only listing and an audited by-id value
 * read. Failures are sanitised as in [SecretService]; logs carry the operation, never values.
 */
object SecretValueService {
    private val logger = BossLogger.forComponent("SecretValueService")

    /** Server limit per `get_secret_values` call. */
    const val MAX_VALUE_IDS: Int = 100

    private val PURPOSE = Regex("^[a-z0-9][a-z0-9_.:-]{0,63}$")

    /**
     * Owned and shared secrets, metadata only (`list_secrets_v2`). [kinds] filters on
     * `kind:<k>` tags (`card`, `password`, ...); null lists every kind.
     */
    suspend fun listV2(
        limit: Int = 50,
        offset: Int = 0,
        kinds: List<String>? = null,
    ): Result<List<SecretSummary>> = call("listV2", "list_secrets_v2", listV2Params(limit, offset, kinds))

    /**
     * Decrypted values for [ids] (`get_secret_values`). The server writes one access-log row
     * per id returned, tagged [purpose]; ids the caller may not read are silently omitted.
     */
    suspend fun getValues(
        ids: Collection<String>,
        purpose: String,
    ): Result<List<SecretValues>> {
        // Built from the caller's own arguments; no server payload to sanitise.
        val params = getValuesParams(ids, purpose).getOrElse { safe -> return Result.failure(safe) }
        return if (ids.isEmpty()) Result.success(emptyList()) else call("getValues", "get_secret_values", params)
    }

    private suspend inline fun <reified T> call(
        operation: String,
        function: String,
        params: JsonObject,
    ): Result<List<T>> =
        try {
            val response = SupabaseConfig.client.postgrest.rpc(function = function, parameters = params)
            val data = response.data
            Result.success(supabaseJson.decodeFromJsonElement<List<T>>(supabaseJson.parseToJsonElement(data)))
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            val safe = sanitizeSupabaseFailure(operation, e)
            logger.warn(
                LogCategory.NETWORK,
                "Secret RPC failed",
                data = mapOf("operation" to operation, "errorType" to e::class.simpleName),
            )
            Result.failure(safe)
        }

    internal fun listV2Params(
        limit: Int,
        offset: Int,
        kinds: List<String>?,
    ): JsonObject =
        buildJsonObject {
            put("p_limit", limit)
            put("p_offset", offset)
            if (kinds != null) put("p_kinds", JsonArray(kinds.map { JsonPrimitive(it) }))
        }

    /** Mirrors the server checks so a bad call fails before it leaves the host. */
    internal fun getValuesParams(
        ids: Collection<String>,
        purpose: String,
    ): Result<JsonObject> =
        when {
            !PURPOSE.matches(purpose) -> {
                Result.failure(IllegalArgumentException("purpose must be a short lowercase label"))
            }

            ids.size > MAX_VALUE_IDS -> {
                Result.failure(IllegalArgumentException("at most $MAX_VALUE_IDS ids per call"))
            }

            else -> {
                Result.success(
                    buildJsonObject {
                        put("p_ids", JsonArray(ids.distinct().map { JsonPrimitive(it) }))
                        put("p_purpose", purpose)
                    },
                )
            }
        }
}
