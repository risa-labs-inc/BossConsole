package ai.rever.boss.components.dialogs

import ai.rever.boss.plugin.browser.UrlHistoryManager

actual object UrlHistoryProvider {
    actual fun getSuggestions(
        query: String,
        limit: Int,
    ): List<UrlSuggestion> {
        // Decided before the lookup, because it decides how many rows history may fill. Asking
        // for `limit - 1` unconditionally cost a row on exactly the URL-shaped queries this
        // matching change is for: no search row is appended for them, so the dropdown showed
        // nine rows where ten were asked for.
        val offersSearch = !query.contains(".") && !query.startsWith("http")
        val historySuggestions =
            UrlHistoryManager
                // A negative count is floored inside `rankMatches`, which is the function
                // `take` would have thrown from.
                .getSuggestions(query, if (offersSearch) limit - 1 else limit)
                .map { entry ->
                    UrlSuggestion(
                        url = entry.url,
                        title = entry.title,
                        isSearchSuggestion = false,
                    )
                }

        // Offer a web search when the query doesn't look like an address.
        //
        // APPENDED, not inserted at the top. History is ranked and the best match is the row
        // the user is most likely to want - putting the search row above it made every
        // suggestion list open with the one row that is not a suggestion, and pushed the
        // match that inline completion has already filled into the field down to second
        // place. Chrome keeps its search row under the history it found, for the same reason.
        val suggestions = historySuggestions.toMutableList()
        if (offersSearch) {
            suggestions.add(
                UrlSuggestion(
                    // The same encoder `processUrlInput` uses, so this row and Enter on the
                    // same text search for the same thing. A bare space-to-plus replace let
                    // `&`, `#` and `%` through verbatim.
                    url = "https://www.google.com/search?q=${encodeUrlParameter(query)}",
                    title = "Search Google for \"$query\"",
                    isSearchSuggestion = true,
                ),
            )
        }

        // Floored for the same reason: this `take` is the second one a negative limit
        // reaches, and `getSuggestions` is an `expect` declaration with a defaulted limit,
        // so a caller that computes one should get an empty list rather than a crash.
        return suggestions.take(limit.coerceAtLeast(0))
    }

    actual fun deleteUrl(url: String) {
        UrlHistoryManager.deleteUrl(url)
    }
}
