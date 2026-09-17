package ai.rever.boss.components.auth.screens

import io.ktor.http.parseQueryString

private const val MAX_UNWRAP = 2
private val TOKEN = Regex("[A-Za-z0-9_-]+")
private val TYPE = Regex("[a-z_]+")

/**
 * The `boss://auth/verify` link for text pasted into "Paste magic link manually", or null when the text is
 * not a sign-in link.
 *
 * The email's link points at the redirect function, so the link is read the way that function reads it
 * (`supabase/functions/redirect/app.ts`):
 * - the token is `token` or `token_hash`, on the pasted link itself or on the confirmation URL in its `url=`
 *   parameter. A copied link can carry that URL percent-encoded, and the query is decoded, not searched as
 *   text: matching a literal `token=` is what made the encoded link do nothing;
 * - the type is the one beside the token, else the outer link's (an unencoded `url=` value splits its
 *   `&type=` off onto the redirect link), else `magiclink`.
 *
 * Two refusals the redirect function does not need, because this link is assembled as a string and
 * `BossAppWithAuth` splits it on `&`:
 * - a token or type with other characters, since a decoded `abc&type=recovery` would add a parameter;
 * - a token on an address whose path does not end in `/verify` or `/redirect`, so a token from some other
 *   service pasted by mistake is never sent to Supabase.
 *
 * `url=` is followed at most [MAX_UNWRAP] times: the email's own wrapper and one more.
 */
internal fun signInDeepLinkFor(pasted: String): String? {
    val link = pasted.trim()
    if (link.startsWith("boss://")) return link
    return signInTokenIn(link, MAX_UNWRAP)?.let { (token, type) ->
        val resolvedType = type ?: "magiclink"
        "boss://auth/verify?token=$token&type=$resolvedType"
            .takeIf { TOKEN.matches(token) && TYPE.matches(resolvedType) }
    }
}

private fun signInTokenIn(
    link: String,
    unwrapsLeft: Int,
): Pair<String, String?>? {
    val params = runCatching { parseQueryString(link.substringAfter('?', "")) }.getOrNull()
    val token = params?.get("token") ?: params?.get("token_hash")
    val outerType = params?.get("type")
    val nested = params?.get("url")?.takeIf { unwrapsLeft > 0 }
    return when {
        token != null -> (token to outerType).takeIf { isSignInAddress(link.substringBefore('?')) }
        nested != null -> signInTokenIn(nested, unwrapsLeft - 1)?.let { (inner, type) -> inner to (type ?: outerType) }
        else -> null
    }
}

/** An address ending in `/verify` (Supabase) or `/redirect` (the email's redirect function), on any host. */
private fun isSignInAddress(address: String): Boolean = address.endsWith("/verify") || address.endsWith("/redirect")
