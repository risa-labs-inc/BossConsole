package ai.rever.boss.plugin.browser

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
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
    val lastVisited: Long = System.currentTimeMillis()
)

object UrlHistoryManager {
    private val logger = BossLogger.forComponent("UrlHistoryManager")
    private val historyFile = BossDirectories.resolve("browser-history.json")
    private val history = ConcurrentHashMap<String, UrlHistoryEntry>()
    private val json = Json { 
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
    
    suspend fun saveHistory() = withContext(Dispatchers.IO) {
        try {
            historyFile.parentFile?.mkdirs()
            val entries = history.values.toList()
                .sortedByDescending { it.visitCount * 1000 + (it.lastVisited / 1000000) }
                .take(1000) // Keep only top 1000 entries
            historyFile.writeText(json.encodeToString(entries))
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Failed to save browser history", error = e)
        }
    }
    
    fun addUrl(url: String, title: String) {
        if (url.isBlank() || url == "about:blank" || url.startsWith("data:")) return
        
        try {
            val domain = java.net.URL(url).host.lowercase()
            val existing = history[url]
            
            history[url] = if (existing != null) {
                existing.copy(
                    title = title.ifBlank { existing.title },
                    visitCount = existing.visitCount + 1,
                    lastVisited = System.currentTimeMillis()
                )
            } else {
                UrlHistoryEntry(
                    url = url,
                    title = title,
                    domain = domain,
                    visitCount = 1,
                    lastVisited = System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            // Invalid URL - not worth recording in history
            logger.debug(
                LogCategory.BROWSER,
                "Ignoring history entry with unparsable URL",
                mapOf("error" to e.toString()),
            )
        }
    }
    
    fun getSuggestions(query: String, limit: Int = 10): List<UrlHistoryEntry> {
        if (query.isBlank()) return emptyList()
        
        val lowerQuery = query.lowercase()
        
        return history.values
            .filter { entry ->
                entry.domain.contains(lowerQuery) ||
                entry.url.contains(lowerQuery) ||
                entry.title.lowercase().contains(lowerQuery)
            }
            .sortedWith(compareBy(
                // Prioritize domain starts with query
                { !it.domain.startsWith(lowerQuery) },
                // Then URL starts with query
                { !it.url.removePrefix("https://").removePrefix("http://").startsWith(lowerQuery) },
                // Then by visit count and recency
                { -(it.visitCount * 1000 + (it.lastVisited / 1000000)) }
            ))
            .take(limit)
    }

    fun deleteUrl(url: String) {
        history.remove(url)
    }
}
