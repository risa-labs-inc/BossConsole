package ai.rever.boss.cache

import ai.rever.boss.plugin.api.TabIcon
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * What a fetch from Google learned - which is not the same question as "did we get an icon".
 *
 * [NoIcon] is Google answering *definitely*: it has nothing for this host. [NoAnswer] is Google
 * not answering - a timeout, an unreachable host, a rate-limit interstitial where an image should
 * be. The two must not be conflated, because they imply opposite things about the entry already
 * on disk: a definite miss means a site dropped its favicon and the cached copy is now wrong,
 * while no answer means the cached copy is the best thing anyone has.
 */
internal sealed interface FaviconFetch {
    data class Icon(
        val icon: TabIcon.Image,
    ) : FaviconFetch

    /** Google has no favicon for this host. */
    data object NoIcon : FaviconFetch

    /** Google did not answer, or answered with something that is not an image. */
    data object NoAnswer : FaviconFetch
}

/**
 * The host [HighQualityFaviconService] should ask Google about, or null when there is nothing to
 * ask.
 *
 * Only an http(s) page has a host Google can answer for. Everything else - `file:///…`,
 * `boss://`, `mailto:`, a bare path - used to reach the fetch as a "domain" of whatever sat
 * before the first slash (`file:` for a local file), which spends a network round trip to be told
 * no.
 *
 * Three things beyond the scheme are stripped, and two of them matter beyond tidiness:
 *
 * - **Credentials.** `https://user:pw@example.com/x` yielded `user:pw@example.com`, which went
 *   into the `domain=` query parameter of a request to `www.google.com` and was then MD5'd into a
 *   cache filename. A password does not belong in either place.
 * - **The fragment.** `https://example.com#top` was sent verbatim for the same reason.
 * - **The port.** Google's service keys on host alone and has nothing for `localhost:3000`, so a
 *   dev server got no icon at all while plain `localhost` would have answered.
 *
 * A fourth, carried over from the old extraction and kept deliberately: a leading **`www.`** goes,
 * so `www.example.com` and the apex share one cache entry and one miss-memory key. They serve the
 * same favicon in practice, and the alternative is two requests and two entries for one site.
 *
 * What it does not cover: `https://user:pw` + `#@example.com/` extracts the host `user`, because
 * the fragment is cut before the credentials are. That is a malformed URL Chromium rejects
 * outright, so nothing reachable produces one - but it is the same class of leak the credential
 * strip closes, and it is not closed.
 */
internal object FaviconHost {
    /**
     * A scheme-less `example.com/x` or `localhost:3000/app`.
     *
     * These do reach here: `NetscapeBookmarkParser` passes an export's `HREF` through verbatim
     * and nothing on the import path normalises a scheme onto it, so a hand-written or
     * third-party bookmark file can carry one. The old extraction resolved them by accident of
     * stripping a prefix that was not there.
     *
     * Deliberately narrow - a dotted name, a dotted-quad address or `localhost`, an optional
     * numeric port, then a `/`, `?`, `#` or the end of the string. That rejects a path
     * (`/Users/x`), an opaque scheme (`mailto:`, `javascript:`, `about:blank`,
     * `data:image/png;…`) and anything with a space in it, none of which have a host to ask
     * about, and all of which the old extraction sent.
     *
     * It does NOT reject a dotted filename: `index.js` and `notes.md` are shaped exactly like a
     * host and are accepted as one. Nothing reaches here with a bare filename today, and the same
     * hand-written bookmarks file that motivates this form could hold one, so the cost is stated
     * rather than guarded: one request naming a filename, and six hours of remembered miss.
     */
    private val BARE_AUTHORITY =
        Regex(
            """^(?:localhost|(?:\d{1,3}\.){3}\d{1,3}|(?:[\w-]+\.)+[a-z]{2,})(?::\d+)?(?![^/?#])""",
            RegexOption.IGNORE_CASE,
        )

    fun of(url: String?): String? {
        // A BACKSLASH is a slash here. WHATWG says so for special schemes, so Chromium - and
        // therefore JxBrowser - navigates `https://example.com\@evil.com/` to example.com with the
        // path `/@evil.com/`. Splitting only on `/` would have taken the userinfo strip literally
        // and yielded `evil.com`: the wrong favicon on the tile, a request to Google naming a site
        // the user never visited, and that name cached for a fortnight. Same shape as the
        // credential leak below, from the other direction, and reachable by anyone who can put a
        // URL in a bookmark file.
        val authority = authorityOf(url?.trim()?.replace('\\', '/').orEmpty()) ?: return null
        return authority
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            // Credentials, if any, sit before the host and are not part of it.
            .substringAfterLast('@')
            .let(::stripPort)
            .removeSuffix(".")
            .lowercase()
            .removePrefix("www.")
            .ifBlank { null }
    }

    /**
     * The `host[:port][/path…]` part of [url], or null when [url] names no host.
     *
     * A leading `//` is scheme-relative and inherits the page's scheme, which for anything that
     * became a bookmark was http(s).
     */
    private fun authorityOf(url: String): String? =
        when {
            url.startsWith("//") -> url.removePrefix("//")
            url.contains("://") -> url.substringAfter("://").takeIf { isHttp(url.substringBefore("://")) }
            else -> BARE_AUTHORITY.find(url)?.value
        }

