package ai.rever.boss.components.dialogs

import ai.rever.boss.search.GlobalSearchService
import ai.rever.boss.search.IndexedFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay

/** Runs searches with the lifetime and file snapshot of the dialog that owns them. */
@Composable
internal fun SpotlightSearchEffect(
    dialogState: SpotlightDialogState,
    windowId: String?,
    indexedFiles: List<IndexedFile>,
) {
    // Debounced search as user types
    // 50ms debounce balances responsiveness with avoiding excessive searches while typing fast
    // A fresh dialog starts with an empty index. Re-run the current query when its scan
    // completes, and cancel work owned by a replaced session even if the query is unchanged.
    LaunchedEffect(dialogState, dialogState.query, windowId, indexedFiles) {
        if (dialogState.query.isBlank()) {
            dialogState.results = emptyList()
            return@LaunchedEffect
        }
        delay(50)
        
        val currentGen = ++dialogState.searchGeneration
        
        // This window, so the Tools rows come from the sidebar this dialog can actually open, and
        // so a signpost is offered only when its panel is present here - see SearchSources.
        dialogState.isSearching = true
        try {
            val results = GlobalSearchService.search(dialogState.query, windowId, indexedFiles)
            if (currentGen == dialogState.searchGeneration) {
                dialogState.results = results
            }
        } finally {
            if (currentGen == dialogState.searchGeneration) {
                dialogState.isSearching = false
            }
        }
    }
}
