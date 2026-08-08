package ai.rever.boss.plugin.browser

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class UrlHistoryEntry(
    val url: String,
    val title: String,
    val domain: String,
    val visitCount: Int = 1,
    val lastVisited: Long = System.currentTimeMillis(),
)

/**
 * Collapse entries that are the same page under different spellings.
 *
 * History recorded before the browser plugin reported committed URLs holds both what the
 * user typed (`https://youtube.com`, no title) and what the browser loaded
 * (`https://www.youtube.com/`, "YouTube") — two suggestions for one site, one of them
 * titleless. Merging keeps the entry that knows the page's title, sums the visit counts so
 * ranking isn't split between the two, and takes the later visit. An `https` spelling wins
 * over its plaintext twin regardless of counts: the merged URL is one we will navigate to.
 *
 * Grouping is by [distinctPageKey], NOT the fragment-insensitive [canonicalUrlKey]:
 * hash-routed apps put genuinely different pages behind one path, so merging on the looser
 * key would fold every Gmail view (`#inbox`, `#sent`, `#search/…`) into one.
 *
 * Top-level rather than a member so it can be tested without [UrlHistoryManager]'s `init`
 * reading the developer's real history file.
 */
internal fun mergeDuplicateHistoryEntries(entries: List<UrlHistoryEntry>): List<UrlHistoryEntry> =
    entries
        .groupBy { distinctPageKey(it.url) }
        .map { (_, group) ->
            if (group.size == 1) {
                group.first()
            } else {
                // The URL and the title are chosen independently: the surviving URL is one
                // we will navigate to, so https wins outright, but the title comes from
                // whichever spelling actually rendered the page. Picking one entry whole
                // would force a choice between a safe URL and a known title.
                val primary =
                    group
                        .sortedWith(
                            compareByDescending<UrlHistoryEntry> {
                                it.url.startsWith("https", ignoreCase = true)
                            }.thenByDescending { it.title.isNotBlank() }
                                .thenByDescending { it.visitCount }
                                .thenByDescending { it.lastVisited },
                        ).first()
                val bestTitle =
                    group
                        .filter { it.title.isNotBlank() }
                        .maxByOrNull { it.lastVisited }
                        ?.title
                primary.copy(
                    title = bestTitle ?: primary.title,
                    visitCount = group.sumOf { it.visitCount },
                    lastVisited = group.maxOf { it.lastVisited },
                )
            }
        }

/**
 * The entries that a failed navigation to [url] should retire.
 *
 * Pure so the destructive decision is testable without touching a real history file.
 *
 * @param recordedWithinMs when set, this is the **retraction** case: undoing a visit a
 *   title callback recorded moments before the browser reported the navigation as failed.
 *   It only takes entries that the racing callback could itself have created — a single
 *   visit — because `lastVisited` is refreshed on every visit, so "touched in the last 5s"
 *   would otherwise also describe a site with three hundred visits that was simply open a
 *   moment ago. Null is the **eviction** case: the address itself is gone, and every
 *   spelling of it goes regardless of age or visit count.
 */
internal fun entriesToEvict(
    entries: Collection<UrlHistoryEntry>,
    url: String,
    recordedWithinMs: Long?,
    now: Long = System.currentTimeMillis(),
): List<UrlHistoryEntry> {
    val key = canonicalUrlKey(url)
    if (key.isEmpty()) return emptyList()

    val cutoff = recordedWithinMs?.let { now - it }
    return entries.filter { entry ->
        shouldRetireVisit(entry.url, entry.lastVisited, entry.visitCount, key, cutoff)
    }
}

/**
 * How strongly an entry should be suggested: visits dominate, recency breaks ties.
 *
 * The previous expression — `visitCount * 1000 + lastVisited / 1_000_000` — was effectively
 * pure recency, because the recency term is around 1.75e6 at current epoch milliseconds, so
 * a site needed roughly 1750 visits before its count moved it at all. Bounding recency to a
 * 0..100 decay over a few days restores what the comment always claimed. Matches
 * `RecentBrowserPagesManager.getSuggestions`, so the dashboard and the URL bar order the
 * same history the same way.
 */
