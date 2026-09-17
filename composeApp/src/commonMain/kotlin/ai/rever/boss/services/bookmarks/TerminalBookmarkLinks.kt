package ai.rever.boss.services.bookmarks

import androidx.compose.runtime.mutableStateMapOf

/** Explicit session provenance, never inferred from a terminal's directory or title. Use on the UI thread. */
internal object TerminalBookmarkLinks {
    private val links = mutableStateMapOf<String, String>()

    fun bind(
        tabId: String,
        bookmarkId: String,
    ) {
        links[tabId] = bookmarkId
    }

    fun find(tabId: String): String? = links[tabId]

    fun unbind(tabId: String) {
        links.remove(tabId)
    }
}
