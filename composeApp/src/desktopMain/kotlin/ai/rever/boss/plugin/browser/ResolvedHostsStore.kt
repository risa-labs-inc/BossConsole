package ai.rever.boss.plugin.browser

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.atomicWriteText
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
            logger.warn(LogCategory.BROWSER, "Discarding unreadable resolved hosts", error = e)
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
            // Bind the destination and the contents now rather than inside the coroutine:
            // the write is what must land, and reading either one later would let an
            // unrelated change in between decide where it goes or what it says.
            save(storeFile, hosts.toList().sorted())
        }
    }

    private fun save(
        target: File,
        snapshot: List<String>,
    ) {
        scope.launch {
            withContext(Dispatchers.IO) {
                saveLock.withLock {
                    try {
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
}
