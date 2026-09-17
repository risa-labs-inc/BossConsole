package ai.rever.boss.components.dialogs

import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.bookmark.BookmarkLibraryProvider
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Deletion is distinct from unfavoriting and has a durable Undo token. */
@Composable
internal fun BookmarkDeleteDialog(
    provider: BookmarkLibraryProvider,
    bookmark: Bookmark,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val confirmedRevision = remember(bookmark.id) { provider.state.value.revision }
    var undoToken by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val mutate: (Boolean) -> Unit = { undo ->
        if (!busy) {
            busy = true
            error = null
            scope.launch {
                try {
                    bookmarkProviderCall({
                        val revision = provider.state.value.revision
                        val result =
                            if (undo) {
                                provider.undo(requireNotNull(undoToken), revision)
                            } else {
                                provider.deleteBookmark(bookmark.id, confirmedRevision)
                            }
                        if (result.success) {
                            if (undo) onDismiss() else undoToken = result.undoToken
                        } else {
                            error = result.message ?: "Could not update bookmark."
                        }
                    }, { error = it })
                } finally {
                    busy = false
                }
            }
        }
    }
    BossDialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(color = BossTheme.colors.panel) {
            Column(Modifier.widthIn(max = 440.dp).padding(20.dp)) {
                Text(if (undoToken == null) "Delete bookmark?" else "Bookmark deleted")
                Spacer(Modifier.height(12.dp))
                Text(
                    error ?: if (undoToken == null) {
                        "Delete “${bookmark.tabConfig.title}” from saved bookmarks? " +
                            "Open tabs will stay open."
                    } else {
                        "You can restore “${bookmark.tabConfig.title}” with Undo."
                    },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = !busy, onClick = onDismiss) {
                        Text(if (undoToken == null) "Cancel" else "Done")
                    }
                    TextButton(enabled = !busy, onClick = { mutate(undoToken != null) }) {
                        Text(if (undoToken == null) "Delete bookmark" else "Undo")
                    }
                }
            }
        }
    }
}
