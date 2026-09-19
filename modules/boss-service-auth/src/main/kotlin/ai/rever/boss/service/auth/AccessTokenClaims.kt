package ai.rever.boss.service.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * The `is_admin` claim the `custom_access_token_hook` puts in the access token, or null when the
 * token cannot be read.
 *
 * Not `user_metadata`: the server never writes `is_admin` there, so a real admin read as `false`,
 * and `user_metadata` belongs to the user - any signed-in account can set `is_admin: true` on
 * itself through `updateUser`. The host already reads the claim (`RoleService.decodeJWTClaims`
 * and `RoleClaims.fromJWTClaims`); this matches its parsing, so a missing claim or anything but a
 * strict boolean is not an admin. The signature is not checked here, as it is not in the host:
 * the token is the one Supabase just returned to this process.
 */
internal fun isAdminClaim(accessToken: String): Boolean? =
    try {
        val parts = accessToken.split(".")
        require(parts.size == 3) { "Invalid JWT format" }
        val payload = Base64.getUrlDecoder().decode(parts[1]).decodeToString()
        Json
            .parseToJsonElement(payload)
            .jsonObject["is_admin"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toBooleanStrictOrNull() ?: false
    } catch (_: IllegalArgumentException) {
        // Base64, JSON and shape errors all land here. Nothing from the exception is kept: a
        // garbled payload puts the claim set into the message.
        null
    }