    private fun isHttp(scheme: String): Boolean = scheme.equals("http", true) || scheme.equals("https", true)

    /** Drops a `:port`, leaving an IPv6 literal's own bracketed colons alone. */
    private fun stripPort(host: String): String =
        when {
            host.startsWith('[') && host.contains(']') -> host.substringBefore(']') + "]"
            host.startsWith('[') -> host
            else -> host.substringBefore(':')
        }
}

/**
 * Google's "I have no favicon for this host" reply, recognised by its bytes.
 *
 * It arrives as HTTP 200 with a 16x16 grey globe - identical bytes for every unknown host,
 * whatever `sz` was asked for - so nothing about the response says "miss" except the payload.
 * Caching it is what made unrelated hosts share one anonymous globe and never re-check.
 */
internal object GoogleNoIconPlaceholder {
    /**
     * Verified against two unrelated nonexistent hosts at sz=16/32/64/128: the same 726 bytes
     * each time. A fingerprint is inherently a bet on Google not changing the asset; the cost of
     * losing that bet is only that one placeholder gets cached again, which is where this
     * started, so it fails no worse than not checking.
     */
    private const val SHA256 = "59bfe9bc385ad69f50793ce4a53397316d7a875a7148a63c16df9b674c6cda64"

    fun matches(bytes: ByteArray): Boolean {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) } == SHA256
    }
}

/**
 * How long a fetched icon is trusted before it is fetched again.
 *
 * A favicon from this service is a guess about a host, and a wrong guess used to be permanent:
 * nothing ever refetched, so a site that had no icon on the day it was first opened kept whatever
 * Google said then for as long as the entry survived eviction.
 *
 * Note this is NOT a cleanup schedule. An expired entry is not deleted; it stops counting as
 * fresh, which means the next resolution refetches and either replaces it or - if Google says the
 * host has no favicon at all - drops it. Only eviction removes an entry nobody asks about.
 *
 * `FaviconCache.cleanupStaleEntries` ages the standard cache out after 30 days, so the two favicon
 * caches now expire on different clocks. Deliberate: the standard cache holds what a page actually
 * served, which does not go stale by being a guess.
 *
 * Not addressed here, and worth knowing: nothing memoises a *resolved* icon, so every composition
 * that misses `remember` costs a disk read and a PNG decode on all three surfaces. Preferring the
 * standard cache moved that cost onto the common path. A small in-memory LRU keyed on
 * `(url, standardCacheKey)` in the service would collapse it for every caller, and is the natural
 * home for [FaviconMissMemory] too.
 */
internal object FaviconFreshness {
    const val MAX_CACHE_AGE_MS = 14L * 24 * 60 * 60 * 1000

    fun isEntryExpired(
        fetchedAtMs: Long,
        nowMs: Long,
    ): Boolean {
        val age = nowMs - fetchedAtMs
        // A NEGATIVE age is not fresh, it is nonsense - an entry restored by `rsync -a` from a
        // clock-skewed machine, a filesystem timestamp nobody wrote, the clock stepping back.
        // Read as fresh it would never be refetched again, which is exactly the permanence this
        // TTL exists to remove, and it would fail silently.
        return age < 0 || age > MAX_CACHE_AGE_MS
    }
}

/**
 * Hosts Google has answered "no icon" for, and when it answered.
 *
 * Declining the placeholder removed the only negative cache this service had. With nothing
 * written for a miss, every re-entry of a tile into composition - toggling the shelf, each launch
 * - spent another request with a 2.5s timeout to learn the same thing, and for the dashboard and
 * the capture picker that is the common path rather than the rare one.
 *
 * In memory rather than on disk: it is cheap to rebuild, and it must not outlive a host adding a
 * favicon by long, so [MISS_MEMORY_MS] is deliberately a small fraction of
 * [FaviconFreshness.MAX_CACHE_AGE_MS].
 *
 * Only a definite answer is recorded. A timeout or an unreachable Google is not a miss - see
 * `HighQualityFaviconService.acceptResponse` - because remembering one would suppress the retry
 * for hours after the network came back.
 *
 * **Process-wide state that tests mutate.** Every test class touching it calls [forget] in an
 * `@AfterTest`, which is sufficient only because desktopTest runs one fork: raising
 * `maxParallelForks` would let one class's 500 recorded hosts suppress another class's fetch,
 * nondeterministically. Make the map injectable before that happens.
 */
internal object FaviconMissMemory {
    const val MISS_MEMORY_MS = 6L * 60 * 60 * 1000

    /** Bounded so a long session browsing icon-less hosts cannot grow this without limit. */
    const val MAX_REMEMBERED = 500

    private val misses = ConcurrentHashMap<String, Long>()

    fun record(
        host: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (misses.size >= MAX_REMEMBERED) {
            misses.values.removeIf { nowMs - it > MISS_MEMORY_MS }
            // Nothing had expired, so there is no cheap subset to drop. The whole map costs at
            // most one extra request per host to rebuild.
            if (misses.size >= MAX_REMEMBERED) misses.clear()
        }
        misses[host] = nowMs
    }

    fun remembers(
        host: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean = misses[host]?.let { nowMs - it <= MISS_MEMORY_MS } == true

    fun forget() {
        misses.clear()
    }
}
