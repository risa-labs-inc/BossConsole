package ai.rever.boss.components.auth.screens

import io.ktor.http.parseQueryString

private const val MAX_UNWRAP = 2
private val TOKEN = Regex("[A-Za-z0-9_-]+")
private val TYPE = Regex("[a-z_]+")

/**
 * The invisible `Cf` characters a copy can carry that [Char.isWhitespace] does not reach: the
 * zero-width space and joiners (U+200B-D) and the byte-order mark (U+FEFF). [TOKEN] and [TYPE]
 * exclude them and a URL carries them encoded, so dropping them is the same widening as the
 * whitespace removal.
 */
private val INVISIBLE_NOISE = setOf('\u200B', '\u200C', '\u200D', '\uFEFF')

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
 *
 * **Whitespace is removed from the whole link, not only its ends, and so are the invisible
 * `Cf` characters a rendered copy can carry (U+200B-D, U+FEFF).** This box is the path for when
 * `boss://` is not delivered (BossConsole#410), which is a plain-text copy, and plain-text mail
 * clients wrap a long URL across lines. A break lands mid-token, so `trim()` alone refuses the one
 * shape this exists to read. Safe here because no part this reads can legitimately contain any of
 * these: [TOKEN] and [TYPE] both exclude them, and a URL carries them encoded. Leading and
 * trailing invisible noise - including a byte-order mark - is trimmed before the passthrough
 * check, since plain `trim()` does not reach it and a routed host would take the link over
 * mangled. The `boss://` passthrough is deliberately outside the strip and keeps the same trim,
 * since that link is handed on verbatim rather than parsed.
 */
internal fun signInDeepLinkFor(pasted: String): String? {
    val link = pasted.trim { it.isWhitespace() || it in INVISIBLE_NOISE }
    if (link.startsWith("boss://")) return link
    return signInTokenIn(
        link.filterNot { it.isWhitespace() || it in INVISIBLE_NOISE },
        MAX_UNWRAP,
    )?.let { (token, type) ->
        val resolvedType = type ?: "magiclink"
        "boss://auth/verify?token=$token&type=$resolvedType"
            .takeIf { TOKEN.matches(token) && TYPE.matches(resolvedType) }
    }
}

/**
 * The token and type in [link], or null when it carries none.
 *
 * The fragment is dropped before the query is read. `substringAfter('?')` would otherwise keep a
 * trailing `#...` inside whichever parameter came last, so a mail client that appends one to every
 * link it rewrites turns a valid `&type=magiclink` into `magiclink#_=_` and the charset check
 * refuses it. Nothing this reads lives in the fragment: the one sign-in link that carries its token
 * there is `boss://auth/verify#access_token=`, which returns from the passthrough above and never
 * reaches here. That also closes the older, wider reading, where a token sitting only in the
 * fragment (`.../verify?a=1#&token=...`) was read as if it were in the query.
 *
 * The strip above happens once, at the call site, so a decoded `url=` value re-entered here may
 * still carry a literal space. That fails closed: the charset checks and the address suffix refuse
 * whatever such a space leaves behind.
 */
private fun signInTokenIn(
    link: String,
    unwrapsLeft: Int,
): Pair<String, String?>? {
    val query = link.substringAfter('?', "").substringBefore('#')
    val params = runCatching { parseQueryString(query) }.getOrNull()
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
