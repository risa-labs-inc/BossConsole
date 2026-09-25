package ai.rever.boss.plugin.logging

import org.slf4j.LoggerFactory
import java.net.URI
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Utilities for sanitizing sensitive data before logging.
 *
 * SECURITY: These functions MUST be used when logging any potentially sensitive data:
 * - Email addresses
 * - Tokens (access, refresh, magic link, etc.)
 * - Credential IDs
 * - URIs with authentication parameters
 * - User IDs
 *
 * ## Usage
 * ```kotlin
 * logger.info(LogCategory.AUTH, "Processing login",
 *     data = mapOf("email" to LogSanitizer.maskEmail(email)))
 * ```
 */
object LogSanitizer {
    // Use SLF4J directly to avoid recursive logging through BossLogger
    private val logger = LoggerFactory.getLogger("LogSanitizer")

    /**
     * Mask email address for logging.
     * Example: "user@example.com" -> "u***@e***.com"
     */
    fun maskEmail(email: String?): String {
        if (email.isNullOrBlank()) return "[empty]"

        return try {
            val parts = email.split("@")
            if (parts.size != 2) return "[invalid-email]"

            val localPart = parts[0]
            val domainParts = parts[1].split(".")

            val maskedLocal =
                if (localPart.length <= 1) {
                    "*"
                } else {
                    "${localPart.first()}${"*".repeat(minOf(localPart.length - 1, 3))}"
                }

            val maskedDomain =
                if (domainParts.isEmpty()) {
                    "[invalid-domain]"
                } else {
                    val firstDomainPart = domainParts.first()
                    val maskedFirstPart =
                        if (firstDomainPart.length <= 1) {
                            "*"
                        } else {
                            "${firstDomainPart.first()}${"*".repeat(minOf(firstDomainPart.length - 1, 3))}"
                        }
                    (listOf(maskedFirstPart) + domainParts.drop(1)).joinToString(".")
                }

            "$maskedLocal@$maskedDomain"
        } catch (ignored: Exception) {
            // Deliberately unlogged: LogSanitizer runs inside the logging pipeline,
            // so logging from here could recurse. The placeholder marks the failure.
            "[email-mask-error]"
        }
    }

    /**
     * Mask a token for logging.
     * Shows first 3 and last 3 characters only.
     * Example: "abc123def456ghi789" -> "abc...789"
     */
    fun maskToken(token: String?): String {
        if (token.isNullOrBlank()) return "[empty]"

        return if (token.length <= 6) {
            "***"
        } else {
            "${token.take(3)}...${token.takeLast(3)}"
        }
    }

    /**
     * Mask a credential ID for logging.
     * Replaces the entire ID with a placeholder.
     */
    fun maskCredentialId(credentialId: String?): String {
        if (credentialId.isNullOrBlank()) return "[empty]"
        return "[CREDENTIAL_ID:${credentialId.length}chars]"
    }

    /**
     * Mask a user ID for logging.
     * Shows first 4 characters only.
     */
    fun maskUserId(userId: String?): String {
        if (userId.isNullOrBlank()) return "[empty]"

        return if (userId.length <= 4) {
            "****"
        } else {
            "${userId.take(4)}..."
        }
    }

    /**
     * Mask sensitive parameters in URIs.
     * Redacts: token, access_token, refresh_token, code, error_description, id_token,
     * session_token, api_key, key, secret, sessionId, email — name-matched
     * case-insensitively, so the passkey ceremony's `sessionId` and the `email`
     * beside it leave the log with the token names.
     *
     * Example:
     * "boss://auth?token=abc123&type=signup" -> "boss://auth?token=[REDACTED]&type=signup"
     */
    fun maskUriParams(uri: String?): String {
        if (uri.isNullOrBlank()) return "[empty]"

        return try {
            // Redact any credential carried in an authority (scheme://user:password@host), including
            // a URL nested in the query or fragment, before masking query/fragment params.
            var result = redactUserInfo(uri, freeText = false)

            // Mask query parameters. Indices are read off `result`, not the original `uri`,
            // because redactUserInfo above can change the string's length.
            val queryStart = result.indexOf('?')
            if (queryStart >= 0) {
                result = maskParamsInSegment(result, queryStart + 1, '#', sensitiveUriParamNames)
            }

            // Mask fragment parameters
            val fragmentStart = result.indexOf('#')
            if (fragmentStart >= 0) {
                result = maskParamsInSegment(result, fragmentStart + 1, '\u0000', sensitiveUriParamNames)
            }

            result
        } catch (e: Exception) {
            logger.warn("URI masking failed: ${e.message}")
            "[uri-mask-error]"
        }
    }

