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

/**
 * The terms a query asks for: whitespace-separated and lowercased.
 *
 * Multi-term input is how a browser finds a page you remember two words of - "boss pulls"
 * reaching `github.com/risa-labs-inc/BossConsole/pulls` - and it is why matching cannot be a
 * single `contains`: the query as one string appears nowhere in that URL.
 */
private fun queryTerms(query: String): List<String> = query.lowercase().split(WHITESPACE).filter { it.isNotEmpty() }

private val WHITESPACE = Regex("\\s+")

/**
 * Whether [i] is where a word begins in [text].
 *
 * A run of letters or digits is one word, and a term may only match where such a run starts.
 * That single rule is what keeps "loc" out of `block` and out of the `…%2Flocalhost…` buried
 * in an OAuth redirect parameter, both of which a substring match surfaced ahead of the page
 * the user was actually reaching for.
 *
 * A lower-to-upper step counts as a word start too, so "console" still finds `BossConsole`.
 * Upper-to-lower deliberately does NOT: it would put `%2FLocalhost` straight back.
 */
private fun isWordStart(
    text: String,
    i: Int,
): Boolean {
    if (i == 0) return true
    val previous = text[i - 1]
    return !previous.isLetterOrDigit() ||
        ((previous.isLowerCase() || previous.isDigit()) && text[i].isUpperCase())
}

/**
 * Whether [term] starts a word anywhere in [haystack], case-insensitively.
 */
internal fun startsWord(
    haystack: String,
    term: String,
): Boolean {
    if (term.isEmpty() || term.length > haystack.length) return false
    // An index loop rather than `(0..n).any { }`. `Iterable.any` is inline, so the lambda
    // costs nothing, but inside it the receiver's static type is `Iterable<Int>` and the
    // iterator hands back boxed `Integer`s - and past the 127 the box cache covers, that is
    // an allocation per index, in the innermost loop of the per-keystroke scan.
    var i = 0
    var found = false
    val last = haystack.length - term.length
    while (!found && i <= last) {
        found = isWordStart(haystack, i) && haystack.startsWith(term, i, ignoreCase = true)
        i++
    }
    return found
}

/**
 * What matching needs to know about one stored URL, derived from the URL string alone.
 *
 * [canonicalUrlKey] scans and [suggestableHost] constructs a `java.net.URL` - and throws and
 * unwinds a `MalformedURLException` for every malformed row - and both used to run per entry
 * per KEYSTROKE. Neither depends on anything but the URL, so the result is memoized on it
 * and the hot loop is left as string scanning.
 *
 * Keyed by the whole URL and never invalidated, because there is nothing to invalidate: the
 * same string always derives the same facts. That leaves only growth to bound, and the store
 * these describe is itself capped, so the table is emptied rather than evicted from once it
 * drifts past the cap - a stale key costs one re-derive, and re-deriving is what this used
 * to do every time.
 */
private class UrlFacts(
    val address: String,
    val suggestable: Boolean,
)

private val urlFacts = ConcurrentHashMap<String, UrlFacts>()

private fun factsFor(url: String): UrlFacts =
    // `computeIfAbsent`, not `getOrPut`. The latter is the plain Map extension - a get, then
    // a put - so two threads racing the same URL both build a `java.net.URL` and both unwind
    // a `MalformedURLException` for a malformed row. Not a correctness problem, since this is
    // a pure function of the key, but paying that twice is the exact cost the table exists to
    // avoid.
    urlFacts.computeIfAbsent(url) {
        val address = canonicalUrlKey(url)
        // [hasUserinfo] rather than the same expression spelled out here: the URL field's
        // completion refuses these by that rule, and a list beside it that accepted them
        // would harden half a surface.
        UrlFacts(address = address, suggestable = suggestableHost(url) != null && !hasUserinfo(address))
    }

/**
 * One matched entry with the facts its ranking needs, computed once.
 *
 * [canonicalUrlKey] parses, and reading it from inside a comparator would parse the same URL
 * once per comparison rather than once per entry.
 */
