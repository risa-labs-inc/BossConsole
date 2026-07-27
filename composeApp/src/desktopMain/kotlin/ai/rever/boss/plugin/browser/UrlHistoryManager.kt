package ai.rever.boss.plugin.browser

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

object UrlHistoryManager {
    private val logger = BossLogger.forComponent("UrlHistoryManager")
    private val historyFile = BossDirectories.resolve("browser-history.json")
    private val history = ConcurrentHashMap<String, UrlHistoryEntry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
                    entries.forEach { entry ->
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
            try {
                historyFile.parentFile?.mkdirs()
                val entries =
                    history.values
                        .toList()
                        .sortedByDescending { it.visitCount * 1000 + (it.lastVisited / 1000000) }
                        .take(1000) // Keep only top 1000 entries
                historyFile.writeText(json.encodeToString(entries))
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "Failed to save browser history", error = e)
            }
        }

    fun addUrl(
        url: String,
        title: String,
    ) {
        val domain = suggestableHostOrNull(url)

        // A page the browser never managed to load is not somewhere the user has been —
        // suggesting it back to them is how a typo like `youtube.como` became permanent.
        if (domain == null || NavigationOutcomeTracker.didFail(url)) return

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

    /**
     * The host of [url] when it is an address worth offering back as a suggestion, else
     * null.
     *
     * Suggestions are matched and displayed by domain, so anything without one —
     * `about:blank`, `data:`, `blob:`, `chrome://`, `file://` — has nothing to contribute
     * to the list, and an unparsable URL has no business in it either.
     */
    private fun suggestableHostOrNull(url: String): String? =
        try {
            val parsed = java.net.URL(url)
            val scheme = parsed.protocol?.lowercase()
            val host = parsed.host?.lowercase().orEmpty()
            if ((scheme == "http" || scheme == "https") && host.isNotBlank()) host else null
        } catch (e: Exception) {
            logger.debug(
                LogCategory.BROWSER,
                "Ignoring history entry with unparsable URL",
                mapOf("error" to e.toString()),
            )
            null
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

    fun deleteUrl(url: String) {
        history.remove(url)
    }

    /**
     * Remove every entry that points at the same place as [url].
     *
     * Matching is by [canonicalUrlKey] rather than string equality: an entry recorded
     * before this gating existed may be stored as whatever the user typed
     * (`youtube.como`), while the engine reports the URL it tried to load
     * (`https://youtube.como/`).
     *
     * @return the number of entries removed
     */
    fun removeMatchingUrls(url: String): Int {
        val key = canonicalUrlKey(url)
        val matches =
            if (key.isEmpty()) emptyList() else history.keys.filter { canonicalUrlKey(it) == key }

        if (matches.isNotEmpty()) {
            matches.forEach { history.remove(it) }
            // Persist here rather than waiting for the next saveHistory() — an evicted
            // entry that survives in the file is exactly the stale suggestion we just
            // removed.
            scope.launch { saveHistory() }
        }
        return matches.size
    }
}
