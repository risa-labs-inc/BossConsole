package ai.rever.boss.dashboard

import ai.rever.boss.plugin.browser.NavigationOutcomeTracker
import ai.rever.boss.plugin.browser.canonicalUrlKey
import ai.rever.boss.plugin.browser.shouldRetireVisit
import ai.rever.boss.plugin.browser.suggestableHost
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Data class representing a recently visited browser page.
 */
@Serializable
data class RecentBrowserPage(
    val url: String,
    val title: String,
    val lastVisited: Long,
    val faviconCacheKey: String? = null,
    val visitCount: Int = 1,
)

/**
 * Container for recent browser pages data with serialization support.
 */
@Serializable
data class RecentBrowserPagesData(
    val pages: List<RecentBrowserPage> = emptyList(),
    /**
     * Suggested sites the user has dismissed, so they stay dismissed.
     *
     * Only the padding suggestions need this. A page in [pages] is removed by removing it; a
     * suggestion drawn from `POPULAR_DEV_SITES` is not in any list, so before this existed its
     * X button filtered nothing, saved an unchanged list, and the card re-appeared on the next
     * frame. Seventeen of them could pile up on the home screen with no way to clear them -
     * "Clear" only touches [pages], and even hid its own label once [pages] was empty.
     */
    val dismissedSuggestions: List<String> = emptyList(),
)

/**
 * Entry format from existing browser history (UrlHistoryManager).
 * Used for bootstrapping when no recent pages data exists.
 */
@Serializable
private data class BrowserHistoryEntry(
    val url: String,
    val title: String,
    val domain: String = "",
    val visitCount: Int = 1,
    val lastVisited: Long = 0,
)

/**
 * Manages recently visited browser pages for the Dashboard.
 * Persists to ~/.boss/recent-browser-pages.json
 *
 * Thread-safe: All file I/O operations run on Dispatchers.IO.
 * Uses StateFlow for reactive UI updates.
 */
@Suppress("TooManyFunctions") // Visit, dismissal, persistence, and shutdown share one manager state.
object RecentBrowserPagesManager {
    private val logger = BossLogger.forComponent("RecentBrowserPagesManager")
    private const val MAX_PAGES = 30
    private const val SAVE_DEBOUNCE_MS = 5000L // Debounce saves to max once per 5 seconds

