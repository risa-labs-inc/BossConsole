package ai.rever.boss.components.dialogs

import ai.rever.boss.search.GlobalSearchService
import ai.rever.boss.search.IndexedFile
import ai.rever.boss.search.SearchResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Runs searches with the lifetime and file snapshot of the dialog that owns them. */
@Composable
internal fun SpotlightSearchEffect(
    dialogState: SpotlightDialogState,
    windowId: String?,
    indexedFiles: List<IndexedFile>,
) {
    val query = dialogState.query
    LaunchedEffect(dialogState, query, windowId, indexedFiles) {
        runSpotlightSearch(dialogState, query) {
            GlobalSearchService.search(query, windowId, indexedFiles)
        }
    }
}

/** Invalidates obsolete work before clearing or debouncing a replacement query. */
internal suspend fun runSpotlightSearch(
    dialogState: SpotlightDialogState,
    query: String,
    search: suspend () -> List<SearchResult>,
) {
    val currentGen = ++dialogState.searchGeneration
    if (query.isBlank()) {
        dialogState.results = emptyList()
        dialogState.isSearching = false
        return
    }
    delay(50)
    if (currentGen == dialogState.searchGeneration) {
        dialogState.isSearching = true
    }
    try {
        val results = search()
        currentCoroutineContext().ensureActive()
        if (currentGen == dialogState.searchGeneration) {
            dialogState.results = results
        }
    } finally {
        if (currentGen == dialogState.searchGeneration) {
            dialogState.isSearching = false
        }
    }
}
