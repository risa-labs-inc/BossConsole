package ai.rever.boss.components.dialogs

import ai.rever.boss.plugin.browser.UrlHistoryManager

actual object UrlHistoryProvider {
    actual fun getSuggestions(
        query: String,
        limit: Int,
    ): List<UrlSuggestion> {
        val historySuggestions =
            UrlHistoryManager
                .getSuggestions(query, limit - 1)
                .map { entry ->
                    UrlSuggestion(
                        url = entry.url,
                        title = entry.title,
                        isSearchSuggestion = false,
                    )
                }

        // Add a Google search suggestion if the query doesn't look like a URL
        val suggestions = historySuggestions.toMutableList()
        if (!query.contains(".") && !query.startsWith("http")) {
            suggestions.add(
                0,
                UrlSuggestion(
                    url = "https://www.google.com/search?q=${query.replace(" ", "+")}",
                    title = "Search Google for \"$query\"",
                    isSearchSuggestion = true,
                ),
            )
        }

        return suggestions.take(limit)
    }

    actual fun deleteUrl(url: String) {
        UrlHistoryManager.deleteUrl(url)
    }
}