    /**
     * Redact the userinfo of every URL in a line of free text, such as a line of `git clone`
     * output: `fatal: unable to access 'https://x-access-token:<token>@github.com/o/r.git/'` becomes
     * `fatal: unable to access 'https://[REDACTED]@github.com/o/r.git/'`.
     *
     * Unlike [sanitizeLogMessage], which replaces whole URLs, paths and hostnames, this removes only
     * the credential, so the rest of the line, including each host and port, stays readable. An
     * authority ends where it does for [maskUriParams], and also at whitespace, so an `@` later in
     * the sentence (an email address) is not read as a delimiter. The nested-URL `&` rule of
     * [maskUriParams] does not apply here.
     */
    fun redactUrlUserInfo(text: String): String = redactUserInfo(text, freeText = true)

    /**
     * Redact the userinfo component of every URL in [text]: `scheme://user:password@host` becomes
     * `scheme://[REDACTED]@host`. A credential is routinely carried there - a private HTTPS clone
     * URL is `https://x-access-token:<token>@github.com/...` - and [maskUriParams] used to return
     * it verbatim, since it masked only query and fragment parameters.
     *
     * Only an `@` inside an authority is a userinfo delimiter: the authority ends at the first
     * `/`, `?` or `#` after `://`, so an `@` in a path (`/@handle`) or a query value (an email) is
     * left alone. The LAST `@` in the authority is the delimiter, as in WHATWG URL parsing, so all
     * of `user:p@ss` is removed from `user:p@ss@host`; stopping at the first `@` would log `ss@host`.
     * The scheme, host, port and path are preserved.
     *
     * Every `://` is examined, not only the first, so a URL nested in a query or fragment value
     * (`?next=https://u:p@internal/`) is redacted too. Outside [freeText], a nested URL's authority
     * also ends at `&`, the outer query's separator, so a later `&contact=a@b.com` is not read as
     * its userinfo. With [freeText], every authority also ends at whitespace instead.
     *
     * A protocol-relative authority, `//user:pass@host/path` with no scheme, is redacted too
     * (BossConsole#1639): `Failed to connect to //alice:pass@10.0.0.5/x` otherwise reached
     * `filePathPattern`, which stops at the userinfo colon and left the password in the line. Such a
     * `//` counts only where a reference can begin - see [nextAuthorityStart] - so the `//` inside a
     * path (`/a//b@c`) or after a third slash is not read as one.
     *
     * Deliberately not handled, since the call sites log absolute URLs:
     * - a percent-encoded nested URL (`?next=https%3A%2F%2Fu%3Ap%40internal`) is unchanged, and a
     *   nested URL whose userinfo holds a literal `&` is not redacted;
     * - `\` does not end an authority. WHATWG parsing treats it as `/` in special schemes, so
     *   `https://evil.example\@good.example/x` loads `evil.example` but is logged as
     *   `https://[REDACTED]@good.example/x`, hiding the host that was actually visited.
     */
    private fun redactUserInfo(
        text: String,
        freeText: Boolean,
    ): String {
        var out: StringBuilder? = null
        var copiedUpTo = 0
        var nested = false
        var authorityStart = nextAuthorityStart(text, 0)
        while (authorityStart >= 0) {
            val authorityEnd = findAuthorityEnd(text, authorityStart, freeText, ampersandEnds = nested && !freeText)
            val at = text.lastIndexOf('@', authorityEnd - 1)
            if (at >= authorityStart) {
                val builder = out ?: StringBuilder(text.length)
                builder.append(text, copiedUpTo, authorityStart).append("[REDACTED]")
                out = builder
                copiedUpTo = at
            }
            nested = true
            authorityStart = nextAuthorityStart(text, authorityEnd)
        }
        return out?.append(text, copiedUpTo, text.length)?.toString() ?: text
    }

    /**
     * What may stand right before a protocol-relative `//`: the start of a quoted or bracketed
     * reference, or of a value.
     */
    private val protocolRelativeOpeners = setOf('\'', '"', '`', '(', '[', '{', '<', '=', ',', ';')

    /**
     * The index just past the next `//` at or after [from] that starts an authority, or -1. That is
     * every `://`, and a scheme-less `//` that begins a reference: at the start of [text], after
     * whitespace, or after one of [protocolRelativeOpeners]. Anything else before it - a letter, a
     * `/`, a `.` - means the `//` sits inside a path or a word, and a third `/` after it means an
     * empty authority (`file:///x`), which has no userinfo to remove.
     */
    private fun nextAuthorityStart(
        text: String,
        from: Int,
    ): Int {
        var slashes = text.indexOf("//", from)
        while (slashes >= 0) {
            val before = text.getOrNull(slashes - 1)
            val opensReference =
                before == ':' || before == null || before.isWhitespace() || before in protocolRelativeOpeners
            if (opensReference) return slashes + 2
            slashes = text.indexOf("//", slashes + 1)
        }
        return -1
    }