private class RankedMatch(
    val entry: UrlHistoryEntry,
    val completableRoot: Boolean,
    val addressPrefix: Boolean,
    val wordStart: Boolean,
    val addressMatch: Boolean,
)

/**
 * The entries that answer [query], best first.
 *
 * Matching follows what a browser's address bar does rather than what is easiest to write:
 *  - EVERY term must match, and each may match in the address or in the page title.
 *  - a term that matches at the start of a word (see [startsWord]) outranks one that matches
 *    mid-word. Both are kept: word starts alone put "loc" one keystroke from a PR titled
 *    "…block…" out of the list, but they also lost "tube" for youtube.com and "hub" for
 *    github.com. Mid-word matches are answered, just underneath.
 *  - the address is matched in its canonical spelling - no scheme, no `www.`, no trailing
 *    slash - so what the user types lines up with what they see.
 *
 * Ranking is four tiers. The first exists so the row on top is the row the field's ghost
 * text has already filled in - two different proposals on screen at once is one too many,
 * and it is why the most-visited page on a host does not outrank the host itself while the
 * host is still being typed:
 *  1. the host root, on a host the query is a prefix of. This is the completable match: it is
 *     what `inlineUrlCompletion` fills in, because a host completes before a path does.
 *  2. the address starts with the whole query. This takes over once the host is typed out and
 *     the completion moves on to paths, and it subsumes "any other page on a matching host":
 *     an address always begins with its own authority, so a host prefix is an address prefix.
 *  3. every term matched at the start of a word, rather than mid-word.
 *  4. the address matches without help from the title. A page whose URL you are typing beats
 *     one that merely mentions the words somewhere in its title.
 *  5. frecency - visits, with recency breaking ties. See [rankOf].
 *
 * Prefixes are matched case-INSENSITIVELY. [canonicalUrlKey] lowercases the authority but
 * leaves the path alone, so a case-sensitive test dropped every mixed-case path out of tier 2
 * the moment the user typed into it - and then the top row disagreed with the ghost text,
 * which matches case-insensitively.
 *
 * Pure, taking [now], so the tiers can be pinned by a test without a clock or a real history
 * file.
 */
