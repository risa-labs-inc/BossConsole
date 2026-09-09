package ai.rever.boss.cache

import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Resolves the icon for a page, from the two sources that can supply one.
 *
 * **The page's own favicon wins; Google is only a fallback.** That order is the whole correctness
 * of this service, and it used to be the other way round:
 *
 * - `FaviconCache` is keyed on the FULL page URL and holds what the tab itself served. Right by
 *   construction, and absent for anything that was never a browser tab.
 * - Google's `s2/favicons` service is asked about a HOST. It fills the gap the first source
 *   leaves, and it is a guess.
 *
 * Asking Google FIRST was the bug: a guess about a host overwrote a known-correct per-page icon.
 * Google resolves subdomains to their parent - `mail.google.com`, `docs.google.com` and
 * `accounts.google.com` all return the same 32px Google "G" - so every Google-property bookmark
 * came out identical, and one on a `google.com/url?q=…` redirect link came out as Google rather
 * than as the site it opens. Neither is recoverable from a host; both were already sitting
 * correct in the standard cache.
 *
 * The order lives here rather than at a call site because all three surfaces want it: the
 * Favorites shelf (`TabBarFavorites`), the dashboard's recent-page cards (`BrowserPageCard`) and
 * the screen-capture picker. Fixing one file left a Gmail entry showing the generic G in the
 * other two.
 *
 * **What that costs, stated plainly:** the dashboard draws its result at 36dp, so a page whose
 * own favicon is 16px is now softer there than Google's 128px guess about its host was. Right
 * site over sharp icon, the same trade as dropping the 32px floor in [acceptResponse].
 *
 * A page whose icon this resolves from cache no longer tells Google which site it is at all,
 * which on the most-used surfaces is most of the requests this service used to make.
 *
 * Performance:
 * - Async HTTP with Ktor client (non-blocking), everything on [Dispatchers.IO]
 * - Reduced timeouts (2.5s) for faster failure detection
 * - Concurrency limit (3 simultaneous fetches) to prevent network flooding
 * - A definite "no icon" answer is remembered ([FaviconMissMemory]) rather than re-asked
 * - Size-capped cache, oldest fetch evicted first, entries expiring after a fortnight
 */
object HighQualityFaviconService {
    private val logger = BossLogger.forComponent("HighQualityFaviconService")

    // Requested, not promised: Google honours it for some hosts and serves 32px for others
    // (every google.com subdomain, among them), so a tile must cope with whatever comes back.
    private const val ICON_SIZE = 128
    private const val REQUEST_TIMEOUT_MS = 2500L
    private const val MAX_CONCURRENT_FETCHES = 3

    /**
     * A ceiling on what a reply may be worth keeping. A 128px PNG is a few KB, so this is two
     * orders of magnitude of headroom.
     *
     * **It is not a streaming bound.** `readRawBytes()` materialises the body first, so a reply
     * with no `Content-Length` is rejected only after it has been held in full; what actually
     * bounds that case is the 2.5s timeout times the link speed. Acceptable here and nowhere else,
     * because the host is always `www.google.com` rather than anything a caller supplies.
     */
    private const val MAX_RESPONSE_BYTES = 256L * 1024

    // Semaphore to limit concurrent network requests
    private val fetchSemaphore = Semaphore(MAX_CONCURRENT_FETCHES)

    // Ktor HTTP client with connection pooling. Held as the Lazy, not just its value, so close()
    // can ask whether anything ever fetched rather than starting a CIO engine at shutdown to shut
    // it down.
    private val httpClientLazy =
        lazy {
            HttpClient(CIO) {
                engine {
                    requestTimeout = REQUEST_TIMEOUT_MS
                    endpoint {
                        connectTimeout = REQUEST_TIMEOUT_MS
                        socketTimeout = REQUEST_TIMEOUT_MS
                    }
                }
            }
        }

    private val httpClient by httpClientLazy

    /**
     * The icon for a page: its own cached favicon if there is one, else Google's guess about its
     * host.
     *
     * **Everything blocking is inside [withContext], and callers rely on that.** A `LaunchedEffect`
     * does not move you off the composition dispatcher, so a caller that read the standard cache
     * itself would decode a PNG per tile on the UI thread.
     *
     * **Never throws**, except to propagate cancellation - a corrupt cache file, an unreachable
     * Google and a payload that will not decode are all just null. Callers do not need a
     * `try`/`catch` or a `runCatching`, and should not have one: wrapping this swallows the
     * cancellation an ordinary `LaunchedEffect` disposal raises.
     *
     * @param url the page URL, or null for a tab that is not a page - a terminal, a file. There is
     *   no host to guess from, so such a tab gets its cached icon or nothing.
     * @param standardCacheKey the key into the standard favicon cache, i.e. the page's own icon
     * @return the icon, or null when neither source has one
     */
    suspend fun getHighQualityFavicon(
        url: String?,
        standardCacheKey: String?,
    ): TabIcon.Image? =
        resolve(
            url = url,
            standardCacheKey = standardCacheKey,
            // The two slots cannot be swapped by accident: `pageIcon` is not `suspend` and
            // `hostGuess` is, so the compiler rejects the reversal a test would otherwise pin.
            pageIcon = ::loadStandardFavicon,
            hostGuess = { hostIcon(it) },
        )

