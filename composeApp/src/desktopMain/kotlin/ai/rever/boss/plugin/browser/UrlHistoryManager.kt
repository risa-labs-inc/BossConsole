package ai.rever.boss.plugin.browser

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

object UrlHistoryManager {
    private val logger = BossLogger.forComponent("UrlHistoryManager")
    private val historyFile = BossDirectories.resolve("browser-history.json")
    private val history = ConcurrentHashMap<String, UrlHistoryEntry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * One writer at a time. [saveHistory] is public and fires from several directions —
     * the browser plugin on every page load, a deletion, an eviction — and two overlapping
     * writes would interleave into a file that no longer parses, which [loadHistory]
     * reports as "no history" and silently starts empty.
     */
    private val saveLock = Mutex()
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    init {
        loadHistory()
    }

    private fun loadHistory() {
        try {
            if (historyFile.exists()) {
                val content = historyFile.readText()
                if (content.isNotEmpty()) {
                    val entries = json.decodeFromString<List<UrlHistoryEntry>>(content)
                    mergeDuplicateHistoryEntries(entries).forEach { entry ->
                        history[entry.url] = entry
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Failed to load browser history", error = e)
        }
    }

    suspend fun saveHistory() =
        withContext(Dispatchers.IO) {
            saveLock.withLock {
                try {
                    val entries =
                        history.values
                            .toList()
                            .sortedByDescending { it.visitCount * 1000 + (it.lastVisited / 1000000) }
                            .take(1000) // Keep only top 1000 entries
                    // Atomic: a crash or a concurrent writer leaves the previous file
                    // intact rather than a half-written one.
                    historyFile.atomicWriteText(json.encodeToString(entries))
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

        val existing = history[url]

        history[url] =
            if (existing != null) {
                existing.copy(
                    title = title.ifBlank { existing.title },
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

        return history.values
            .filter { entry ->
                entry.domain.contains(lowerQuery) ||
                    entry.url.contains(lowerQuery) ||
                    entry.title.lowercase().contains(lowerQuery)
            }.sortedWith(
                compareBy(
                    // Prioritize domain starts with query
                    { !it.domain.startsWith(lowerQuery) },
                    // Then URL starts with query
                    {
                        !it.url
                            .removePrefix("https://")
                            .removePrefix("http://")
                            .startsWith(lowerQuery)
                    },
                    // Then by visit count and recency
                    { -(it.visitCount * 1000 + (it.lastVisited / 1000000)) },
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
        if (history.remove(url) != null) {
            scope.launch { saveHistory() }
        }
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
            matches.forEach { history.remove(it.url) }
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
            scope.launch { saveHistory() }
        }
        return matches.size
    }
}
