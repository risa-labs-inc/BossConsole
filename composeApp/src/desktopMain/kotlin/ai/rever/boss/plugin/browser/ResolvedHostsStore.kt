package ai.rever.boss.plugin.browser

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.decodeFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The hosts that have served this user a page at least once, remembered across restarts.
 *
 * This is what separates "you mistyped an address that has never existed" from "an address
 * you use every day isn't resolving from where you're sitting". A resolver-health check
 * can't tell those apart: it only sees that *some* name resolved, which stays true when a
 * developer is off the VPN and `jira.internal.corp` stops resolving while `github.com`
 * keeps working — a split-horizon outage looks identical to a typo from the outside.
 *
 * So a host that has ever loaded is never retired on a name-resolution failure, however
 * long it has been failing. `youtube.como` has never appeared here for anyone, because it
 * cannot load; that is exactly the set of addresses safe to forget.
 *
 * A title-based heuristic cannot stand in for this: Chromium titles its error document
 * with the failed host, so an entry created by a typo carries a title too.
 */
object ResolvedHostsStore {
    private val logger = BossLogger.forComponent("ResolvedHostsStore")

    /** Overridable so tests exercise the real read/write path without touching `~/.boss`. */
    internal var storeFile: File = BossDirectories.resolve("browser-resolved-hosts.json")

    private val hosts = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveLock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        load()
    }

    private fun load() {
        try {
            if (!storeFile.exists()) return
            val content = storeFile.readText()
            if (content.isEmpty()) return
            hosts.addAll(json.decodeFromString<List<String>>(content))
        } catch (e: IOException) {
            logger.warn(LogCategory.BROWSER, "Failed to read resolved hosts", error = e)
        } catch (e: SerializationException) {
            // A corrupt file just means starting over: the set is a cache of successes,
            // and the only cost of losing it is being cautious about eviction again.
            logger.warn(LogCategory.BROWSER, "Discarding unreadable resolved hosts", decodeFailure(e))
        }
    }

    /** Whether [host] has ever served a page, in this session or a previous one. */
    fun hasEverLoaded(host: String): Boolean = hosts.contains(host.lowercase())

    /**
     * Remember that [host] served a page. Persists only when the host is new, so the
     * common case — navigating around somewhere already known — costs a set lookup.
     */
    fun recordLoaded(host: String) {
        if (host.isBlank()) return
        if (hosts.add(host.lowercase())) {
            // Bind the destination now; the contents are read inside `save` under `saveLock`, at
            // the moment the write runs rather than here at the call site. That is what keeps two
            // recordLoaded calls landing together from writing out of order - the failure mode
            // pinned by `an older concurrent save never overwrites a newer one` in
            // ResolvedHostsStoreTest.
            save(storeFile)
        }
    }

    private fun save(target: File) {
        scope.launch {
            withContext(Dispatchers.IO) {
                saveLock.withLock {
                    try {
                        // Read `hosts` here, under the lock, rather than at the call site, so two
                        // recordLoaded calls in flight cannot publish out-of-order writes: whichever
                        // save runs last still serialises the current set, not a value frozen
                        // earlier. `hosts` is a ConcurrentHashMap-backed set, so this `toList()` is
                        // a weakly-consistent iteration (it never throws and reflects every add that
                        // happened-before it); the lock is what orders the writes, not the set.
                        val snapshot = hosts.toList().sorted()
                        target.atomicWriteText(json.encodeToString(snapshot))
                    } catch (e: IOException) {
                        logger.warn(LogCategory.BROWSER, "Failed to save resolved hosts", error = e)
                    }
                }
            }
        }
    }

    /** Drop everything. Used by tests. */
    internal fun clear() {
        hosts.clear()
    }

    /**
     * Save synchronously, in the calling thread, holding [saveLock]. Returns the bytes that
     * landed on disk so a test can pin the snapshot shape.
     *
     * This is not the production path: production fires [save] on [scope] with no wait. It shares
     * the parts that matter for correctness - the same [saveLock], the same snapshot-under-lock
     * read of `hosts`, the same [atomicWriteText] - but runs inline instead of on a coroutine, so a
     * test can read back the exact bytes deterministically. To await the launched production saves
     * instead of forcing a fresh one, use [awaitPendingSaves]; this method writes a new full
     * snapshot, which would mask an earlier out-of-order write.
     */
    internal fun saveNowBlocking(): String =
        runBlocking {
            saveLock.withLock {
                val snapshot = hosts.toList().sorted()
                val payload = json.encodeToString(snapshot)
                storeFile.atomicWriteText(payload)
                payload
            }
        }

    /**
     * Block until every [save] already launched on [scope] has finished. Test-only: it lets a
     * regression assert what the fire-and-forget production saves actually left on disk, without
     * writing a fresh full snapshot (which [saveNowBlocking] does and which would hide a stale
     * out-of-order write). Callers must have finished issuing their [recordLoaded] calls first, so
     * that all the save jobs to await already exist as children of [scope].
     */
    internal fun awaitPendingSaves() {
        runBlocking {
            scope.coroutineContext[Job]?.children?.forEach { it.join() }
        }
    }
}