internal fun rankMatches(
    entries: Collection<UrlHistoryEntry>,
    query: String,
    limit: Int,
    now: Long,
): List<UrlHistoryEntry> {
    // A pasted address carries a scheme, and maybe a `www.`, neither of which appears in the
    // canonical form the entries are matched in - so the whole paste became one term longer
    // than any address and matched nothing at all. Normalizing the query the same way the
    // entries are is what makes pasting a URL you visit daily find it.
    val normalized = if (query.contains("://")) canonicalUrlKey(query) else query.trim()
    val terms = queryTerms(normalized)
    if (terms.isEmpty()) return emptyList()
    // Floored HERE, at the function that would throw, rather than at a call site. `take`
    // rejects a negative count, and one of the callers is `DesktopUrlHistoryProvider` -
    // `PluginContext.urlHistoryProvider` - so this limit arrives from plugin code.
    val wanted = limit.coerceAtLeast(0)

    // The memo table is bounded here rather than inside `factsFor`, and by RETAINING what
    // the store still holds rather than emptying it. Clearing put a cliff on the typing
    // path: a working set hovering near the cap paid a full rebuild - a thousand
    // `java.net.URL` constructions, `MalformedURLException` unwinds included - on the
    // keystroke after one new page was visited. This walks the entries once, and only when
    // the table has actually drifted past them.
    if (urlFacts.size > MAX_ENTRIES + PRUNE_SLACK) {
        urlFacts.keys.retainAll(entries.mapTo(HashSet()) { it.url })
    }
    // Not lowercased: both readers below pass `ignoreCase = true`, so a second lowercasing
    // here only made it look as though one of the two were load-bearing.
    val typed = normalized

    return entries
        .mapNotNull { entry ->
            val facts = factsFor(entry.url)
            // The gate `addUrl` applies to new visits, applied here to what gets SUGGESTED.
            // It cannot be applied on load: `loadHistory` feeds the map that `saveHistory`
            // writes back, so dropping an entry there silently purges it from the user's
            // history file. A `javascript:` or `file://` row in a legacy or tampered file is
            // not something to offer as a completion the field fills in and Enter opens.
            if (!facts.suggestable) return@mapNotNull null
            // Capped for the same reason the title is, and the reason is stronger here: a
            // stored URL is at least as attacker-influenceable as a page's own title, and
            // the `isUnofferableAddress` KDoc puts a stored OAuth URL at 500-2000
            // characters - every one of which `startsWord` walked, per term, per keystroke.
            // What is lost past the cap is a match buried deeper in a URL than anyone types,
            // which is the noise this matcher exists to stop answering.
            val address = facts.address.take(MAX_ADDRESS_LENGTH)
            // Capped HERE as well as in `addUrl`, because a file written by an older build
            // can hold a title of any length, and this is the loop the cap exists to bound.
            // Capping in `loadHistory` instead would be destructive: that map is what
            // `saveHistory` writes back, so it would truncate the user's own file.
            // `take` returns the receiver when it is already short enough, so the common
            // case allocates nothing.
            val title = entry.title.take(MAX_TITLE_LENGTH)
            // Per-term address hits, computed ONCE. Falling through to the title used to
            // re-scan the address for every term, which is the common case (most entries
            // do not match) and measured about a third of the whole matching pass.
            val addressHits = BooleanArray(terms.size) { startsWord(address, terms[it]) }
            val addressWordStart = addressHits.all { it }
            val wordStart =
                addressWordStart || terms.indices.all { addressHits[it] || startsWord(title, terms[it]) }
            // The fallback: every term appears SOMEWHERE. Only consulted when no word-start
            // reading of the query works, so it can never displace a word-start hit.
            val loose =
                !wordStart &&
                    terms.all { address.contains(it, ignoreCase = true) || title.contains(it, ignoreCase = true) }
            if (!wordStart && !loose) {
                null
            } else {
                RankedMatch(
                    entry = entry,
                    // `address == domain` is what makes this the host's own root page rather
                    // than something under it.
                    completableRoot =
                        entry.domain.startsWith(typed, ignoreCase = true) && address == entry.domain,
                    addressPrefix = address.startsWith(typed, ignoreCase = true),
                    wordStart = wordStart,
                    addressMatch =
                        if (wordStart) {
                            addressWordStart
                        } else {
                            terms.all { address.contains(it, ignoreCase = true) }
                        },
                )
            }
        }.sortedWith(
            compareBy(
                { !it.completableRoot },
                { !it.addressPrefix },
                { !it.wordStart },
                { !it.addressMatch },
                { -rankOf(it.entry, now) },
            ),
        ).take(wanted)
        // Capped on the way OUT as well as on the way in. `maxLines = 1` truncates what a
        // dropdown row DRAWS, not what Compose measures, so an uncapped title from an older
        // file still laid out in full on every keystroke - which is the cost the cap exists
        // to remove. This copy is the matcher's own return value and never reaches the map
        // `saveHistory` writes back, so the user's file keeps the title it had.
        // `copy` only where it changes something: an already-short title is the overwhelming
        // case, and the surrounding code is careful about exactly this kind of allocation.
        .map { match ->
            val entry = match.entry
            if (entry.title.length <= MAX_TITLE_LENGTH) {
                entry
            } else {
                entry.copy(title = entry.title.take(MAX_TITLE_LENGTH))
            }
        }
}

