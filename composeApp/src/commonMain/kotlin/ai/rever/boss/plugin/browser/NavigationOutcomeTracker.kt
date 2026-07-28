package ai.rever.boss.plugin.browser

/** What a finished navigation means for the stores that record visits. */
enum class NavigationVerdict {
    /** Not about the page the user is on (a sub-frame, or no URL to key on). */
    IGNORED,

    /** A real page loaded — record the visit. */
    LOADED,

    /** An error page, an aborted load, or a network error — record nothing. */
    FAILED,
}

/**
 * Classify a finished navigation from the facts the browser engine reports.
 *
 * Kept free of engine types so the rule itself is testable: everything about which
 * [com.teamdev.jxbrowser.net.NetError] values mean what stays at the call site.
 *
 * "Never committed" counts as failure because nothing was shown — a load that turned into
 * a download, or was replaced before it painted, is not a page anyone visited.
 */
fun classifyNavigation(
    isMainFrame: Boolean,
    hasUrl: Boolean,
    isErrorPage: Boolean,
    hasCommitted: Boolean,
    hasNetworkError: Boolean,
): NavigationVerdict =
    when {
        !isMainFrame || !hasUrl -> NavigationVerdict.IGNORED
        isErrorPage || !hasCommitted || hasNetworkError -> NavigationVerdict.FAILED
        else -> NavigationVerdict.LOADED
    }

/**
 * Remembers which addresses just failed to load, so the stores that record visits can
 * skip them.
 *
 * A browser reports a title and a "load finished" for a URL that never loaded — typing
 * `youtube.como` still commits an error page, still fires TitleChanged, and used to land
 * in the URL history and the dashboard's recent pages, where it came back as a suggestion
 * forever. The engine is the only layer that knows a navigation ended on an error page,
 * so it records the outcome here and the stores consult it before recording a visit.
 *
 * Entries are keyed by [canonicalUrlKey] so the URL the engine committed
 * (`https://youtube.como/`) matches the one a caller reports (`youtube.como`).
 *
 * **Failures expire.** A verdict is only meant to cover the moment between a navigation
 * finishing and the callbacks that follow it, and [FAILURE_TTL_MS] enforces that rather
 * than trusting a later success on the same key to clear it — a redirect commits a
 * *different* URL, so the address the user actually asked for would otherwise stay
 * "failed" for the rest of the session and quietly drop every later visit to it.
 */
object NavigationOutcomeTracker {
    /**
     * How long a failure verdict stays authoritative. Comfortably longer than the gap
     * between a navigation finishing and its title/load callbacks (milliseconds), short
     * enough that a stale verdict can't shadow a page the user goes back to.
     */
    const val FAILURE_TTL_MS = 30_000L

    /** Bounds memory if a page redirect-loops through failures; oldest keys go first. */
    private const val MAX_TRACKED_FAILURES = 256

    private val failedUrls = LinkedHashMap<String, Long>()

    /** Record that a main-frame navigation to [url] committed a real page. */
    fun recordSuccess(url: String) {
        val key = canonicalUrlKey(url)
        if (key.isEmpty()) return
        synchronized(failedUrls) {
            failedUrls.remove(key)
        }
    }

    /** Record that a main-frame navigation to [url] ended on an error page (or never committed). */
    fun recordFailure(
        url: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val key = canonicalUrlKey(url)
        if (key.isEmpty()) return
        synchronized(failedUrls) {
            // Re-insert so a repeated failure counts as the most recent entry.
            failedUrls.remove(key)
            failedUrls[key] = now
            while (failedUrls.size > MAX_TRACKED_FAILURES) {
                failedUrls.remove(failedUrls.keys.first())
            }
        }
    }

    /** Whether a main-frame navigation to [url] failed within the last [FAILURE_TTL_MS]. */
    fun didFail(
        url: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val key = canonicalUrlKey(url)
        if (key.isEmpty()) return false
        return synchronized(failedUrls) {
            val failedAt = failedUrls[key]
            when {
                failedAt == null -> {
                    false
                }

                now - failedAt <= FAILURE_TTL_MS -> {
                    true
                }

                else -> {
                    failedUrls.remove(key)
                    false
                }
            }
        }
    }

    /** Drop all tracked outcomes. */
    internal fun clear() {
        synchronized(failedUrls) { failedUrls.clear() }
    }
}

