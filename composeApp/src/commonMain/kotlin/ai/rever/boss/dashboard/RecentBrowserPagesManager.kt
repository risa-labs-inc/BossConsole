package ai.rever.boss.dashboard

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import ai.rever.boss.plugin.pathutils.BossDirectories

/**
 * Data class representing a recently visited browser page.
 */
@Serializable
data class RecentBrowserPage(
    val url: String,
    val title: String,
    val lastVisited: Long,
    val faviconCacheKey: String? = null,
    val visitCount: Int = 1
)

/**
 * Container for recent browser pages data with serialization support.
 */
@Serializable
data class RecentBrowserPagesData(
    val pages: List<RecentBrowserPage> = emptyList()
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
    val lastVisited: Long = 0
)

/**
 * Manages recently visited browser pages for the Dashboard.
 * Persists to ~/.boss/recent-browser-pages.json
 *
 * Thread-safe: All file I/O operations run on Dispatchers.IO.
 * Uses StateFlow for reactive UI updates.
 */
object RecentBrowserPagesManager {
    private val logger = BossLogger.forComponent("RecentBrowserPagesManager")
    private const val MAX_PAGES = 30
    private const val SAVE_DEBOUNCE_MS = 5000L // Debounce saves to max once per 5 seconds
    private val settingsFile = BossDirectories.resolve("recent-browser-pages.json")
    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var saveJob: Job? = null

    private val _recentPages = MutableStateFlow<List<RecentBrowserPage>>(emptyList())
    val recentPages: StateFlow<List<RecentBrowserPage>> = _recentPages.asStateFlow()

    // Popular developer websites for suggestions
    private val POPULAR_DEV_SITES = listOf(
        RecentBrowserPage(
            url = "https://risalabs.ai",
            title = "Risa Labs",
            lastVisited = 0L,
            faviconCacheKey = null,
            visitCount = 0
        ),
        RecentBrowserPage(
            url = "https://github.com/risa-labs-inc/BossConsole-Releases",
            title = "BossConsole Releases",
            lastVisited = 0L,
            faviconCacheKey = null,
            visitCount = 0
        ),
        RecentBrowserPage(
            url = "https://formulae.brew.sh/cask/boss",
            title = "BOSS - Homebrew",
            lastVisited = 0L,
            faviconCacheKey = null,
            visitCount = 0
        ),
        RecentBrowserPage(
            url = "https://github.com/kshivang/BossTerm",
            title = "BossTerm",
            lastVisited = 0L,
            faviconCacheKey = null,
            visitCount = 0
        ),
        RecentBrowserPage(
            url = "https://chat.openai.com",
            title = "ChatGPT",
            lastVisited = 0L,
            faviconCacheKey = null,
            visitCount = 0
        ),
        RecentBrowserPage(
            url = "https://claude.ai",
            title = "Claude",
            lastVisited = 0L,
            faviconCacheKey = null,
            visitCount = 0
        ),
        RecentBrowserPage(
            url = "https://grok.com",
            title = "Grok",
            lastVisited = 0L,
            faviconCacheKey = null,
            visitCount = 0
        ),
        RecentBrowserPage(
            url = "https://gemini.google.com",
            title = "Gemini",
            lastVisited = 0L,
            faviconCacheKey = null,
            visitCount = 0
        ),
        RecentBrowserPage(
            url = "https://console.cloud.google.com/vertex-ai",
            title = "Vertex AI",
            lastVisited = 0L,
            faviconCacheKey = null,
            visitCount = 0
        ),
        RecentBrowserPage(
            url = "https://github.com",
            title = "GitHub",
            lastVisited = 0L,
            faviconCacheKey = null,
            visitCount = 0
        ),
        RecentBrowserPage(
            url = "https://stackoverflow.com",
            title = "Stack Overflow",
            lastVisited = 0L,
            faviconCacheKey = null,
            visitCount = 0
        ),
        RecentBrowserPage(
            url = "https://developer.mozilla.org",
            title = "MDN Web Docs",
            lastVisited = 0L,
            faviconCacheKey = null,
            visitCount = 0
        ),
        RecentBrowserPage(
            url = "https://docs.github.com",
            title = "GitHub Docs",
            lastVisited = 0L,
            faviconCacheKey = null,
            visitCount = 0
        ),
        RecentBrowserPage(
            url = "https://npmjs.com",
            title = "npm",
            lastVisited = 0L,
            faviconCacheKey = null,
            visitCount = 0
        ),
        RecentBrowserPage(
            url = "https://crates.io",
            title = "Crates.io",
            lastVisited = 0L,
            faviconCacheKey = null,
            visitCount = 0
        ),
        RecentBrowserPage(
            url = "https://docs.python.org",
            title = "Python Docs",
            lastVisited = 0L,
            faviconCacheKey = null,
            visitCount = 0
        ),
        RecentBrowserPage(
            url = "https://golang.org",
            title = "Go",
            lastVisited = 0L,
            faviconCacheKey = null,
            visitCount = 0
        )
    )

    init {
        scope.launch {
            loadAsync()
        }
    }

