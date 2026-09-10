package ai.rever.boss.services

/**
 * Whether a URL the OS handed BOSS is one BOSS should open in a browser tab.
 *
 * Pure and separate from [URLHandlerService] so the rules can be tested without
 * a window, a plugin or an event bus - the previous inline version had none of
 * its behaviour covered, and one of its rules was wrong.
 *
 * **The rule that was wrong**: the host had to contain a `.`, which silently
 * rejected `http://localhost:3000`. For an app whose users are developers, the
 * single most common link they click is the one pointing at their own dev
 * server, and BOSS dropped it with a "Invalid URL" line in the log and no tab.
 * A dot is not what makes a host valid; it is not required by DNS (`localhost`,
 * an intranet single-label name, a Docker service name) and it is not present in
 * a bracketed IPv6 literal either.
 *
 * What is still enforced, because this input arrives from any program on the
 * machine that can ask the OS to open a URL:
 *
 * - the scheme is http or https and nothing else. `file:`, `javascript:` and
 *   `data:` in particular must never reach a browser tab from here.
 * - there is an authority, and it is well formed: no spaces, no control
 *   characters, no embedded credentials.
 */
internal object UrlOpenValidation {
    private val ALLOWED_SCHEMES = listOf("http://", "https://")

    /**
     * Characters that must never appear in an authority.
     *
     * Tabs, newlines and NUL are covered by the `isISOControl` test at the call
     * site; these are the printable ones that are not.
     */
    private val FORBIDDEN_IN_AUTHORITY = charArrayOf(' ', '\u00A0', '\\', '"', '<', '>')

    fun isOpenable(url: String): Boolean {
        val scheme = ALLOWED_SCHEMES.firstOrNull { url.startsWith(it, ignoreCase = true) } ?: return false

        val afterScheme = url.substring(scheme.length)
        // The authority ends at the first '/', '?' or '#'. Taken from the string
        // rather than java.net.URI on purpose: URI.getHost() returns null for
        // hosts it considers malformed (an underscore in a Docker service name,
        // for one), which would reject links that browsers open fine.
        val authorityEnd = afterScheme.indexOfAny(charArrayOf('/', '?', '#'))
        val authority = if (authorityEnd >= 0) afterScheme.substring(0, authorityEnd) else afterScheme

        if (authority.isEmpty()) return false
        if (authority.any { it in FORBIDDEN_IN_AUTHORITY || it.isISOControl() }) return false

        // Credentials in the authority are how a link disguises its real
        // destination ("https://apple.com@evil.example"). Nothing BOSS does
        // needs them, so they are refused rather than stripped.
        if (authority.contains('@')) return false

        val host = hostOf(authority) ?: return false
        return host.isNotEmpty()
    }

    /**
     * The host part of an authority, or null when the authority is malformed.
     *
     * Handles the bracketed IPv6 form (`[::1]:8080`) explicitly: splitting on
     * the last colon would otherwise cut a bare IPv6 literal in half, and
     * splitting on the first would take `::1` apart.
     */
    private fun hostOf(authority: String): String? {
        if (authority.startsWith('[')) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            val host = authority.substring(1, close)
            val rest = authority.substring(close + 1)
            if (rest.isNotEmpty() && !isValidPortSuffix(rest)) return null
            return host.ifEmpty { null }
        }

        val colon = authority.indexOf(':')
        if (colon < 0) return authority
        if (!isValidPortSuffix(authority.substring(colon))) return null
        return authority.substring(0, colon).ifEmpty { null }
    }

    /**
     * A numeric port from 0 through 65535, or `:` for the default port.
     * Zero is deliberately accepted as a numeric value, not rewritten to the default.
     * This syntax gate leaves unsafe-port policy and reachability to the browser.
     */
    private fun isValidPortSuffix(suffix: String): Boolean {
        if (!suffix.startsWith(':')) return false
        // Leading zeros do not affect the port value, even when their count
        // would overflow an integer parser. Empty and all-zero ports are valid.
        val port = suffix.drop(1).trimStart('0')
        // `in '0'..'9'`, not Char.isDigit(): isDigit is true for every Unicode
        // decimal digit, so ":٣३" (Arabic-Indic and Devanagari threes)
        // validated as a port and the URL reached the browser. Verified with
        // Character.isDigit on the JDK in use.
        return port.isEmpty() || (port.all { it in '0'..'9' } && port.toIntOrNull()?.let { it <= 65535 } == true)
    }
}
