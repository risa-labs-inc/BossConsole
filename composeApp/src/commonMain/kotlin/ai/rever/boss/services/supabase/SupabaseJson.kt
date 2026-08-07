package ai.rever.boss.services.supabase

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The Json instance for every Supabase payload in this package, in both directions.
 *
 * Responses are the reason it exists, but request parameters and decoded JWT payloads go
 * through it as well, so that "never the `Json` default here" is a rule with no exceptions
 * to remember. `SupabaseWiringTest` enforces exactly that.
 *
 * `ignoreUnknownKeys` is not a convenience, it is a requirement of how BOSS ships.
 * The database is migrated ahead of the desktop app and installed copies keep talking
 * to it, so a client will meet columns it does not model. The kotlinx default is strict
 * and treats those as a hard error - and because these RPCs return LISTS, one unmodelled
 * key does not drop a field, it throws and takes the whole page with it.
 *
 * That is not hypothetical. The organisation migration extended four secret RPCs at once
 * (`get_user_secrets`, `search_user_secrets`, `get_user_secrets_with_shared`,
 * `get_secret_shares`) and every already-installed build started answering
 * "Encountered an unknown key 'org_id'". The secret panels rendered empty with nothing
 * but a WARN in the log.
 *
 * It lives here rather than inside one service on purpose: the hazard is a property of
 * the DEPLOYMENT, not of any single file, so every service that decodes a server response
 * is exposed to it equally. `RoleService` and `RoleCreationService` had not been bitten
 * only because `roles` and `permissions` had not been extended yet.
 *
 * ## What this buys, and what it costs
 *
 * Additive schema changes become safe. In exchange, **renames become silent** for any
 * field carrying a default: rename `metadata` server-side and decoding now succeeds with
 * the 2FA metadata quietly missing, rather than failing loudly.
 *
 * That is the right trade here - a loud failure means every secret vanishes - but it makes
 * "additive only, never rename" a contract the database side has to keep. Renaming a
 * projected column is a breaking change for every installed build and needs the field kept
 * as an alias until those builds age out. Do not read this instance as blanket tolerance
 * of schema drift.
 */
internal val supabaseJson = Json { ignoreUnknownKeys = true }

/**
 * The marker kotlinx puts before the offending document in a parse failure.
 *
 * Pinned by `SupabaseJsonTest` rather than trusted: if a kotlinx upgrade renames it, the
 * sanitiser below silently stops working and starts leaking again, so the test asserts on
 * a real exception from the current version rather than on this constant.
 */
private const val JSON_INPUT_MARKER = "\nJSON input: "

/**
 * Strip the response body out of a decode failure before it can be logged.
 *
 * kotlinx appends the whole offending document to a **parse** error:
 *
 * ```
 * Unexpected JSON token at offset 73: Expected end of the object or comma at path: $
 * JSON input: [{"id":"1","password":"SUPERSECRET_PW","recovery_codes":["ABC"]
 * ```
 *
 * For the secret RPCs that document holds passwords the server has already decrypted,
 * plus recovery codes. `SecretService` returns its exceptions through `Result.failure`
 * and callers log `e.message`, which is how the `org_id` WARN reached the log at all - so
 * a truncated or garbled response body (a proxy error, a connection cut mid-stream, a
 * gateway HTML page) would write live credentials into a log file that users attach to
 * bug reports.
 *
 * Verified against the current kotlinx: SCHEMA failures are already safe - an unknown key
 * or a null in a non-nullable slot reports the key name and JSON path and nothing else. It
 * is only the malformed-input path that appends the document. The distinction does not
 * matter to callers, so everything is sanitised uniformly.
 *
 * BOUNDED, and deliberately so: anything that is not a `SerializationException` passes
 * through untouched, because network and auth failures are not ours to rewrite and losing
 * their type would break callers that distinguish them. That is not the same as "nothing
 * else can leak" - supabase-kt's `RestException` carries the PostgREST error body, and a
 * constraint violation can echo column values back ("Key (col)=(value) already exists").
 * Do not read this helper as covering every way a payload can escape this package.
 *
 * The diagnostic half of the message is kept deliberately. "Encountered an unknown key
 * 'org_id' at path: $" is exactly what identified this outage; discarding it to be safe
 * would trade a credential leak for an undiagnosable one.
 */
internal fun sanitizeSupabaseFailure(
    operation: String,
    error: Throwable,
): Throwable {
    if (error !is SerializationException) return error
    val diagnostic =
        error.message
            ?.substringBefore(JSON_INPUT_MARKER)
            ?.takeIf { it.isNotBlank() }
            ?: "malformed response"
    return SupabaseFailure("$operation: $diagnostic")
}

/**
 * A decode failure with the response body removed.
 *
 * A distinct type so the cause cannot be attached: chaining the original would put the
 * payload back within reach of any handler that walks `cause`, which is the whole thing
 * being prevented.
 */
internal class SupabaseFailure(
    message: String,
) : Exception(message)