    private fun findAuthorityEnd(
        text: String,
        start: Int,
        freeText: Boolean,
        ampersandEnds: Boolean,
    ): Int {
        var i = start
        while (i < text.length && !endsAuthority(text[i], freeText, ampersandEnds)) {
            i++
        }
        return i
    }

    private fun endsAuthority(
        c: Char,
        freeText: Boolean,
        ampersandEnds: Boolean,
    ): Boolean =
        when (c) {
            '/', '?', '#' -> true
            '&' -> ampersandEnds
            else -> freeText && c.isWhitespace()
        }

    private fun maskParamsInSegment(
        uri: String,
        startIndex: Int,
        endChar: Char,
        sensitiveParams: Set<String>,
    ): String {
        // Searched from startIndex, not from 0. A URL whose fragment precedes its query
        // (`https://app/#/reset?token=...`, the ordinary shape of a hash-routed callback)
        // otherwise found the '#' BEFORE the segment being masked, producing an endIndex
        // below startIndex and a StringIndexOutOfBoundsException out of substring. The
        // caller's catch turned that into "[uri-mask-error]", so the whole URL was lost
        // from the log rather than masked.
        val endIndex =
            if (endChar == '\u0000') {
                uri.length
            } else {
                uri.indexOf(endChar, startIndex).let { if (it < 0) uri.length else it }
            }
        val segment = uri.substring(startIndex, endIndex)

        val maskedSegment =
            segment.split("&").joinToString("&") { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2 && sensitiveParams.any { parts[0].equals(it, ignoreCase = true) }) {
                    "${parts[0]}=[REDACTED]"
                } else {
                    param
                }
            }