/**
 * Drop everything past [MAX_ENTRIES] once [history] has drifted [PRUNE_SLACK] past it.
 *
 * The cap used to apply only on the way to disk, so the in-memory map grew with every
 * distinct page visited for the whole life of the process - and BOSS is a long-lived desktop
 * app. That was survivable while matching was a `contains` per entry; it is not now that
 * every keystroke canonicalises and word-scans each one (measured 0.4-1.7ms at 1000 entries,
 * 7-52ms at 10000, which is a visible stall while typing).
 *
 * The slack is what keeps this amortised: pruning is a sort, so doing it on every visit past
 * the cap would pay O(n log n) per navigation.
 *
 * Keeps the map's OWN keys rather than re-deriving them with [distinctPageKey]. Re-deriving
 * was correct only while every insertion path keyed by exactly that function - an invariant
 * held by convention across three call sites, and one whose failure mode is `retainAll`
 * emptying the store rather than anything visible.
 *
 * The read-then-retain is not atomic. An entry recorded by another thread between the sort
 * and the retain is dropped, which costs one history row; `addUrl` is driven by the browser
 * plugin's title listener, so that is reachable. Left as is rather than locked, because the
 * alternative is holding a lock across a sort on the navigation path.
 */
internal fun pruneIfOversized(
    history: MutableMap<String, UrlHistoryEntry>,
    now: Long = System.currentTimeMillis(),
) {
    if (history.size <= MAX_ENTRIES + PRUNE_SLACK) return
    val survivors = bestEntries(history.entries, MAX_ENTRIES, now) { it.value }.mapTo(HashSet()) { it.key }
    history.keys.retainAll(survivors)
}

/**
 * The [limit] best-ranked of whatever an entry can be read out of, best first.
 *
 * Top-level and pure so both callers - the file write and the in-memory prune - order the
 * store the same way, and so the ordering is testable without the object's `init` reading
 * the developer's real history file.
 *
 * [entryOf] exists so the prune can rank the map's ENTRIES and keep their own keys, rather
 * than re-deriving a key from each surviving value and trusting that to match how it was
 * stored.
 */
internal fun <T> bestEntries(
    items: Collection<T>,
    limit: Int,
    now: Long,
    entryOf: (T) -> UrlHistoryEntry,
): List<T> = items.sortedByDescending { rankOf(entryOf(it), now) }.take(limit)

/** How many entries the store keeps, in memory and on disk. */
private const val MAX_ENTRIES = 1000

/** How far past [MAX_ENTRIES] the map may drift before a prune, so pruning stays amortised. */
private const val PRUNE_SLACK = 200

/**
 * Longest page title a match will scan. Attacker-controlled - a page sets its own
 * `document.title` - and word-scanned on every keystroke of every URL field.
 *
 * Enforced in three places on purpose, because a history file written before the cap existed
 * still holds whatever the page put there: `addUrl` caps what is STORED, [rankMatches] caps
 * what it SCANS, and it caps what it RETURNS so the dropdown does not lay out a paragraph
 * per row per keystroke. Not capped in `loadHistory`, which feeds the map `saveHistory`
 * writes back and would rewrite the user's own file.
 */
private const val MAX_TITLE_LENGTH = 256

/**
 * Longest stored address a match will scan.
 *
 * Larger than [MAX_TITLE_LENGTH] because a path carries meaning a title does not, and a
 * legitimate deep link runs longer than a headline. Bounds the same scan for the same
 * reason, and like the title cap it applies to what is READ, never to what is stored.
 */
private const val MAX_ADDRESS_LENGTH = 512

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
        return bestEntries(history.values, MAX_ENTRIES, now) { it }
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
                    // Capped: a page controls its own title, and the whole thing is
                    // word-scanned on every keystroke of every URL field.
                    title = title.ifBlank { existing.title }.take(MAX_TITLE_LENGTH),
                    domain = domain,
                    visitCount = existing.visitCount + 1,
                    lastVisited = System.currentTimeMillis(),
                )
            } else {
                UrlHistoryEntry(
                    url = url,
                    title = title.take(MAX_TITLE_LENGTH),
                    domain = domain,
                    visitCount = 1,
                    lastVisited = System.currentTimeMillis(),
                )
            }
        pruneIfOversized(history)
    }

    /** See [rankMatches] for the matching and ranking rules. */
    fun getSuggestions(
        query: String,
        limit: Int = 10,
    ): List<UrlHistoryEntry> = rankMatches(history.values, query, limit, System.currentTimeMillis())

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
