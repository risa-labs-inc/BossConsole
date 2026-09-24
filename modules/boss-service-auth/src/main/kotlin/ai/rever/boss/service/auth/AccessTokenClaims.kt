package ai.rever.boss.service.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * The role claims `custom_access_token_hook` puts in the access token: `is_admin` and the effective
 * `user_permissions` set.
 *
 * These come from the token, never from `user_metadata`. The server writes neither there, so a
 * real admin read as `false` and every permission check answered no; and `user_metadata` belongs
 * to the user - any signed-in account can set `is_admin: true` on itself through `updateUser`.
 * The host already reads the same claims (`RoleService.decodeJWTClaims` and
 * `RoleClaims.fromJWTClaims`).
 */
internal data class AccessTokenClaims(
    val isAdmin: Boolean,
    val permissions: Set<String>,
) {
    companion object {
        /** What an unreadable token grants: nothing. */
        val NONE = AccessTokenClaims(isAdmin = false, permissions = emptySet())
    }
}

/**
 * Reads [AccessTokenClaims] from [accessToken], or null when the token cannot be read.
 *
 * Fails closed: `is_admin` counts only when it reads as `true` - a JSON boolean, or the string
 * `"true"`, which the host's `RoleService` accepts too; the hook writes a real boolean. Anything
 * else (`"yes"`, `1`) is not an admin, and a non-primitive `is_admin` makes the whole token
 * unreadable. `user_permissions` keeps only its string entries. A missing claim grants nothing.
 *
 * The signature is not checked here, as it is not in the host. After a sign-in the token is the
 * one Supabase just returned to this process; on a restored session it comes from supabase-kt's
 * local session store, unverified and without an `exp` check. That store is only writable by the
 * account this service already runs as, and real authority stays with server-side RLS, which
 * checks the genuine JWT - so do not treat these claims as verified beyond that.
 */
internal fun accessTokenClaims(accessToken: String): AccessTokenClaims? =
    try {
        val parts = accessToken.split(".")
        require(parts.size == 3) { "Invalid JWT format" }
        val payload = Base64.getUrlDecoder().decode(parts[1]).decodeToString()
        val claims = Json.parseToJsonElement(payload).jsonObject
        AccessTokenClaims(
            isAdmin =
                claims["is_admin"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.toBooleanStrictOrNull() ?: false,
            permissions =
                (claims["user_permissions"] as? JsonArray)
                    ?.mapNotNull { entry -> (entry as? JsonPrimitive)?.takeIf { it.isString }?.content }
                    ?.toSet()
                    .orEmpty(),
        )
    } catch (_: IllegalArgumentException) {
        // Base64, JSON and shape errors all land here. Nothing from the exception is kept: a
        // garbled payload puts the claim set into the message.
        null
    }
