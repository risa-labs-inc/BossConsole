package ai.rever.boss.plugin.browser

/**
 * Remembers whether the last main-frame navigation to a URL actually produced a page.
 *
 * A browser reports a title and a "load finished" for a URL that never loaded — typing
 * `youtube.como` still commits an error page, still fires TitleChanged, and used to land
 * in the URL history and the dashboard's recent pages, where it came back as a
 * suggestion forever. The browser engine is the only layer that knows a navigation
 * ended on an error page, so it records the outcome here and the two history stores
 * consult it before recording a visit.
 *
 * Entries are keyed by [canonicalUrlKey] so the URL the engine committed
 * (`https://youtube.como/`) matches the one a caller reports (`youtube.como`), and the
 * failure set is bounded — it only needs to survive the moment between a navigation
 * finishing and the title/load callbacks that follow it.
 */
object NavigationOutcomeTracker {
    /** Plenty for the in-flight window; oldest keys are dropped first. */
    private const val MAX_TRACKED_FAILURES = 256

    private val failedUrls = LinkedHashSet<String>()

    /** Record that a main-frame navigation to [url] committed a real page. */
    fun recordSuccess(url: String) {
        val key = canonicalUrlKey(url)
        if (key.isEmpty()) return
        synchronized(failedUrls) {
            failedUrls.remove(key)
        }
    }

    /** Record that a main-frame navigation to [url] ended on an error page (or never committed). */
    fun recordFailure(url: String) {
        val key = canonicalUrlKey(url)
        if (key.isEmpty()) return
        synchronized(failedUrls) {
            // Re-insert so a repeated failure counts as the most recent entry.
            failedUrls.remove(key)
            failedUrls.add(key)
            while (failedUrls.size > MAX_TRACKED_FAILURES) {
                val oldest = failedUrls.first()
                failedUrls.remove(oldest)
            }
        }
    }

    /** Whether the last main-frame navigation to [url] failed to load a page. */
    fun didFail(url: String): Boolean {
        val key = canonicalUrlKey(url)
        if (key.isEmpty()) return false
        return synchronized(failedUrls) { failedUrls.contains(key) }
    }

    /** Drop all tracked outcomes. Used by tests. */
    fun clear() {
        synchronized(failedUrls) { failedUrls.clear() }
    }
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
 * user typed, the URL a plugin reports, and the URL the engine committed: scheme and
 * host casing, a `http(s)://` prefix, a `www.` prefix, a trailing slash, and a fragment.
 *
 * Returns an empty string for a URL with nothing to key on.
 */
fun canonicalUrlKey(url: String): String {
    val trimmed = url.trim().substringBefore('#')
    if (trimmed.isEmpty()) return ""

    val schemeEnd = trimmed.indexOf("://")
    val scheme = if (schemeEnd >= 0) trimmed.substring(0, schemeEnd).lowercase() else ""
    val remainder = if (schemeEnd >= 0) trimmed.substring(schemeEnd + 3) else trimmed

    // "about:blank", "data:…", "localhost:3000/app" — a colon with no slash before it and
    // no "://" means there is no authority to normalize, so key the whole thing. (The
    // host:port form lands here too and still matches its "http://host:port" spelling,
    // which is the point.)
    val leadingSegment = remainder.substringBefore(':', "")
    val hasOpaqueBody = schemeEnd < 0 && leadingSegment.isNotEmpty() && !leadingSegment.contains('/')
    val isWebScheme = scheme.isEmpty() || scheme == "http" || scheme == "https"

    return when {
        !isWebScheme -> {
            "$scheme://${remainder.trimEnd('/')}".lowercase()
        }

        hasOpaqueBody -> {
            remainder.trimEnd('/').lowercase()
        }

        else -> {
            val authorityEnd = remainder.indexOfFirst { it == '/' || it == '?' }
            val authority = if (authorityEnd < 0) remainder else remainder.substring(0, authorityEnd)
            val path = if (authorityEnd < 0) "" else remainder.substring(authorityEnd)
            val host = authority.lowercase().removePrefix("www.")
            (host + path.trimEnd('/')).ifEmpty { trimmed.lowercase() }
        }
    }
}
