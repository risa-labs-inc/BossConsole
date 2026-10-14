package ai.rever.boss.components.auth

/**
 * One auth-ceremony callback parsed out of a `boss://` deep link.
 *
 * Every field is exactly what a known producer wrote: a verification token from the sign-in
 * email's link, or a session id the server minted for a passkey ceremony. These values drive
 * session-establishing actions, so the link is parsed by structure in [AuthDeepLinks] and
 * anything that is not a known producer's shape is refused before anything acts on it.
 */
sealed interface AuthDeepLink {
    /**
     * `boss://auth/verify`, the sign-in email's magic link.
     *
     * @param token the email's verification token (query `token`) or the Supabase success
     *     redirect's access token (fragment `access_token`)
     * @param type the verification flow, `magiclink` when the link carries none
     */
    data class MagicLinkVerify(
        val token: String,
        val type: String,
    ) : AuthDeepLink

    /** `boss://passkey/registered?sessionId=<uuid>`, a passkey registration finished in the browser. */
    data class PasskeyRegistered(
        val sessionId: String,
    ) : AuthDeepLink

    /** `boss://passkey/authenticated?sessionId=<uuid>`, a passkey sign-in finished in the browser. */
    data class PasskeyAuthenticated(
        val sessionId: String,
    ) : AuthDeepLink

    /**
     * `boss://auth/callback`, Supabase's redirect back from a Google or Apple sign-in.
     *
     * Exactly one of [code] and [error] is set. The code is only half of the exchange: it is
     * worthless without the PKCE verifier this app stored when it opened the sign-in page, so
     * a callback nobody started cannot sign anyone in.
     *
     * @param code the PKCE authorization code (query `code`)
     * @param error the provider's or Supabase's error code (`error`, query or fragment)
     * @param errorDescription the human-readable reason beside [error], for logs only
     */
    data class OAuthCallback(
        val code: String?,
        val error: String?,
        val errorDescription: String?,
    ) : AuthDeepLink {
        override fun toString(): String {
            val shownCode = if (code != null) "<redacted>" else "null"
            return "OAuthCallback(code=$shownCode, error=$error)"
        }
    }
}

/**
 * Structural parser for the auth segment of the `boss://` deep-link pipeline.
 *
 * `boss://` is registered with the OS, so any web page can open one of these links, and the
 * auth segment acts with no confirmation. The pipeline's action routes already validate their
 * origin and target at execution; the auth segment did not: a substring anywhere in the link
 * was taken as a ceremony callback. This parser replaces that with the producers' exact
 * shapes:
 *
 * - the scheme is `boss` (case-insensitive, as URI schemes are), and the host+path is exactly
 *   `auth/verify`, `auth/callback`, `passkey/registered` or `passkey/authenticated`,
 *   case-sensitive, since the
 *   server emits exactly those and case-folding would take a lookalike host;
 * - a session id lives in the query only, exactly once, and is a UUID like the ones
 *   `PasskeyAuthService` mints; a copy in the fragment is an ambiguous smuggle and refuses the
 *   link;
 * - the token is the fragment's `access_token` (Supabase success redirect) or else the query's
 *   `token` (the manual-paste shape), exactly once across the whole link: a token-shaped value
 *   in more than one place — the query and the fragment, or two fragment names — is an
 *   ambiguous smuggle and refuses the link instead of being resolved by first match. The mail
 *   clients' `#_=_` fragment mangling carries no token and keeps parsing. It is at most 2048
 *   chars and URL-safe like every producer's token, so a value carrying other characters is
 *   refused, not decoded: the redirect function embeds its tokens with `encodeURIComponent`,
 *   and every character that escapes is a shape no producer's token carries;
 * - the type is the fragment's or else the query's `type`, `[a-z_]` up to 32, defaulting to
 *   `magiclink`.
 * - an OAuth callback carries a query `code` or an `error` code, never both, each at most once
 *   per section; a `code` in the fragment is a smuggle and refuses the link. An error may sit in
 *   the query, the fragment, or both with the same value (GoTrue writes both).
 *
 * Anything else is refused with `null`: an unknown host or path, a duplicate of a parameter
 * this parser reads, a missing token, or a value not of the producer's shape. Unknown extra
 * parameters are tolerated: Supabase fragments carry `refresh_token`, `expires_in` and
 * `token_type`, and a producer adding one must not break its own callback.
 */
object AuthDeepLinks {
    private const val MAGIC_LINK_HOST_PATH = "auth/verify"
    private const val PASSKEY_REGISTERED_HOST_PATH = "passkey/registered"
    private const val PASSKEY_AUTHENTICATED_HOST_PATH = "passkey/authenticated"
    private const val OAUTH_CALLBACK_HOST_PATH = "auth/callback"

    /** The ceremony routes, for the diagnostic [isAuthShaped] check only. */
    private val AUTH_HOST_PATHS =
        listOf(
            MAGIC_LINK_HOST_PATH,
            PASSKEY_REGISTERED_HOST_PATH,
            PASSKEY_AUTHENTICATED_HOST_PATH,
            OAUTH_CALLBACK_HOST_PATH,
        )