        return uri.substring(0, startIndex) + maskedSegment + uri.substring(endIndex)
    }

    /**
     * Mask a session ID for logging.
     * Shows first 8 characters only.
     */
    fun maskSessionId(sessionId: String?): String {
        if (sessionId.isNullOrBlank()) return "[empty]"

        return if (sessionId.length <= 8) {
            "****"
        } else {
            "${sessionId.take(8)}..."
        }
    }

    /**
     * Describe a URI safely without exposing sensitive parameters.
     * Returns the scheme, host and path without query, fragment, port or userinfo.
     *
     * Example: "boss://auth/verify?token=abc" -> "boss://auth/verify (with query params)"
     *
     * A reference with no scheme, such as `localhost` or `example.com/a?q=1`, is described without
     * one (`localhost`, `example.com/a (with query params)`) rather than as `null://...`.
     *
     * The description always says something: a host `java.net.URI` will not parse is still named (see
     * [hostFromRawAuthority]), and a reference that is only delimiters reads `[empty reference]`
     * rather than an empty string. The userinfo, the port, the query and the fragment are always
     * dropped, whether or not the parser could read the authority.
     */
    fun describeUri(uri: String?): String {
        if (uri.isNullOrBlank()) return "[empty]"

        return try {
            val parsed = URI(uri)
            val hasQuery = !parsed.rawQuery.isNullOrBlank()
            val hasFragment = !parsed.rawFragment.isNullOrBlank()

            val base = describedBase(parsed)
            val suffix =
                when {
                    hasQuery && hasFragment -> " (with query and fragment)"
                    hasQuery -> " (with query params)"
                    hasFragment -> " (with fragment)"
                    else -> ""
                }

            val described = if (base.isEmpty()) suffix.trimStart() else base + suffix

            // A reference that is only delimiters (`?`, `#`, `?#`) has an empty base, an empty query
            // and an empty fragment, so every branch above contributes nothing and the caller used to
            // log `uri=` with no value at all - indistinguishable from a line that logged nothing.
            // Distinct from the `[empty]` above, which says the input itself was absent or blank.
            described.ifEmpty { "[empty reference]" }
        } catch (ignored: Exception) {
            // Deliberately unlogged: LogSanitizer runs inside the logging pipeline,
            // so logging from here could recurse. The placeholder marks the failure.
            "[uri-parse-error]"
        }
    }

    /** `scheme://host/path`; for a reference with no scheme, `//host/path` or just the path. */
    private fun describedBase(parsed: URI): String {
        val scheme = parsed.scheme?.let { "$it://" }
        val host = parsed.host ?: hostFromRawAuthority(parsed.rawAuthority)
        val authority = host?.let { if (scheme == null) "//$it" else it }
        return scheme.orEmpty() + authority.orEmpty() + parsed.rawPath.orEmpty()
    }

    /**
     * The host of an authority `java.net.URI` declined to parse as one, or null if there is none.
     *
     * `URI.getHost()` is null whenever the authority is not a legal RFC 2396 hostname or IP literal,
     * which covers an underscore (`web_server`, an ordinary intranet or container name) and any
     * non-ASCII label. [describedBase] read `getHost()` alone, so those URLs were described with the
     * authority missing entirely: a cookie rejected for `https://my_host.example.com/` was logged as
     * `https:///`, which names nothing and cannot be told apart from any other such host.
     *
     * The raw authority has to be split here rather than read off the parser, because `getRawUserInfo()`
     * and `getPort()` are null and -1 in exactly the same cases - measured on JDK 17, not assumed.
     * Both parts are removed, which is what `getHost()` already gives when the parse succeeds, so a
     * host reaches the log the same way whether or not the parser could read it:
     *
     * - Everything up to the LAST `@` is userinfo and is dropped. That is the one part of an authority
     *   that is routinely a credential (`https://x-access-token:<token>@host/...`), and splitting on
     *   the first `@` instead would keep the tail of a password that contains one.
     * - A trailing `:<digits>` is a port and is dropped. Requiring digits keeps a colon that is not a
     *   port, such as the malformed `x_y.internal:80a`, rather than cutting the name at it.
     * - An authority that is nothing but userinfo (`//user:pass@/a`) yields null rather than an empty
     *   host, so the scheme-less branch of [describedBase] does not emit a bare `//`.
     *
     * An IPv6 literal is deliberately not a case here: `[::1]`, `[::1]:8080` and even a zone id such
     * as `[fe80::1%25eth0]` all parse, so `getHost()` answers and this is never reached, and a
     * malformed one such as `[::1` throws out of `URI` before it. Measured on JDK 17.
     *
     * This names a host; it does not validate one. An authority this describes is by definition one
     * the parser rejected, so the result is the text between the delimiters and nothing more.
     */
    private fun hostFromRawAuthority(rawAuthority: String?): String? {
        if (rawAuthority.isNullOrEmpty()) return null

        val afterUserInfo = rawAuthority.substringAfterLast('@')
        val portSeparator = afterUserInfo.lastIndexOf(':')
        val host =
            if (portSeparator > 0 && afterUserInfo.drop(portSeparator + 1).all { it.isDigit() }) {
                afterUserInfo.take(portSeparator)
            } else {
                afterUserInfo
            }
        return host.ifEmpty { null }
    }

    // -------------------------------------------------------------------------
    // Patterns and vocabularies for sanitization.
    //
    // All compiled/allocated once: every log line and every crash report is put
    // through them, so none of this may be rebuilt per call.
    // -------------------------------------------------------------------------

    private val filePathPattern = Regex("""(?:/[^\s:]+)+|(?:[A-Za-z]:\\[^\s:]+)+""")
    private val urlPattern = Regex("""https?://[^\s]+""")
    private val emailPattern = Regex("""[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""")

    /**
     * Private DNS names that can reveal an organisation's internal topology in
     * network failures. Kept to the private-style suffixes measured in #109 so
     * ordinary dotted prose and package names remain diagnostic. Case folding intentionally
     * also masks ambiguous constants such as `Status.INTERNAL`: free text cannot distinguish
     * these from private DNS names. Run before the public matcher to avoid exposing a mixed-case
     * leading label. Both hostname passes preserve ports because they remain useful diagnostics.
     * A terminal period is punctuation (or a DNS root dot); a following label blocks the match.
     */
    private val privateHostnamePattern =
        Regex(
            """(?<![A-Za-z0-9_.-])(?:[A-Za-z0-9-]+\.)+(?:internal|local)(?![A-Za-z0-9_-])(?!\.[A-Za-z0-9_-])""",
            RegexOption.IGNORE_CASE,
        )

    /**
     * A bare hostname with no protocol/path around it - the shape `UnknownHostException.getMessage()`
     * and every DNS/proxy-connect failure produces (BossConsole#109). [filePathPattern] needs a `/`,
     * [urlPattern] needs `http`, [emailPattern] needs `@` - none of them fire on this shape, so
     * `proxy.corp.internal:3128` or a bare `api.risaboss.com` passed through every prior revision of
     * this file untouched, and for a user behind a corporate proxy the failing hostname *is* the
     * sensitive part - it names an employer and an internal topology.
     *
     * Deliberately narrow: labels are lowercase-hostname-shaped (`[a-z0-9-]`), and the trailing label
     * must be one of a short, explicit list of real TLDs or `internal`/`local` - not "any 2-6 letter
     * word," which would eat ordinary lowercase prose. The lowercase requirement is also what keeps a
     * fully-qualified Kotlin/Java exception class name (`ai.rever.boss.services.supabase.SecretService`)
     * from matching: its trailing segment is PascalCase, and no legitimate exception class happens to
     * end in a bare `.com`/`.io`/etc. word.
     *
     * `internal` and `io` are also real lowercase package-name segments
     * (`kotlinx.coroutines.internal.ScopeCoroutine`, `kotlinx.io.EOFException`), and the lowercase
     * rule alone does not rule those out - a package path is lowercase right up to the class name.
     * What distinguishes them is what follows: a hostname's TLD is the end of the token, while a
     * package segment is immediately followed by `.NextSegment`. The trailing
     * `(?!\.[A-Za-z])` is that check - measured directly against a realistic stack trace
     * (`sanitizeStackTrace leaves a realistic Kotlin trace intact`) after the first version of this
     * pattern redacted `kotlinx.coroutines.internal` out of one.
     *
     * Coverage limits: multi-level public suffixes such as `.co.uk` are rejected by that same
     * guard. This public-host matcher remains case-sensitive, so
     * mixed-case public hosts are untouched. The preceding private-host pass handles complete
     * mixed-case `.internal`/`.local` names and preserves their ports. Unlisted suffixes and IP literals also
     * remain unchanged. Ports are preserved, as in the private-host pass. The private suffixes
     * remain here to preserve existing lowercase matching outside the stricter private boundaries.
     * This is selected lowercase-host redaction, not complete DNS redaction.
     */
    private val hostnamePattern =
        Regex(
            """\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+""" +
                """(?:internal|local|com|net|org|io|dev|app|co|ai|gov|edu|mil|info|biz)\b""" +
                """(?!\.[A-Za-z])""",
        )

    /**
     * Runs of text that are a credential by their own structure, wherever they
     * appear: a JWT (three base64url segments — the first is the base64url of a
     * JSON header, which is why every JWT begins `eyJ`), a GitHub token prefix,
     * a vendor `sk_`/`pk_` key prefix, or a Supabase `sb_publishable_`/`sb_secret_` key.
     *
     * The Supabase branch names the two published prefixes rather than any
     * `sb_`, so an ordinary identifier is not masked. `sb_secret_` is the
     * service_role replacement and bypasses row-level security. This pattern is the
     * original: it is duplicated in `McpArgumentSanitizer.credentialShapePattern` and
     * pinned against this one by `McpArgumentSanitizerCredentialShapeTest`.
     *
     * Each alternative is anchored on the left by a boundary that rules out word
     * characters and `.`, so a name that merely *contains* one of these prefixes
     * keeps its text: `task_manager_configuration` is not a Stripe key, and
     * neither is `com.example.pk_utilities`. That precision is what makes the
     * pattern safe to run over message text and stack traces, which consist
     * mostly of long class and method names.
     */
    private val credentialShapePattern =
        Regex(
            "(?<![A-Za-z0-9_.])(?:" +
                """eyJ[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*""" +
                "|(?:gh[pousr]_|github_pat_)[A-Za-z0-9_]{8,}" +
                "|(?:sk|pk)[-_][A-Za-z0-9_-]{8,}" +
                "|sb_(?:publishable|secret)_[A-Za-z0-9_-]{8,}" +
                ")",
        )

    /**
     * A `name=value` pair inside a message — the shape a quoted config line,
     * environment entry or command line arrives in. Group 1 is the name, group 2
     * the value.
     *
     * Only the value is ever masked. The name is what makes a report actionable
     * (it says *which* setting was wrong) and is not itself the secret.
     *
     * A value whose first character is `[` is excluded, so a value an earlier
     * pass already replaced keeps the more informative result: `token=/home/me/x`
     * becomes `token=[PATH]` rather than degrading further to `token=***`.
     */
    private val assignmentPattern = Regex("""(?<![A-Za-z0-9_.])([A-Za-z][A-Za-z0-9_.-]*)=([^\s\[][^\s]*)""")

    /**
     * A sensitive-named query or fragment parameter shape — `?token=…`,
     * `&access_token=…`, `#access_token=…` — redacted before [filePathPattern]
     * gets a chance to see it. These shapes are redacted even without a full URL.
     * Parameters use ampersand separators; legacy semicolon separators and
     * percent-encoded parameter names are not interpreted by this text matcher.
     *
     * BossConsole#109 (review comment): [filePathPattern]'s `[^\s:]+` stops at
     * the first colon, on the (correct, elsewhere) assumption that a colon
     * there marks a `host:port` boundary worth preserving. A value that
     * happens to contain one — `token=abc:def` — is only partly consumed, so
     * the tail survives the later replace verbatim: `[url=https://…?token=
     * abc:def]` became `[url=https:[PATH]:def]`, leaking a fragment of the
     * token. Measured, not hypothetical.
     *
     * Running this first removes the value entirely wherever [nameMarksSecret]
     * says the name is sensitive, so there is nothing containing a colon left
     * by the time [filePathPattern] runs — the fix is in what reaches that
     * pass, not in loosening its own boundary (which exists for the
     * `host:port` case this pass does not touch: no `?`/`&`/`#` precedes it).
     * Only parameter/fragment separators and whitespace terminate the value.
     * Punctuation such as `)` and an apostrophe is legal inside a URI value;
     * treating it as a log wrapper can leave a later colon and secret suffix
     * exposed. Conservatively consume adjacent wrapper punctuation too, as
     * the following path pass already does for colon-free URLs.
     */
    private val sensitiveQueryParamPattern = Regex("""([?&#])([A-Za-z][A-Za-z0-9_.-]*)=([^&#\s]+)""")

    /** Inserts a word boundary into camelCase names, so `accessToken` splits like `access_token`. */
    private val camelCaseBoundary = Regex("""(?<=[a-z0-9])(?=[A-Z])""")

    // Exact URL names stay separate: free-text exit_code and status_code are diagnostics.
    // `sessionid` is the passkey ceremony's own query name (a UUID handle, not a
    // `session_token` credential), and `email` rides along on the same WebAuthn URL
    // the ceremony opens, so both must leave masked-URI log lines too.
    private val sensitiveUriParamNames =
        setOf(
            "token",
            "access_token",
            "refresh_token",
            "code",
            "error_description",
            "id_token",
            "session_token",
            "api_key",
            "key",
            "secret",
            "sessionid",
            "email",
        )

    /**
     * Names whose value is sensitive. Shared by [sanitizeMap] and the
     * `name=value` pass of [redactLocationsAndCredentials] so that the map path
     * and the free-text path cannot drift apart; [nameMarksSecret] explains why
     * free text matches this list more strictly than a map key does.
     */
    private val sensitiveValueNames =
        setOf(
            "token",
            "access_token",
            "refresh_token",
            "password",
            "secret",
            "api_key",
            "key",
            "credential",
            "credential_id",
        )

    /**
     * Values kept verbatim even under a sensitive name: they cannot be
     * credential material, and they answer the question a reader actually has.
     * `token=null` is a useful thing to read; `token=***` is not.
     */
    private val nonSecretValues = setOf("null", "true", "false")

    /**
     * Check if a string looks like it might be a token/secret.
     * Used for defensive logging to avoid accidentally logging secrets.
     *
     * This is the *value-position* test: the argument is a single datum a caller
     * chose to log, so mere length is reason enough to mask it here. Message text
     * is held to the stricter [credentialShapePattern] instead, because a run of
     * 20-plus characters inside a sentence or a stack frame is usually just a
     * long identifier.
     */
    fun looksLikeSecret(value: String?): Boolean {
        if (value.isNullOrBlank()) return false

        // Check for common patterns.
        //
        // A former `^[a-zA-Z0-9_-]{20,}$` alternative is gone: it required a
        // length of 20 or more, so the first condition below already covered
        // every string it could match, and it compiled a fresh Regex per call.
        return value.length >= 20 ||
            value.contains("eyJ") || // JWT prefix
            value.contains("sk_") ||
            value.contains("pk_") ||
            value.contains("ghp_") ||
            value.contains("gho_") ||
            credentialShapePattern.containsMatchIn(value)
    }

    /**
     * Safely format a map for logging, masking known sensitive keys.
     *
     * A key is matched against [sensitiveValueNames] by substring: the caller
     * named this field deliberately, so `userAccessTokenV2` redacts like `token`.
     * Message text is matched more narrowly — see [nameMarksSecret].
     */
    fun sanitizeMap(map: Map<String, Any?>?): Map<String, Any?> {
        if (map == null) return emptyMap()

        return map.mapValues { (key, value) ->
            when {
                sensitiveValueNames.any { key.contains(it, ignoreCase = true) } -> "[REDACTED]"
                value is String && looksLikeSecret(value) -> maskToken(value)
                else -> value
            }
        }
    }

    /**
     * Whether the name of a `name=value` pair marks its value as sensitive.
     *
     * Matched per word rather than by substring, which is the one deliberate
     * difference from [sanitizeMap]'s key test. A map key is a field the caller
     * named; a name lifted out of arbitrary message text is not, and substring
     * matching there would mask the value of `KEYBOARD_LAYOUT` for containing
     * "key". Words are split on the separators [assignmentPattern] admits, plus
     * camelCase boundaries, so `SUPABASE_ANON_KEY`, `api_key` and `apiKey` all
     * yield a "key" word while `KEYBOARD_LAYOUT` yields "keyboard".
     *
     * The multi-word entries of [sensitiveValueNames] ("access_token",
     * "credential_id", ...) can never equal a single word; their "token", "key"
     * and "credential" words do, so nothing is left uncovered.
     */
    private fun nameMarksSecret(name: String): Boolean =
        name
            .replace(camelCaseBoundary, "_")
            .split('_', '-', '.')
            .any { word -> word.isNotEmpty() && sensitiveValueNames.any { word.equals(it, ignoreCase = true) } }

    /**
     * The shared body of [sanitizeExceptionMessage] and [sanitizeStackTrace].
     *
     * The order of the passes is deliberate. Locations go first, and
     * [filePathPattern]'s `(?:/[^\s:]+)+` consumes everything after a scheme's
     * colon — so a value carried in a URL query string or fragment, which is the
     * usual way one reaches a message, is already `[PATH]` by the time the later
     * passes see the text. Those later passes exist for what that cannot reach:
     * a credential written into a message on its own, with no URL or path around
     * it.
     *
     * The passes compose in either order because [maskToken] is a fixed point on
     * its own output at these lengths (`ghp...345` masks to `ghp...345`), so a
     * value both of them match is masked once in effect.
     *
     * [sensitiveQueryParamPattern] runs before all of that, for a narrower
     * reason: it is the one pass that must see the *original* text, since its
     * whole job is removing a colon before [filePathPattern] can trip on it
     * (BossConsole#109). Running it any later would be too late by definition.
     *
     * [redactUrlUserInfo] runs next, and for the same reason. A URL's userinfo
     * sits before the host, so [filePathPattern] reaches `//alice` first and
     * leaves `http:[PATH]:pass@10.0.0.5:3128` - the password still in the line.
     * It has to see the authority intact, so it goes ahead of the locations and
     * behind the query pass, which does not touch an authority. Only the
     * credential is removed; what survives is masked as a location as before.
     * BossConsole#640 closed this for [maskUriParams] and recorded the free-text
     * path as a separate change.
     */
    private fun redactLocationsAndCredentials(text: String): String {
        val withMaskedQueryParams =
            sensitiveQueryParamPattern.replace(text) { match ->
                val (prefix, name, value) = match.destructured
                val sensitive =
                    nameMarksSecret(name) || sensitiveUriParamNames.any { name.equals(it, ignoreCase = true) }
                if (sensitive && value.lowercase() !in nonSecretValues) {
                    "$prefix$name=[REDACTED]"
                } else {
                    match.value
                }
            }

        val withoutLocations =
            redactUrlUserInfo(withMaskedQueryParams)
                .replace(filePathPattern, "[PATH]")
                .replace(urlPattern, "[URL]")
                .replace(emailPattern, "[EMAIL]")
                .replace(privateHostnamePattern, "[HOST]")
                .replace(hostnamePattern, "[HOST]")

        val withMaskedAssignments =
            assignmentPattern.replace(withoutLocations) { match ->
                val (name, value) = match.destructured
                if (nameMarksSecret(name) && value.lowercase() !in nonSecretValues) {
                    "$name=${maskToken(value)}"
                } else {
                    match.value
                }
            }

        return credentialShapePattern.replace(withMaskedAssignments) { match -> maskToken(match.value) }
    }

    /**
     * Sanitize an exception message by removing potentially sensitive data.
     *
     * Removes:
     * - File paths (Unix and Windows)
     * - URLs
     * - Email addresses
     * - Bare hostnames (no protocol/path around them - DNS and proxy-connect failures)
     * - Credentials recognisable by shape: JWTs, GitHub tokens, `sk_`/`pk_` keys, Supabase `sb_` keys
     * - The value of a `name=value` pair whose name marks it sensitive
     *
     * @param message The exception message to sanitize
     * @return The sanitized message
     */
    fun sanitizeExceptionMessage(message: String?): String {
        if (message.isNullOrBlank()) return "[no message]"

        return try {
            redactLocationsAndCredentials(message)
        } catch (ignored: Exception) {
            // Deliberately unlogged: LogSanitizer runs inside the logging pipeline,
            // so logging from here could recurse. The placeholder marks the failure.
            "[sanitization-error]"
        }
    }

    /**
     * Sanitize a log message by removing potentially sensitive data.
     * Uses the same rules as sanitizeExceptionMessage.
     *
     * @param message The log message to sanitize
     * @return The sanitized message
     */
    fun sanitizeLogMessage(message: String?): String = sanitizeExceptionMessage(message)

    /**
     * Sanitize a stack trace by removing file paths and other sensitive data.
     *
     * Applies the same rules as [sanitizeExceptionMessage], which a stack trace
     * needs too: the `Caused by:` lines of a trace are exception messages, and a
     * value quoted into one arrives here rather than there.
     *
     * @param stackTrace The stack trace string to sanitize
     * @return The sanitized stack trace
     */
    fun sanitizeStackTrace(stackTrace: String?): String {
        if (stackTrace.isNullOrBlank()) return "[no stack trace]"

        return try {
            redactLocationsAndCredentials(stackTrace)
        } catch (ignored: Exception) {
            // Deliberately unlogged: LogSanitizer runs inside the logging pipeline,
            // so logging from here could recurse. The placeholder marks the failure.
            "[sanitization-error]"
        }
    }

    /**
     * Sanitize a stack frame source file name by removing directory paths and sensitive data.
     *
     * In standard JVM stack traces, [StackTraceElement.getFileName] is a simple filename like
     * `BossLogger.kt`. Hand-built or foreign stack frames may embed absolute or relative paths
     * (e.g. `/Users/ci/keys.pem` or `C:\Users\secret\keys.pem`) or credential shapes.
     *
     * @param fileName The stack frame file name to sanitize
     * @return The sanitized file name, or null if [fileName] was null
     */
    fun sanitizeFileName(fileName: String?): String? =
        when {
            fileName.isNullOrEmpty() -> {
                fileName
            }

            fileName.contains('/') || fileName.contains('\\') -> {
                "[PATH]"
            }

            else -> {
                val sanitized = sanitizeExceptionMessage(fileName)
                if (sanitized == "[no message]") fileName else sanitized
            }
        }

    /**
     * Sanitize a [StackTraceElement] by removing directory paths and sensitive data from its filename.
     *
     * If the frame's filename is null or already clean, returns the original frame.
     * Otherwise reconstructs a new [StackTraceElement] with the sanitized filename.
     *
     * @param frame The stack trace element to sanitize
     * @return The sanitized stack trace element
     */
    fun sanitizeStackTraceElement(frame: StackTraceElement): StackTraceElement {
        val rawFileName = frame.fileName ?: return frame
        val sanitizedFileName = sanitizeFileName(rawFileName)
        return if (sanitizedFileName == rawFileName) {
            frame
        } else {
            createStackTraceElement(frame, sanitizedFileName)
        }
    }

    private fun createStackTraceElement(
        frame: StackTraceElement,
        sanitizedFileName: String?,
    ): StackTraceElement =
        if (frame.classLoaderName != null || frame.moduleName != null) {
            StackTraceElement(
                frame.classLoaderName,
                frame.moduleName,
                frame.moduleVersion,
                frame.className,
                frame.methodName,
                sanitizedFileName,
                frame.lineNumber,
            )
        } else {
            StackTraceElement(
                frame.className,
                frame.methodName,
                sanitizedFileName,
                frame.lineNumber,
            )
        }

    /**
     * Sanitize a [Throwable] by wrapping it in a [SanitizedThrowable] with sanitized message,
     * cause chain, suppressed exceptions, and stack trace frames.
     *
     * Preserves the original exception type name, frame class/method names, and line numbers while
     * replacing directory paths in frame filenames (such as `/Users/ci/keys.pem`) with `[PATH]`.
     * Guards against cyclic cause chains via an identity-visited set.
     *
     * @param error The throwable to sanitize
     * @return The sanitized throwable, or null if [error] was null
     */
    fun sanitizeThrowable(error: Throwable?): Throwable? =
        when {
            error == null -> null
            error is SanitizedThrowable -> error
            else -> sanitizeThrowableInternal(error, Collections.newSetFromMap(IdentityHashMap()))
        }

    private fun sanitizeThrowableInternal(
        error: Throwable?,
        visited: MutableSet<Throwable>,
    ): Throwable? {
        if (error == null || !visited.add(error)) return null

        val originalClassName = (error as? SanitizedThrowable)?.originalClassName ?: error.javaClass.name
        val sanitizedMessage = error.message?.let { sanitizeExceptionMessage(it) }
        val sanitizedCause = error.cause?.let { sanitizeThrowableInternal(it, visited) }

        val sanitized =
            SanitizedThrowable(
                originalClassName = originalClassName,
                sanitizedMessage = sanitizedMessage,
                cause = sanitizedCause,
            )

        sanitized.stackTrace = error.stackTrace.map { sanitizeStackTraceElement(it) }.toTypedArray()

        for (suppressed in error.suppressed) {
            val sanitizedSuppressed = sanitizeThrowableInternal(suppressed, visited)
            if (sanitizedSuppressed != null) {
                sanitized.addSuppressed(sanitizedSuppressed)
            }
        }

        return sanitized
    }
}

/**
 * A [Throwable] wrapper whose message and stack trace frames have been sanitized.
 *
 * Preserves [originalClassName] so that [toString] and [Throwable.printStackTrace]
 * report the true exception type (e.g. `java.io.FileNotFoundException`) rather than
 * erasing exception classification across log listeners and crash reporters.
 *
 * @param originalClassName The fully qualified class name of the original throwable
 * @param sanitizedMessage The sanitized exception message, or null if original had none
 * @param cause The sanitized cause, or null
 */
class SanitizedThrowable(
    val originalClassName: String,
    sanitizedMessage: String?,
    cause: Throwable? = null,
) : Throwable(sanitizedMessage, cause) {
    override fun toString(): String {
        val msg = localizedMessage
        return if (msg != null) "$originalClassName: $msg" else originalClassName
    }
}