    /**
     * [getHighQualityFavicon] with both sources injected, so the ORDER between them is pinned by a
     * test rather than by review. It is the one thing in this file that must not quietly flip
     * back, and inside a composable - where it used to live - it was unpinnable.
     *
     * Swapping the two calls below fails `the page's own icon wins and Google is not even asked`.
     * The second half of that name is what a "which icon wins" assertion alone would miss: a page
     * whose icon is already cached must not tell a third party which site it is.
     */
    internal suspend fun resolve(
        url: String?,
        standardCacheKey: String?,
        pageIcon: (String?) -> TabIcon.Image?,
        hostGuess: suspend (String?) -> TabIcon.Image?,
    ): TabIcon.Image? =
        withContext(Dispatchers.IO) {
            // Each source is attempted separately, so a corrupt standard-cache entry cannot cost
            // the host guess as well. `FaviconCache.loadFavicon` catches its own exceptions today,
            // which makes this belt and braces - but "happens to be total" is not a guarantee this
            // function should be spending on the caller's behalf.
            sourceOrNull("the page's own icon") { pageIcon(standardCacheKey) }
                ?: sourceOrNull("Google's guess about the host") { hostGuess(url) }
        }

    /**
     * One favicon source, reduced to null if it fails.
     *
     * Cancellation is put back rather than logged: ordinary disposal - the shelf collapsing, a
     * picker dismissed, a url change - cancels the effect this runs in, and CancellationException
     * IS an Exception, so reporting it as a failed lookup would be a lie and eating it would be
     * worse.
     */
    private suspend fun sourceOrNull(
        name: String,
        source: suspend () -> TabIcon.Image?,
    ): TabIcon.Image? =
        try {
            source()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.debug(
                LogCategory.BROWSER,
                "A favicon source failed - trying the next one, or the caller's fallback",
                mapOf("source" to name, "error" to e.toString()),
            )
            null
        }

    /**
     * Google's guess about the host behind [url], from cache when it is fresh.
     *
     * Four interactions live here, and the fetch outcome decides three of them:
     *
     * - **Fresh entry**: served, no request, and the miss memory is not even consulted.
     * - **[FaviconFetch.NoAnswer]** - a timeout, an unreachable Google, a rate-limit page where an
     *   image should be: an expired entry is served anyway. Being offline should cost sharpness,
     *   not the icon, and it must not be irreversible.
     * - **[FaviconFetch.NoIcon]** - Google answered, definitively, that this host has nothing: the
     *   entry is DROPPED. Serving it would keep showing an icon the site has removed, and since
     *   expiry does not delete, it would keep showing it until 200 entries forced an eviction.
     *   This is the one path that removes an entry on purpose.
     * - **A remembered miss**: no request, and no stale entry either - the entry was dropped when
     *   the miss was learned, and the answer has not changed in the six hours since.
     *
     * [nowMs], [dir] and [fetch] are injectable for the tests that pin those four; the defaults are
     * the real clock, the real cache and the real service.
     */
    internal suspend fun hostIcon(
        url: String?,
        nowMs: Long = System.currentTimeMillis(),
        dir: File = HqFaviconDiskCache.defaultDir,
        fetch: suspend (String, String) -> FaviconFetch = { host, key ->
            fetchSemaphore.withPermit { fetchFromGoogle(host, key, dir, nowMs) }
        },
    ): TabIcon.Image? {
        val host = FaviconHost.of(url) ?: return null
        val cacheKey = HqFaviconDiskCache.keyFor(host)
        val cached = HqFaviconDiskCache.load(cacheKey, dir)

        return when {
            cached != null && !FaviconFreshness.isEntryExpired(cached.fetchedAtMs, nowMs) -> {
                cached.icon
            }

            // The entry was dropped when this miss was learned, so `cached` is normally null here.
            // It is not null when that delete FAILED - a Windows lock, a permission problem - and
            // then a usable icon is sitting right there; returning null would show a letter for
            // six hours with the answer on disk.
            FaviconMissMemory.remembers(host, nowMs) -> {
                cached?.icon
            }

            else -> {
                when (val outcome = fetch(host, cacheKey)) {
                    is FaviconFetch.Icon -> {
                        outcome.icon
                    }

                    FaviconFetch.NoIcon -> {
                        HqFaviconDiskCache.delete(cacheKey, dir)
                        null
                    }

                    FaviconFetch.NoAnswer -> {
                        cached?.icon
                    }
                }
            }
        }
    }