    private const val ACCESS_TOKEN_PARAM = "access_token"
    private const val TOKEN_PARAM = "token"
    private const val TYPE_PARAM = "type"
    private const val SESSION_ID_PARAM = "sessionId"
    private const val DEFAULT_TYPE = "magiclink"
    private const val CODE_PARAM = "code"
    private const val ERROR_PARAM = "error"
    private const val ERROR_DESCRIPTION_PARAM = "error_description"
    private const val MAX_ERROR_DESCRIPTION_LENGTH = 300

    /** The session ids `PasskeyAuthService` mints with `UUID.randomUUID()`. */
    private val sessionIdShape =
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /** Every producer's token: Supabase JWTs and the manual-paste shape, bounded to keep links sane. */
    private val tokenShape = Regex("[A-Za-z0-9._~-]{1,2048}")

    /** Supabase's PKCE auth codes, a UUID today; bounded and URL-safe so a format change still parses. */
    private val authCodeShape = Regex("[A-Za-z0-9._~-]{1,512}")

    /** OAuth 2.0 error codes (`access_denied`, `server_error`, ...). */
    private val errorShape = Regex("[a-z_]{1,64}")

    /** The flow names the redirect function writes beside the token. */
    private val typeShape = Regex("[a-z_]{1,32}")

    /**
     * The auth ceremony [uri] carries, or null when the link is not one of the known producers'
     * shapes. Parsing never throws: a malformed link is simply not a ceremony callback.
     */
    fun parse(uri: String): AuthDeepLink? {
        val sections = sectionsOf(uri) ?: return null
        return when (sections.hostPath) {
            MAGIC_LINK_HOST_PATH -> magicLinkOf(sections)
            PASSKEY_REGISTERED_HOST_PATH -> passkeyLinkOf(sections, registered = true)
            PASSKEY_AUTHENTICATED_HOST_PATH -> passkeyLinkOf(sections, registered = false)
            OAUTH_CALLBACK_HOST_PATH -> oauthCallbackOf(sections)
            else -> null
        }
    }

    /**
     * Whether [uri] completes the passkey ceremony the app started as [sessionId]: a parsed
     * passkey callback whose session id is the one that ceremony minted. Everything else — a
     * magic link, another session's callback, or a link that does not parse — is refused, so
     * the caller leaves the link for the app-level collector instead of acting on it.
     */
    fun completesPasskeyCeremony(
        uri: String,
        sessionId: String,
    ): Boolean =
        when (val link = parse(uri)) {
            is AuthDeepLink.PasskeyRegistered -> link.sessionId == sessionId
            is AuthDeepLink.PasskeyAuthenticated -> link.sessionId == sessionId
            else -> false
        }

    /**
     * Whether [uri] carries an auth ceremony's route even though [parse] refuses it — the
     * OS-mangled forms (`boss://auth/verify/`, `boss:///auth/verify?…`) and the look-alikes.
     * Diagnostic only: nothing acts on it, and a refused link falls through to the generic
     * deep-link router whatever this returns. The host+path — the part before any `?` and
     * `#` — must start with an auth route after an OS-added leading slash is removed, so neither
     * a route smuggled inside a parameter nor a file path containing one reads as auth-shaped.
     * The check is case-insensitive so it also names the case-folded hosts
     * the allowlist refuses (`boss://AUTH/verify`) — the mangling class most likely to need
     * the diagnostic. Only this diagnostic folds case; [parse] itself stays case-sensitive.
     */
    fun isAuthShaped(uri: String): Boolean {
        val sections = sectionsOf(uri) ?: return false
        val hostPath = sections.hostPath.trimStart('/').lowercase()
        return AUTH_HOST_PATHS.any { hostPath.startsWith(it) }
    }

    /** The scheme/host+path/query/fragment split of a `boss://` link, or null for any other scheme. */
    private fun sectionsOf(uri: String): Sections? {
        if (!uri.startsWith("boss://", ignoreCase = true)) return null
        val body = uri.substringAfter("://")
        val withoutFragment = body.substringBefore('#')
        return Sections(
            hostPath = withoutFragment.substringBefore('?'),
            query = paramsOf(withoutFragment.substringAfter('?', "")),
            fragment = paramsOf(body.substringAfter('#', "")),
        )
    }