    /** Overridable so tests exercise the real read/write path without touching `~/.boss`. */
    internal var settingsFile: File = BossDirectories.resolve("recent-browser-pages.json")
    private val json =
        Json {
            prettyPrint = false
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var saveJob: Job? = null
    private val saveJobLock = Any()

    private val _recentPages = MutableStateFlow<List<RecentBrowserPage>>(emptyList())
    val recentPages: StateFlow<List<RecentBrowserPage>> = _recentPages.asStateFlow()

    /**
     * Canonical keys of dismissed padding suggestions.
     *
     * A StateFlow rather than a plain set so dismissing one recomposes the home screen, and
     * keyed by [canonicalUrlKey] for the same reason [removeMatchingPages] is: the promo list and
     * a recorded visit can spell the same page differently.
     */
    private val _dismissedSuggestions = MutableStateFlow<Set<String>>(emptySet())
    val dismissedSuggestions: StateFlow<Set<String>> = _dismissedSuggestions.asStateFlow()

    // Popular developer websites for suggestions
    private val POPULAR_DEV_SITES =
        listOf(
            RecentBrowserPage(
                url = "https://www.risalabs.ai",
                title = "Risa Labs",
                lastVisited = 0L,
                faviconCacheKey = null,
                visitCount = 0,
            ),
            RecentBrowserPage(
                url = "https://github.com/risa-labs-inc/BossConsole-Releases",
                title = "BossConsole Releases",
                lastVisited = 0L,
                faviconCacheKey = null,
                visitCount = 0,
            ),
            RecentBrowserPage(
                url = "https://formulae.brew.sh/cask/boss",
                title = "BOSS - Homebrew",
                lastVisited = 0L,
                faviconCacheKey = null,
                visitCount = 0,
            ),
            RecentBrowserPage(
                url = "https://github.com/kshivang/BossTerm",
                title = "BossTerm",
                lastVisited = 0L,
                faviconCacheKey = null,
                visitCount = 0,
            ),
            RecentBrowserPage(
                url = "https://chat.openai.com",
                title = "ChatGPT",
                lastVisited = 0L,
                faviconCacheKey = null,
                visitCount = 0,
            ),
            RecentBrowserPage(
                url = "https://claude.ai",
                title = "Claude",
                lastVisited = 0L,
                faviconCacheKey = null,
                visitCount = 0,
            ),
            RecentBrowserPage(
                url = "https://grok.com",
                title = "Grok",
                lastVisited = 0L,
                faviconCacheKey = null,
                visitCount = 0,
            ),
            RecentBrowserPage(
                url = "https://gemini.google.com",
                title = "Gemini",
                lastVisited = 0L,
                faviconCacheKey = null,
                visitCount = 0,
            ),
            RecentBrowserPage(
                url = "https://console.cloud.google.com/vertex-ai",
                title = "Vertex AI",
                lastVisited = 0L,
                faviconCacheKey = null,
                visitCount = 0,
            ),
            RecentBrowserPage(
                url = "https://github.com",
                title = "GitHub",
                lastVisited = 0L,
                faviconCacheKey = null,
                visitCount = 0,
            ),
            RecentBrowserPage(
                url = "https://stackoverflow.com",
                title = "Stack Overflow",
                lastVisited = 0L,
                faviconCacheKey = null,
                visitCount = 0,
            ),
            RecentBrowserPage(
                url = "https://developer.mozilla.org",
                title = "MDN Web Docs",
                lastVisited = 0L,
                faviconCacheKey = null,
                visitCount = 0,
            ),
            RecentBrowserPage(
                url = "https://docs.github.com",
                title = "GitHub Docs",
                lastVisited = 0L,
                faviconCacheKey = null,
                visitCount = 0,
            ),
            RecentBrowserPage(
                url = "https://npmjs.com",
                title = "npm",
                lastVisited = 0L,
                faviconCacheKey = null,
                visitCount = 0,
            ),
            RecentBrowserPage(
                url = "https://crates.io",
                title = "Crates.io",
                lastVisited = 0L,
                faviconCacheKey = null,
                visitCount = 0,
            ),
            RecentBrowserPage(
                url = "https://docs.python.org",
                title = "Python Docs",
                lastVisited = 0L,
                faviconCacheKey = null,
                visitCount = 0,
            ),
            RecentBrowserPage(
                url = "https://golang.org",
                title = "Go",
                lastVisited = 0L,
                faviconCacheKey = null,
                visitCount = 0,
            ),
        )

    /**
     * Canonical keys of the padding suggestions, which are the only urls worth remembering a
     * dismissal for. Bounds `dismissedSuggestions` to the size of that list.
     */
    private val promoKeys: Set<String> by lazy { POPULAR_DEV_SITES.map { canonicalUrlKey(it.url) }.toSet() }

    private val loadGuard = RecentPagesLoadGuard()

    // Register before launch: a Clear before the IO coroutine starts must also win.
    internal val initialLoad: Job = loadGuard.begin().let { ticket -> scope.launch { loadAsync(ticket) } }

    /**
     * Load recent pages from disk asynchronously.
     * If no data exists, bootstraps from existing browser history.
     *
     * Internal rather than private so tests can drive the real load path against a hermetic file,
     * as [RecentFilesManager]'s already is; production still reaches it only from `init`.
     */
    internal suspend fun loadAsync(
        ticket: RecentPagesLoadGuard.Ticket = loadGuard.begin(),
        read: suspend (File) -> String = { it.readText() },
        historyFile: File = BossDirectories.resolve("browser-history.json"),
    ) = withContext(Dispatchers.IO) {
        try {
            settingsFile.parentFile?.mkdirs()

            if (settingsFile.exists()) {
                val content = read(settingsFile)
                val data = json.decodeFromString<RecentBrowserPagesData>(content)
                // Merged, not assigned: `init` launches this load and `recordPageVisit` can
                // land while the read is still in flight - which is exactly why that method
                // uses update{}. Assigning here dropped the page the user had just visited
                // from the list *and* from the save scheduled for it. Same defect #795 fixed
                // for the sibling RecentFilesManager.
                val merged =
                    loadGuard.publish(ticket, data.pages) { surviving ->
                        _recentPages.updateAndGet { recorded ->
                            mergeRecordedPages(loaded = surviving, recorded = recorded, max = MAX_PAGES)
                        }
                    }
                // Intersected with the current promo list: a file written before dismissals
                // were bounded can hold an entry per page ever removed, and nothing else would
                // ever drop them. Merge rather than assign because removePage and clearAll run
                // on the caller thread and can record a dismissal while this read is in flight.
                val loadedDismissals = data.dismissedSuggestions.toSet() intersect promoKeys
                val mergedDismissals =
                    _dismissedSuggestions.updateAndGet { recorded ->
                        mergeDismissedSuggestions(loadedDismissals, recorded, promoKeys)
                    }
                if (merged != data.pages || mergedDismissals != loadedDismissals) scheduleSave()
                logger.debug(LogCategory.SYSTEM, "Loaded recent pages", mapOf("count" to merged.size))
            } else {
                // Bootstrap from existing browser history if available
                bootstrapFromBrowserHistory(ticket, read, historyFile)
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Error loading recent pages", error = e)
            // Try to bootstrap even on error
            bootstrapFromBrowserHistory(ticket, read, historyFile)
        } finally {
            loadGuard.end(ticket)
        }
    }

    /**
     * Bootstrap recent pages from existing browser history file.
     * This provides initial data when no recent pages have been recorded yet.
     */
    private suspend fun bootstrapFromBrowserHistory(
        ticket: RecentPagesLoadGuard.Ticket,
        read: suspend (File) -> String,
        browserHistoryFile: File,
    ) = withContext(Dispatchers.IO) {
        try {
            if (!browserHistoryFile.exists()) return@withContext

            val content = read(browserHistoryFile)
            if (content.isEmpty()) return@withContext

            // Parse browser history entries
            val entries = json.decodeFromString<List<BrowserHistoryEntry>>(content)

            // Convert to RecentBrowserPage, sorted by lastVisited, take top MAX_PAGES
            val recentPages =
                entries
                    .sortedByDescending { it.lastVisited }
                    .take(MAX_PAGES)
                    .map { entry ->
                        RecentBrowserPage(
                            url = entry.url,
                            title = entry.title,
                            lastVisited = entry.lastVisited,
                            faviconCacheKey = null, // Will be populated on next visit
                            visitCount = entry.visitCount,
                        )
                    }

            if (recentPages.isNotEmpty()) {
                // Merged for the same reason as the load above, and it matters more here:
                // this path writes immediately, so an assignment would persist the bootstrap
                // over a visit recorded while the history file was being read.
                loadGuard.publish(ticket, recentPages) { surviving ->
                    _recentPages.update { recorded ->
                        mergeRecordedPages(loaded = surviving, recorded = recorded, max = MAX_PAGES)
                    }
                }
                saveImmediately()
                logger.debug(LogCategory.SYSTEM, "Bootstrapped pages from browser history", mapOf("count" to recentPages.size))
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Error bootstrapping from browser history", error = e)
        }
    }

    /**
     * Save recent pages to disk with debouncing.
     * Cancels any pending save and schedules a new one after SAVE_DEBOUNCE_MS.
     */
    private fun scheduleSave() {
        // Swap the debounce job under a lock: callers now arrive from concurrent
        // coroutines (a visit and an eviction racing), and an unsynchronized
        // cancel-then-assign can drop the reference to a job that is still pending.
        val target = settingsFile
        synchronized(saveJobLock) {
            saveJob?.cancel()
            saveJob =
                scope.launch {
                    delay(SAVE_DEBOUNCE_MS)
                    saveImmediately(target)
                }
        }
    }

    /** Flush a scheduled page save before shutdown, including one already entering its write. */
    suspend fun flushPendingSaves() {
        val pending = synchronized(saveJobLock) { saveJob.also { saveJob = null } }
        if (pending == null || pending.isCompleted) return
        pending.cancel()
        pending.join()
        saveImmediately()
    }

    /**
     * Immediately save recent pages to disk (bypasses debounce).
     */

    /**
     * @param target resolved by the caller, never read here: a debounced save that picked
     *   its destination at execution time would follow [settingsFile] if it changed in
     *   between, and write one profile's pages into another's file.
     */
    private suspend fun saveImmediately(target: File = settingsFile) =
        withContext(Dispatchers.IO) {
            try {
                target.parentFile?.mkdirs()
                val data =
                    RecentBrowserPagesData(
                        pages = _recentPages.value,
                        // Note `encodeDefaults = false` on the Json above: an empty dismissed
                        // set is simply absent from the file, and absent decodes back to empty.
                        dismissedSuggestions = _dismissedSuggestions.value.toList(),
                    )
                val content = json.encodeToString(RecentBrowserPagesData.serializer(), data)
                // Atomic: the debounced save and an eviction can land together, and a
                // half-written file reads back as "no recent pages".
                target.atomicWriteText(content)
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Error saving recent pages", error = e)
            }
        }

    /**
     * Record a page visit.
     * Updates visit count if already present, otherwise adds new entry.
     * Maintains max page limit.
     *
     * @param url The URL of the page
     * @param title The page title
     * @param faviconCacheKey Optional favicon cache key for display
     */
    fun recordPageVisit(
        url: String,
        title: String,
        faviconCacheKey: String? = null,
    ) {
        // A navigation that ended on an error page never showed the user anything — it
        // still reports a title and a finished load, so without the outcome check a
        // mistyped host would sit in the recent pages (and come back as a suggestion) as
        // if it had loaded. The host check is shared with the URL history so the two
        // stores can't drift on what counts as a page: `about:`, `data:`, `blob:`,
        // `chrome://` and `file://` have no domain to match or display.
        val describesAPage = title.isNotBlank() && suggestableHost(url) != null
        if (!describesAPage || NavigationOutcomeTracker.didFail(url)) return

        scope.launch {
            // update{} rather than read-then-write: this runs on a multi-threaded
            // dispatcher and is *expected* to race an eviction — that is the whole
            // TitleChanged-arrived-first case — so a lost update here would put back the
            // entry that was just retracted.
            _recentPages.update { pages ->
                val currentPages = pages.toMutableList()
                val existingIndex = currentPages.indexOfFirst { it.url == url }

                val newPage =
                    if (existingIndex >= 0) {
                        // Update existing entry
                        val existing = currentPages.removeAt(existingIndex)
                        existing.copy(
                            title = title,
                            lastVisited = System.currentTimeMillis(),
                            faviconCacheKey = faviconCacheKey ?: existing.faviconCacheKey,
                            visitCount = existing.visitCount + 1,
                        )
                    } else {
                        // Create new entry
                        RecentBrowserPage(
                            url = url,
                            title = title,
                            lastVisited = System.currentTimeMillis(),
                            faviconCacheKey = faviconCacheKey,
                            visitCount = 1,
                        )
                    }

                // Add to front (most recent), trimmed to max size
                currentPages.add(0, newPage)
                currentPages.take(MAX_PAGES)
            }
            scheduleSave()
        }
    }

    /**
     * Remove a specific page from recent history, or dismiss a suggestion that was never in it.
     *
     * Both, because the home screen shows one strip built from both sources and the user cannot
     * tell which a given card came from - so the X has to work either way. Dismissing
     * unconditionally is safe: a url in [_recentPages] is filtered out and never reaches
     * [getSuggestions] again, and recording it as dismissed as well only matters if it later
     * turns up as a padding suggestion, which is the same answer the user just gave.
     */
    fun removePage(url: String): Job {
        // Applied on the caller's thread, not inside `scope.launch`. Both updates are in-memory
        // StateFlow writes, and `scheduleSave` launches its own debounced job, so the coroutine
        // bought nothing and cost the user a dispatch before the card disappeared.
        loadGuard.remove({ it.url == url }) {
            _recentPages.update { pages -> pages.filter { it.url != url } }
        }
        // Recorded only for a padding suggestion. A recorded page is excluded by being removed
        // from `_recentPages` above, so adding it here achieved nothing except growing a persisted
        // list with no cap - one entry for every page the user ever dismissed, while `pages`
        // itself is bounded at MAX_PAGES.
        val key = canonicalUrlKey(url)
        if (key in promoKeys) {
            _dismissedSuggestions.update { it + key }
        }
        // Immediately, not debounced: this is a user-initiated dismissal, and losing it to a quit
        // within the 5s window means the card returns and they dismiss it again. `removeMatchingPages`
        // already reasons about exactly this.
        val target = settingsFile
        return scope.launch { saveImmediately(target) }
    }

    /**
     * Remove every page that points at the same place as [url].
     *
     * Matching is by [canonicalUrlKey] rather than string equality, so a page recorded
     * under the URL the user typed is still found when the browser reports the URL it
     * actually tried to load. Used to retire entries for addresses that turn out not to
     * exist.
     *
     * @param recordedWithinMs when set, only removes pages visited that recently — the
     *   race where a title callback recorded a visit just before the browser reported the
     *   navigation as failed. Older entries are real history and are left alone. Null
     *   removes regardless of age.
     */
    fun removeMatchingPages(
        url: String,
        recordedWithinMs: Long? = null,
    ) {
        val key = canonicalUrlKey(url)
        if (key.isEmpty()) return

        scope.launch {
            val cutoff = recordedWithinMs?.let { System.currentTimeMillis() - it }
            var removed = 0
            val retire: (RecentBrowserPage) -> Boolean = { page ->
                shouldRetireVisit(page.url, page.lastVisited, page.visitCount, key, cutoff)
            }
            loadGuard.remove(retire) {
                _recentPages.update { pages ->
                    val remaining =
                        pages.filterNot(retire)
                    // Assigned inside the lambda: MutableStateFlow.update retries on
                    // contention, so only the winning invocation's value survives — which is
                    // exactly the count that matches the list we committed.
                    removed = pages.size - remaining.size
                    remaining
                }
            }
            if (removed > 0) {
                logger.info(
                    LogCategory.BROWSER,
                    "Removed recent pages for an address that failed to load",
                    mapOf(
                        "url" to LogSanitizer.maskUriParams(url),
                        "removed" to removed.toString(),
                    ),
                )
                // Immediately, not on the 5s debounce the additive path uses: the whole
                // scenario here is mistype, see the error page, close the app — which
                // lands inside that window and would leave the typo in the file to come
                // back as a dashboard recent on the next launch.
                saveImmediately()
            }
        }
    }

    /**
     * Clear all recent pages.
     */

    /**
     * Empty the strip: forget the recorded pages and dismiss every suggested site.
     *
     * **Permanent, and worth knowing.** The dismissals persist, so after one Clear the Recent pages
     * section stays empty until a real page is recorded, and there is no UI to bring the suggestions
     * back short of editing `~/.boss/recent-browser-pages.json`. That is the reading of "Clear" this
     * chooses deliberately - the alternative left seventeen undismissable promo cards behind - but it
     * is a property, not an accident.
     */
    fun clearAll(): Job {
        loadGuard.clear { _recentPages.value = emptyList() }
        // Dismiss the padding suggestions too, so "Clear" clears the strip the user is looking
        // at. Emptying only the recorded pages left all seventeen promo cards in place - and hid
        // the "Clear" label that had just failed to remove them, because that label is shown
        // only while recentPages is non-empty.
        // Unioned, not replaced: `removePage` also records real pages it dismissed, and
        // overwriting the set would discard those and let them return as padding later.
        _dismissedSuggestions.update { it + promoKeys }
        val target = settingsFile
        return scope.launch { saveImmediately(target) }
    }

    /**
     * Get the domain from a URL for display purposes.
     */
    fun getDomain(url: String): String =
        try {
            val withoutProtocol = url.removePrefix("https://").removePrefix("http://")
            withoutProtocol.substringBefore('/').substringBefore('?')
        } catch (e: Exception) {
            logger.debug(
                LogCategory.BROWSER,
                "Failed to parse domain from URL - showing full URL",
                mapOf("error" to e.toString()),
            )
            url
        }

    /**
     * Get suggestions combining recent pages with popular dev sites.
     * Uses hybrid ranking: visit count weighted heavily + recency decay.
     * Popular dev sites fill remaining slots if history has fewer entries.
     */
    fun getSuggestions(limit: Int = 8): List<RecentBrowserPage> =
        rankSuggestions(
            recent = _recentPages.value,
            dismissed = _dismissedSuggestions.value,
            popular = POPULAR_DEV_SITES,
            limit = limit,
            now = System.currentTimeMillis(),
        )
}

/**
 * The suggestion list: ranked history first, then padding from [popular].
 *
 * A pure function taking [now] rather than reading the clock, so `SuggestionDismissalTest` can
 * pin the dismissal rule without mutating a process-wide singleton or writing to the user's real
 * `~/.boss/recent-browser-pages.json` - which a test driving `clearAll` on the object would do.
 *
 * @param dismissed [canonicalUrlKey] values the user has dismissed. Applies to the padding only:
 *   a page in [recent] is excluded by being removed from [recent]. The dismissed set exists
 *   because the padding entries live in no persisted list, so before it the card's X filtered
 *   nothing and the suggestion re-appeared on the next frame.
 */
internal fun rankSuggestions(
    recent: List<RecentBrowserPage>,
    dismissed: Set<String>,
    popular: List<RecentBrowserPage>,
    limit: Int,
    now: Long,
): List<RecentBrowserPage> {
    val recentUrls = recent.map { it.url }.toSet()

    // Hybrid ranking: combine visit count with recency
    // - visitCount weighted heavily (multiply by 1000)
    // - recency normalized to hours for reasonable decay
    val rankedRecent =
        recent.sortedByDescending { page ->
            val hoursAgo = (now - page.lastVisited) / (1000.0 * 60 * 60)
            val recencyScore = maxOf(0.0, 100 - hoursAgo) // Decays over ~4 days
            (page.visitCount * 1000.0) + recencyScore
        }

    val suggestions = mutableListOf<RecentBrowserPage>()
    suggestions.addAll(rankedRecent.take(limit))

    if (suggestions.size < limit) {
        val padding =
            popular.filter { site ->
                !recentUrls.contains(site.url) && canonicalUrlKey(site.url) !in dismissed
            }
        suggestions.addAll(padding.take(limit - suggestions.size))
    }

    return suggestions.take(limit)
}

/**
 * Combine the pages read from disk with whatever was recorded in memory while that read was in
 * flight, newest first, capped at [max].
 *
 * The load is not an assignment because it is not the only writer: `init` launches it, and
 * `recordPageVisit` can land first. For a url on both sides the newer `lastVisited` supplies the
 * title and timestamp, the favicon falls back to the older entry when the newer one has none, and
 * `visitCount` is **summed** - the in-flight entry counts the visit that raced the load, the
 * loaded entry counts every visit before it, and `UrlHistoryManager` merges duplicates the same
 * way. Taking the newer entry wholesale would reset a long-lived page's count to 1.
 *
 * Summing means this is not idempotent: merging an output against the same loaded list again
 * doubles the counts. Safe today because `loadAsync` runs once from `init`; anything that adds a
 * reload has to reconsider this.
 */
internal fun mergeRecordedPages(
    loaded: List<RecentBrowserPage>,
    recorded: List<RecentBrowserPage>,
    max: Int,
): List<RecentBrowserPage> =
    (recorded + loaded)
        .groupBy { it.url }
        .map { (_, entries) -> entries.reduce(::combineVisits) }
        .sortedByDescending { it.lastVisited }
        .take(max)

/** Preserve dismissals recorded while the persisted set was being read, bounded to current promos. */
internal fun mergeDismissedSuggestions(
    loaded: Set<String>,
    recorded: Set<String>,
    allowed: Set<String>,
): Set<String> = (recorded + loaded) intersect allowed

/** Fold two entries for the same url into one; see [mergeRecordedPages] for the rules. */
private fun combineVisits(
    a: RecentBrowserPage,
    b: RecentBrowserPage,
): RecentBrowserPage {
    val newer = if (a.lastVisited >= b.lastVisited) a else b
    val older = if (newer === a) b else a
    return newer.copy(
        faviconCacheKey = newer.faviconCacheKey ?: older.faviconCacheKey,
        visitCount = a.visitCount + b.visitCount,
    )
}