    /**
     * Load recent pages from disk asynchronously.
     * If no data exists, bootstraps from existing browser history.
     */
    private suspend fun loadAsync() = withContext(Dispatchers.IO) {
        try {
            settingsFile.parentFile?.mkdirs()

            if (settingsFile.exists()) {
                val content = settingsFile.readText()
                val data = json.decodeFromString<RecentBrowserPagesData>(content)
                _recentPages.value = data.pages
                logger.debug(LogCategory.SYSTEM, "Loaded recent pages", mapOf("count" to data.pages.size))
            } else {
                // Bootstrap from existing browser history if available
                bootstrapFromBrowserHistory()
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Error loading recent pages", error = e)
            // Try to bootstrap even on error
            bootstrapFromBrowserHistory()
        }
    }

    /**
     * Bootstrap recent pages from existing browser history file.
     * This provides initial data when no recent pages have been recorded yet.
     */
    private suspend fun bootstrapFromBrowserHistory() = withContext(Dispatchers.IO) {
        try {
            val browserHistoryFile = BossDirectories.resolve("browser-history.json")
            if (!browserHistoryFile.exists()) return@withContext

            val content = browserHistoryFile.readText()
            if (content.isEmpty()) return@withContext

            // Parse browser history entries
            val entries = json.decodeFromString<List<BrowserHistoryEntry>>(content)

            // Convert to RecentBrowserPage, sorted by lastVisited, take top MAX_PAGES
            val recentPages = entries
                .sortedByDescending { it.lastVisited }
                .take(MAX_PAGES)
                .map { entry ->
                    RecentBrowserPage(
                        url = entry.url,
                        title = entry.title,
                        lastVisited = entry.lastVisited,
                        faviconCacheKey = null, // Will be populated on next visit
                        visitCount = entry.visitCount
                    )
                }

            if (recentPages.isNotEmpty()) {
                _recentPages.value = recentPages
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
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            saveImmediately()
        }
    }

    /**
     * Immediately save recent pages to disk (bypasses debounce).
     */
    private suspend fun saveImmediately() = withContext(Dispatchers.IO) {
        try {
            settingsFile.parentFile?.mkdirs()
            val data = RecentBrowserPagesData(pages = _recentPages.value)
            val content = json.encodeToString(RecentBrowserPagesData.serializer(), data)
            settingsFile.writeText(content)
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
    fun recordPageVisit(url: String, title: String, faviconCacheKey: String? = null) {
        // Skip internal URLs and empty titles
        if (url.isBlank() || title.isBlank()) return
        if (url.startsWith("about:") || url.startsWith("chrome:") || url.startsWith("data:")) return

        scope.launch {
            val currentPages = _recentPages.value.toMutableList()
            val existingIndex = currentPages.indexOfFirst { it.url == url }

            val newPage = if (existingIndex >= 0) {
                // Update existing entry
                val existing = currentPages.removeAt(existingIndex)
                existing.copy(
                    title = title,
                    lastVisited = System.currentTimeMillis(),
                    faviconCacheKey = faviconCacheKey ?: existing.faviconCacheKey,
                    visitCount = existing.visitCount + 1
                )
            } else {
                // Create new entry
                RecentBrowserPage(
                    url = url,
                    title = title,
                    lastVisited = System.currentTimeMillis(),
                    faviconCacheKey = faviconCacheKey,
                    visitCount = 1
                )
            }

            // Add to front (most recent)
            currentPages.add(0, newPage)

            // Trim to max size
            _recentPages.value = currentPages.take(MAX_PAGES)
            scheduleSave()
        }
    }

    /**
     * Remove a specific page from recent history.
     */
    fun removePage(url: String) {
        scope.launch {
            _recentPages.value = _recentPages.value.filter { it.url != url }
            scheduleSave()
        }
    }

    /**
     * Clear all recent pages.
     */
    fun clearAll() {
        scope.launch {
            _recentPages.value = emptyList()
            scheduleSave()
        }
    }

    /**
     * Get the domain from a URL for display purposes.
     */
    fun getDomain(url: String): String {
        return try {
            val withoutProtocol = url.removePrefix("https://").removePrefix("http://")
            withoutProtocol.substringBefore('/').substringBefore('?')
        } catch (e: Exception) {
            url
        }
    }

    /**
     * Get suggestions combining recent pages with popular dev sites.
     * Uses hybrid ranking: visit count weighted heavily + recency decay.
     * Popular dev sites fill remaining slots if history has fewer entries.
     */
    fun getSuggestions(limit: Int = 8): List<RecentBrowserPage> {
        val recent = _recentPages.value
        val recentUrls = recent.map { it.url }.toSet()
        val now = System.currentTimeMillis()

        // Hybrid ranking: combine visit count with recency
        // - visitCount weighted heavily (multiply by 1000)
        // - recency normalized to hours for reasonable decay
        val rankedRecent = recent.sortedByDescending { page ->
            val hoursAgo = (now - page.lastVisited) / (1000.0 * 60 * 60)
            val recencyScore = maxOf(0.0, 100 - hoursAgo) // Decays over ~4 days
            (page.visitCount * 1000.0) + recencyScore
        }

        val suggestions = mutableListOf<RecentBrowserPage>()
        suggestions.addAll(rankedRecent.take(limit))

        // Fill remaining slots with popular sites not in history
        if (suggestions.size < limit) {
            val popular = POPULAR_DEV_SITES.filter { !recentUrls.contains(it.url) }
            suggestions.addAll(popular.take(limit - suggestions.size))
        }

        return suggestions.take(limit)
    }
}