    /** [sections] as a magic-link callback, or null without exactly one URL-safe token. */
    private fun magicLinkOf(sections: Sections): AuthDeepLink? {
        val queryToken = sections.query[TOKEN_PARAM]?.first()
        val accessToken = sections.fragment[ACCESS_TOKEN_PARAM]?.first()
        val fragmentToken = sections.fragment[TOKEN_PARAM]?.first()
        val tokenCandidates = listOfNotNull(queryToken, accessToken, fragmentToken).filter { tokenShape.matches(it) }
        // Refuse on ambiguity, like the passkey branch refuses a session id carried in the
        // fragment: each producer writes the token to exactly one place, so a repeated name
        // or token-shaped values in more than one — the query and the fragment, or two
        // fragment names — are a smuggle, not a callback to resolve by first match. The mail
        // clients' `#_=_` fragment mangling carries no token-shaped value and still parses.
        if (isDuplicated(sections.fragment, ACCESS_TOKEN_PARAM, TYPE_PARAM) ||
            isDuplicated(sections.query, TOKEN_PARAM, TYPE_PARAM) ||
            tokenCandidates.size > 1
        ) {
            return null
        }
        val token = accessToken ?: queryToken
        val type = sections.fragment[TYPE_PARAM]?.first() ?: sections.query[TYPE_PARAM]?.first() ?: DEFAULT_TYPE
        return if (token != null && tokenShape.matches(token) && typeShape.matches(type)) {
            AuthDeepLink.MagicLinkVerify(token, type)
        } else {
            null
        }
    }

    /** [sections] as a passkey ceremony callback, or null without an exactly-once UUID session id. */
    private fun passkeyLinkOf(
        sections: Sections,
        registered: Boolean,
    ): AuthDeepLink? {
        if (sections.fragment.containsKey(SESSION_ID_PARAM) || isDuplicated(sections.query, SESSION_ID_PARAM)) {
            return null
        }
        val sessionId = sections.query[SESSION_ID_PARAM]?.first()
        return if (sessionId != null && sessionIdShape.matches(sessionId)) {
            if (registered) AuthDeepLink.PasskeyRegistered(sessionId) else AuthDeepLink.PasskeyAuthenticated(sessionId)
        } else {
            null
        }
    }

    /**
     * [sections] as an OAuth callback, or null unless it carries exactly one of a URL-safe
     * `code` (query only, as the PKCE redirect writes it) or an `error` code, each exactly once
     * per section.
     */
    private fun oauthCallbackOf(sections: Sections): AuthDeepLink? {
        val ambiguous =
            sections.fragment.containsKey(CODE_PARAM) ||
                isDuplicated(sections.query, CODE_PARAM, ERROR_PARAM, ERROR_DESCRIPTION_PARAM) ||
                isDuplicated(sections.fragment, ERROR_PARAM, ERROR_DESCRIPTION_PARAM)
        val code = sections.query[CODE_PARAM]?.first()
        val hasError = sections.query.containsKey(ERROR_PARAM) || sections.fragment.containsKey(ERROR_PARAM)
        return when {
            ambiguous || (code != null && hasError) -> null
            code != null -> code.takeIf(authCodeShape::matches)?.let { AuthDeepLink.OAuthCallback(it, null, null) }
            else -> errorCallbackOf(sections)
        }
    }

    /**
     * The error half of [oauthCallbackOf]. GoTrue's redirectErrors writes the error into BOTH the
     * query and the fragment (the fragment copy is marked for deprecation upstream), so one error
     * in either section, or the same error in both, is the normal shape. Two DIFFERENT errors are
     * a smuggle. The description is read from the section the error was read from.
     */
    private fun errorCallbackOf(sections: Sections): AuthDeepLink? {
        val queryError = sections.query[ERROR_PARAM]?.first()
        val fragmentError = sections.fragment[ERROR_PARAM]?.first()
        val error = queryError ?: fragmentError
        if (error == null || (fragmentError != null && fragmentError != error)) return null
        val section = if (queryError != null) sections.query else sections.fragment
        // Bounded printable text, or none when the escapes do not decode.
        val description =
            section[ERROR_DESCRIPTION_PARAM]
                ?.first()
                ?.let { raw -> runCatching { java.net.URLDecoder.decode(raw, Charsets.UTF_8) }.getOrNull() }
                ?.filter { !it.isISOControl() }
                ?.take(MAX_ERROR_DESCRIPTION_LENGTH)
        return error.takeIf(errorShape::matches)?.let { AuthDeepLink.OAuthCallback(null, it, description) }
    }

    /**
     * The `name=value` pairs of one URI section, grouped by name. A repeated name keeps every
     * occurrence, so readers can treat more than one as ambiguous via [isDuplicated].
     */
    private fun paramsOf(section: String): Map<String, List<String>> {
        if (section.isEmpty()) return emptyMap()
        return section
            .split('&')
            .map { it.substringBefore('=') to it.substringAfter('=', "") }
            .groupBy({ it.first }, { it.second })
    }

    /** Whether any of [names] appears more than once in [params]. */
    private fun isDuplicated(
        params: Map<String, List<String>>,
        vararg names: String,
    ): Boolean = names.any { (params[it]?.size ?: 0) > 1 }

    /** The URI split this parser reads: the host+path, the query pairs, and the fragment pairs. */
    private data class Sections(
        val hostPath: String,
        val query: Map<String, List<String>>,
        val fragment: Map<String, List<String>>,
    )
}
