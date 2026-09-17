package ai.rever.boss.search

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Data model representing a saved command palette search query.
 */
data class SearchQueryItem(
    val query: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
)

/**
 * Thread-safe registry tracking recent command search palette history and favorites (max capacity: 10).
 */
object CommandSearchHistoryRegistry {
    private const val MAX_CAPACITY = 10

    private val lock = Any()
    private val historyList = mutableListOf<SearchQueryItem>()

    private val _historyFlow = MutableStateFlow<List<SearchQueryItem>>(emptyList())
    val historyFlow: StateFlow<List<SearchQueryItem>> = _historyFlow.asStateFlow()

    /**
     * Records a search query into history. Deduplicates existing entries and moves them to top.
     */
    fun addQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return

        synchronized(lock) {
            val existingIndex = historyList.indexOfFirst { it.query.equals(trimmed, ignoreCase = true) }
            val existingFav = if (existingIndex != -1) historyList[existingIndex].isFavorite else false

            if (existingIndex != -1) {
                historyList.removeAt(existingIndex)
            }

            val item =
                SearchQueryItem(
                    query = trimmed,
                    timestamp = System.currentTimeMillis(),
                    isFavorite = existingFav,
                )
            historyList.add(0, item)

            while (historyList.size > MAX_CAPACITY) {
                // Preserve favorites if possible
                val removeIndex = historyList.indexOfLast { !it.isFavorite }
                if (removeIndex != -1) {
                    historyList.removeAt(removeIndex)
                } else {
                    historyList.removeAt(historyList.size - 1)
                }
            }

            _historyFlow.value = historyList.toList()
        }
    }

    /**
     * Toggles favorite status for a search query.
     */
    fun toggleFavorite(query: String): Boolean {
        val trimmed = query.trim()
        synchronized(lock) {
            val index = historyList.indexOfFirst { it.query.equals(trimmed, ignoreCase = true) }
            if (index != -1) {
                val current = historyList[index]
                val updated = current.copy(isFavorite = !current.isFavorite)
                historyList[index] = updated
                _historyFlow.value = historyList.toList()
                return updated.isFavorite
            }
            return false
        }
    }

    /**
     * Returns a snapshot list of recent search queries.
     */
    fun getRecentQueries(): List<SearchQueryItem> =
        synchronized(lock) {
            historyList.toList()
        }

    /**
     * Returns only favorite search queries.
     */
    fun getFavorites(): List<SearchQueryItem> =
        synchronized(lock) {
            historyList.filter { it.isFavorite }
        }

    /**
     * Clears all non-favorite search history.
     */
    fun clearNonFavorites() {
        synchronized(lock) {
            historyList.removeAll { !it.isFavorite }
            _historyFlow.value = historyList.toList()
        }
    }

    /**
     * Clears all search history including favorites.
     */
    fun clear() {
        synchronized(lock) {
            historyList.clear()
            _historyFlow.value = emptyList()
        }
    }
}