internal fun rankOf(
    entry: UrlHistoryEntry,
    now: Long,
): Double {
    val hoursAgo = (now - entry.lastVisited) / (1000.0 * 60 * 60)
    val recencyScore = maxOf(0.0, 100 - hoursAgo)
    return (entry.visitCount * 1000.0) + recencyScore
}

object UrlHistoryManager {
    private val logger = BossLogger.forComponent("UrlHistoryManager")

    /** Overridable so tests exercise the real read/write path without touching `~/.boss`. */
    internal var historyFile: File = BossDirectories.resolve("browser-history.json")

    /**
     * Keyed by [distinctPageKey], not by the raw URL.
     *
     * Two spellings of one page — `https://x.com` as typed, `https://www.x.com/` as
     * committed — used to occupy two slots and show up as two suggestions until a restart
     * merged them. Keying by page identity means the second recording finds the first, so
     * [mergeDuplicateHistoryEntries] is a one-time migration for old files rather than a
     * repair that has to run on every load.
     */
    private val history = ConcurrentHashMap<String, UrlHistoryEntry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * One writer at a time. [saveHistory] is public and fires from several directions —
     * the browser plugin on every page load, a deletion, an eviction — and two overlapping
     * writes would interleave into a file that no longer parses, which [loadHistory]
     * reports as "no history" and silently starts empty.
     */
    private val saveLock = Mutex()

    /**
     * The most recent background write, so a caller that needs the file to be current can
     * wait for it. Deletions and evictions persist without blocking the caller, which
     * leaves no other way to know when the bytes have landed.
     */
    @Volatile
    private var pendingWrite: Job? = null
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    init {
        loadHistory()
    }

    internal fun loadHistory() {
        try {
            history.clear()
            val content = if (historyFile.exists()) historyFile.readText() else ""
            if (content.isEmpty()) return

            val entries = json.decodeFromString<List<UrlHistoryEntry>>(content)
            mergeDuplicateHistoryEntries(entries).forEach { entry ->
                // Recompute the domain rather than trusting the stored one: files written
                // before host normalization was shared carry a `www.` that keeps the entry
                // out of the domain-prefix bucket when ranking.
                val domain = suggestableHost(entry.url) ?: entry.domain
                history[distinctPageKey(entry.url)] = entry.copy(domain = domain)
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Failed to load browser history", error = e)
        }
    }

    suspend fun saveHistory() = writeTo(historyFile, entriesToPersist())

    /**
     * The entries a save would write: best first, capped.
     *
     * Snapshotted by the caller rather than read inside the write, so what lands on disk
     * is the history as it stood when the save was asked for.
     */
    private fun entriesToPersist(): List<UrlHistoryEntry> {
        val now = System.currentTimeMillis()
        return history.values
            .toList()
            .sortedByDescending { rankOf(it, now) }
            .take(1000) // Keep only top 1000 entries
    }

    /**
     * Write [entries] to [target].
     *
     * Both are parameters, never fields read here: a background save that resolved its
     * destination at execution time would follow [historyFile] if it changed in between,
     * which is how a test pointing the store at a scratch file wrote an empty history over
     * the real one.
     */
    private suspend fun writeTo(
        target: File,
        entries: List<UrlHistoryEntry>,
    ) = withContext(Dispatchers.IO) {
        saveLock.withLock {
            try {
                // Atomic: a crash or a concurrent writer leaves the previous file intact
                // rather than a half-written one.
                target.atomicWriteText(json.encodeToString(entries))
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "Failed to save browser history", error = e)
            }
        }
    }