    /**
     * Fetch from Google's service and cache what comes back.
     *
     * **Only the placeholder is a definite "no icon".** It is the one response that means Google
     * looked and found nothing, so it is the only one recorded in [FaviconMissMemory]. A payload
     * that will not decode is NOT: HTTP 200 with something that is not an image is what a
     * rate-limit interstitial or a proxy-truncated body looks like, and remembering that would
     * suppress the retry for six hours over a transient hiccup. Same for whatever [requestIcon]
     * could not get at all.
     */
    private suspend fun fetchFromGoogle(
        host: String,
        cacheKey: String,
        dir: File,
        nowMs: Long,
    ): FaviconFetch = requestIcon(host)?.let { acceptResponse(it, host, cacheKey, dir, nowMs) } ?: FaviconFetch.NoAnswer

    /**
     * What Google's reply means, and the entry it leaves behind.
     *
     * Split from the request so the distinction can be pinned without the network: it is the whole
     * point of [FaviconMissMemory], and getting it wrong in either direction is a bug that only
     * shows up hours later. Takes [dir] for the same reason.
     */
    internal suspend fun acceptResponse(
        bytes: ByteArray,
        host: String,
        cacheKey: String,
        dir: File = HqFaviconDiskCache.defaultDir,
        nowMs: Long = System.currentTimeMillis(),
    ): FaviconFetch =
        when {
            GoogleNoIconPlaceholder.matches(bytes) -> {
                logger.debug(
                    LogCategory.NETWORK,
                    "Google has no favicon for host - declining its placeholder",
                    mapOf("host" to host),
                )
                FaviconMissMemory.record(host, nowMs)
                FaviconFetch.NoIcon
            }

            else -> {
                // **No minimum size.** The floor was 32px, which rejected every genuine 16px
                // favicon and left the host showing a letter while the real icon sat unused. A
                // 16px icon is soft in a 22dp shelf tile and softer in the dashboard's 36dp card;
                // it is still the right site.
                //
                // Broad catch on purpose: JDK image readers throw unchecked on malformed data, not
                // only IIOException, and every one of them means the same thing here.
                val image =
                    try {
                        ImageIO.read(ByteArrayInputStream(bytes))
                    } catch (e: Exception) {
                        logger.debug(
                            LogCategory.NETWORK,
                            "Google's favicon response would not decode - treating it as no answer",
                            mapOf("host" to host, "error" to e.toString()),
                        )
                        null
                    }

                image?.let {
                    HqFaviconDiskCache.save(cacheKey, it, dir)
                    FaviconFetch.Icon(TabIcon.Image(BitmapPainter(it.toComposeImageBitmap())))
                } ?: FaviconFetch.NoAnswer
            }
        }

    /**
     * The bytes Google served for [host], or null when it did not answer.
     *
     * URL format: `https://www.google.com/s2/favicons?domain=example.com&sz=128`
     */
    private suspend fun requestIcon(host: String): ByteArray? =
        try {
            val response =
                httpClient.get("https://www.google.com/s2/favicons") {
                    // parameter(), not interpolation. An authority may contain an `&` -
                    // `https://evil.com&x=1/` extracts as the host `evil.com&x=1` - which
                    // interpolated would append attacker-shaped parameters to a request BOSS makes
                    // to Google, and hash into a junk cache key besides.
                    parameter("domain", host)
                    parameter("sz", ICON_SIZE)
                    headers {
                        append(HttpHeaders.UserAgent, "Mozilla/5.0")
                    }
                }
            val declared = response.contentLength()
            if (response.status == HttpStatusCode.OK && (declared == null || declared <= MAX_RESPONSE_BYTES)) {
                response.readRawBytes().takeIf { it.size <= MAX_RESPONSE_BYTES }
            } else {
                null
            }
        } catch (e: Exception) {
            // A cancelled effect is not a failed fetch - see resolve().
            currentCoroutineContext().ensureActive()
            logger.debug(
                LogCategory.NETWORK,
                "HQ favicon fetch from Google failed - falling back",
                mapOf("host" to host, "error" to e.toString()),
            )
            null
        }

    /** The page's own favicon, captured from the tab that served it. */
    private fun loadStandardFavicon(cacheKey: String?): TabIcon.Image? {
        if (cacheKey == null) return null
        return FaviconCache.loadFavicon(cacheKey)
    }

    /**
     * Clear the HQ favicon cache, remembered misses included.
     *
     * `suspend`, because it takes the same lock a write does - otherwise it could land between a
     * write's temp file and its move and delete one or the other out from under it.
     */
    suspend fun clearCache() {
        HqFaviconDiskCache.clear()
        FaviconMissMemory.forget()
    }

    /**
     * Cleanup resources when no longer needed. A session that never fetched has nothing to close.
     */
    fun close() {
        if (httpClientLazy.isInitialized()) httpClientLazy.value.close()
    }
}