/**
 * The host of [url] when it is an address worth remembering, else null.
 *
 * Both stores match and display by domain, so anything without one — `about:blank`,
 * `data:`, `blob:`, `chrome://`, `file://` — has nothing to contribute to a suggestion
 * list, and an unparsable URL has no business in one either. Shared so the URL history
 * and the dashboard's recent pages can't drift apart on what counts as a page.
 */
fun suggestableHost(url: String): String? =
    try {
        val parsed = java.net.URL(url)
        val scheme = parsed.protocol?.lowercase()
        val host = parsed.host?.lowercase().orEmpty()
        if ((scheme == "http" || scheme == "https") && host.isNotBlank()) host else null
    } catch (e: java.net.MalformedURLException) {
        // Not a URL we can key on, which is the answer the caller wants. The message
        // would echo the whole URL including its query, so it isn't logged here — callers
        // that want a breadcrumb log the masked URL themselves.
        null
    }

/**
 * Like [canonicalUrlKey], but two URLs are only the same page if their fragments agree.
 *
 * Use this to decide whether two history entries describe one page. [canonicalUrlKey]
 * ignores the fragment, which is right for navigation outcomes — an address that doesn't
 * resolve doesn't resolve for any `#section` of it — but wrong for identity: a
 * hash-routed app serves genuinely different pages from one path, so treating them as
 * one would fold every Gmail view (`#inbox`, `#sent`, `#search/…`) into a single entry.
 */
fun distinctPageKey(url: String): String {
    val base = canonicalUrlKey(url)
    val fragment = url.trim().substringAfter('#', "")
    return if (base.isEmpty() || fragment.isEmpty()) base else "$base#$fragment"
}

/**
 * Normalize [url] into a key that survives the cosmetic differences between the URL a
 * user typed, the URL a plugin reports, and the URL the engine committed: scheme and host
 * casing, a `http(s)://` prefix, a `www.` prefix, a trailing slash, and a fragment.
 *
 * Only the parts that are genuinely case-insensitive get lowercased. Paths, queries and
 * opaque bodies are left alone — servers and filesystems distinguish `/Doc` from `/doc`,
 * and the payload of a `data:` URL is case-sensitive outright.
 *
 * Returns an empty string for a URL with nothing to key on.
 */
fun canonicalUrlKey(url: String): String {
    val trimmed = url.trim().substringBefore('#')
    if (trimmed.isEmpty()) return ""

    val schemeEnd = trimmed.indexOf("://")
    val scheme = if (schemeEnd >= 0) trimmed.substring(0, schemeEnd).lowercase() else ""
    val remainder = if (schemeEnd >= 0) trimmed.substring(schemeEnd + 3) else trimmed

    return when {
        // about:blank, data:…, mailto:… — a scheme with no authority behind it. Told
        // apart from a bare "host:port/path" by what follows the colon.
        schemeEnd < 0 && hasOpaqueBody(remainder) -> {
            val opaqueScheme = remainder.substringBefore(':').lowercase()
            "$opaqueScheme:${remainder.substringAfter(':').trimEnd('/')}"
        }

        // file://, chrome://, devtools://: normalize the authority, keep the path as-is.
        scheme.isNotEmpty() && scheme != "http" && scheme != "https" -> {
            "$scheme://${normalizeAuthorityAndPath(remainder)}"
        }

        else -> {
            normalizeAuthorityAndPath(remainder).ifEmpty { trimmed.lowercase() }
        }
    }
}

/** True when [value] is `scheme:body` (about:blank) rather than `host:port/path`. */
private fun hasOpaqueBody(value: String): Boolean {
    val beforeColon = value.substringBefore(':', "")
    if (beforeColon.isEmpty() || beforeColon.contains('/')) return false
    // A port is digits; anything else after the colon means it was a scheme.
    return value.substringAfter(':').firstOrNull()?.isDigit() != true
}

/** Lowercase the authority and strip a `www.` prefix; leave path and query untouched. */
private fun normalizeAuthorityAndPath(value: String): String {
    val authorityEnd = value.indexOfFirst { it == '/' || it == '?' }
    val authority = if (authorityEnd < 0) value else value.substring(0, authorityEnd)
    val path = if (authorityEnd < 0) "" else value.substring(authorityEnd)
    return authority.lowercase().removePrefix("www.") + path.trimEnd('/')
}