    fun addUrl(
        url: String,
        title: String,
    ) {
        // A page the browser never managed to load is not somewhere the user has been —
        // suggesting it back to them is how a typo like `youtube.como` became permanent.
        val domain = suggestableHost(url) ?: return
        if (NavigationOutcomeTracker.didFail(url)) return

        // Analytics is NOT emitted here. It applies these same two gates — a real http(s)
        // host and a page that actually loaded — but from BrowserHandleImpl's navigation
        // handler, which is the only place that knows *which tab* navigated. Dwell time and
        // navigation depth are per-tab, and this entry point has no tab identity at all
        // (UrlHistoryProvider.addUrl takes a url and a title), so tracking here would
        // interleave every tab's visits into one bogus timeline.

        val key = distinctPageKey(url)
        val existing = history[key]

        history[key] =
            if (existing != null) {
                existing.copy(
                    // Keep the URL the browser committed over one a caller typed: it is
                    // the spelling we will navigate back to.
                    url = if (url.startsWith("https", ignoreCase = true)) url else existing.url,
                    title = title.ifBlank { existing.title },
                    domain = domain,
                    visitCount = existing.visitCount + 1,
                    lastVisited = System.currentTimeMillis(),
                )
            } else {
                UrlHistoryEntry(
                    url = url,
                    title = title,
                    domain = domain,
                    visitCount = 1,
                    lastVisited = System.currentTimeMillis(),
                )
            }
    }

    fun getSuggestions(
        query: String,
        limit: Int = 10,
    ): List<UrlHistoryEntry> {
        if (query.isBlank()) return emptyList()

        val lowerQuery = query.lowercase()
        val now = System.currentTimeMillis()

        return history.values
            .filter { entry ->
                entry.domain.contains(lowerQuery) ||
                    entry.url.contains(lowerQuery) ||
                    entry.title.lowercase().contains(lowerQuery)
            }.sortedWith(
                compareBy(
                    // Prioritize domain starts with query
                    { !it.domain.startsWith(lowerQuery) },
                    // Then URL starts with query. Matched against the canonical form —
                    // no scheme, no `www.` — so a stored `www.` doesn't sink an entry the
                    // user is plainly typing towards.
                    { !canonicalUrlKey(it.url).startsWith(lowerQuery) },
                    // Then by visit count, with recency breaking ties
                    { -rankOf(it, now) },
                ),
            ).take(limit)
    }

    /**
     * Forget a single entry — the URL bar's "don't suggest this again".
     *
     * Persists immediately: a deletion the user has to repeat after a restart isn't a
     * deletion.
     */
    fun deleteUrl(url: String) {
        // By page identity, so a caller holding a different spelling of the same entry
        // than the one stored still removes it.
        if (history.remove(distinctPageKey(url)) != null) {
            persistInBackground()
        }
    }

    private fun persistInBackground() {
        val target = historyFile
        val entries = entriesToPersist()
        pendingWrite = scope.launch { writeTo(target, entries) }
    }

    /** Wait for any background write to reach disk. */
    internal suspend fun awaitPendingWrites() {
        pendingWrite?.join()
    }

    /**
     * Remove every entry that points at the same place as [url].
     *
     * Matching is by [canonicalUrlKey] rather than string equality: an entry recorded
     * before this gating existed may be stored as whatever the user typed
     * (`youtube.como`), while the engine reports the URL it tried to load
     * (`https://youtube.como/`).
     *
     * @param recordedWithinMs when set, only removes entries last visited that recently.
     *   This is the narrow case: a title callback that raced ahead of the navigation
     *   verdict and recorded a visit the browser was about to report as failed. Entries
     *   older than the window are real history — a site that is down today was still
     *   visited last week — and are left alone. Pass null to evict regardless of age,
     *   for failures that mean the address does not exist at all.
     * @return the number of entries removed
     */
    fun removeMatchingUrls(
        url: String,
        recordedWithinMs: Long? = null,
    ): Int {
        val matches = entriesToEvict(history.values, url, recordedWithinMs)

        if (matches.isNotEmpty()) {
            matches.forEach { history.remove(distinctPageKey(it.url)) }
            logger.info(
                LogCategory.BROWSER,
                "Removed history entries for an address that failed to load",
                mapOf(
                    "url" to LogSanitizer.maskUriParams(url),
                    "removed" to matches.size.toString(),
                    "scope" to if (recordedWithinMs == null) "all" else "recent",
                ),
            )
            // Persist here rather than waiting for the next saveHistory() — an evicted
            // entry that survives in the file is exactly the stale suggestion we just
            // removed.
            persistInBackground()
        }
        return matches.size
    }
}
