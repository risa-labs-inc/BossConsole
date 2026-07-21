package ai.rever.boss.components.dialogs

// iOS implementation - no history for now
actual object UrlHistoryProvider {
    actual fun getSuggestions(query: String, limit: Int): List<UrlSuggestion> {
        // For iOS, just return a search suggestion
        if (query.isNotEmpty() && !query.contains(".") && !query.startsWith("http")) {
            return listOf(
                UrlSuggestion(
                    url = "https://www.google.com/search?q=${query.replace(" ", "+")}",
                    title = "Search Google for \"$query\"",
                    isSearchSuggestion = true
                )
            )
        }
        return emptyList()
    }
    
    actual fun deleteUrl(url: String) {
        // No-op for iOS
    }
}
