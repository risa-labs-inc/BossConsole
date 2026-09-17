package ai.rever.boss.components.dialogs

import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.bookmark.BookmarkLibraryProvider
import ai.rever.boss.plugin.bookmark.BookmarkLibraryState
import ai.rever.boss.plugin.bookmark.BookmarkMutationResult
import ai.rever.boss.plugin.bookmark.BookmarkSaveRequest
import ai.rever.boss.plugin.workspace.TabConfig
import ai.rever.boss.services.bookmarks.bookmarkSaveProblem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class BookmarkEditorState(
    private val provider: BookmarkLibraryProvider,
    private val config: TabConfig,
    existing: Bookmark?,
    preferFavorite: Boolean?,
    private val scope: CoroutineScope,
) {
    var bookmarkId by mutableStateOf(existing?.id)
    var name by mutableStateOf(existing?.tabConfig?.title ?: config.title)
    var target by mutableStateOf(config.url ?: config.filePath ?: config.workingDirectory.orEmpty())
    var command by mutableStateOf(config.initialCommand.orEmpty())
    var collectionId by mutableStateOf(
        provider.state.value.collections
            .find { c -> c.bookmarks.any { it.id == existing?.id } }
            ?.id
            ?: provider.state.value.initialFolderId(),
    )
    var favorite by mutableStateOf(
        existing?.let { it.id in provider.state.value.favoriteBookmarkIds }
            ?: preferFavorite ?: provider.state.value.defaultFavorite,
    )
    var revision by mutableStateOf(provider.state.value.revision)
    var busy by mutableStateOf(false)
    var inputFocused by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var duplicateId by mutableStateOf<String?>(null)
    var creatingCollection by mutableStateOf(false)
    var collectionName by mutableStateOf("")
    private val workspaceName = existing?.workspaceName.orEmpty()
    private val addingToFavorites = preferFavorite == true && existing == null

    val dialogTitle: String get() =
        when {
            addingToFavorites && bookmarkId == null -> "Add to Favorites"
            bookmarkId == null -> "Save Bookmark"
            else -> "Edit Bookmark"
        }

    val edited: TabConfig get() =
        config.copy(
            title = name.trim(),
            url = if (config.type == "browser") target.trim() else config.url,
            filePath =
                when (config.type) {
                    "composer" -> target
                    "editor", "jupyter", "diff" -> target.trim()
                    else -> config.filePath
                },
            workingDirectory =
                if (config.type == "terminal") target.trim().ifBlank { null } else config.workingDirectory,
            initialCommand = if (config.type == "terminal") command.ifBlank { null } else config.initialCommand,
        )
    val problem: String? get() = bookmarkSaveProblem(edited)
    val canSave: Boolean get() =
        !busy && !creatingCollection && provider.state.value.ready &&
            name.isNotBlank() && collectionId.isNotBlank() && problem == null

    fun initializeLoadedLibrary() {
        if (collectionId.isBlank() && provider.state.value.ready) {
            collectionId = provider.state.value.initialFolderId()
            revision = provider.state.value.revision
        }
    }

    fun save(
        copy: Boolean,
        onSaved: () -> Unit,
    ) {
        if (!canSave) return
        perform {
            val result =
                provider.saveBookmark(
                    BookmarkSaveRequest(
                        collectionId = collectionId,
                        tabConfig = edited,
                        name = name.trim(),
                        favorite = favorite,
                        expectedRevision = revision,
                        bookmarkId = if (copy) null else bookmarkId,
                        allowCopy = copy,
                        workspaceName = workspaceName,
                    ),
                )
            if (result.success) {
                bookmarkId = result.bookmarkId ?: bookmarkId
                onSaved()
            } else {
                error = result.message ?: "Could not save bookmark. Your changes are still here."
                duplicateId = result.duplicateBookmarkId
            }
        }
    }

    fun createCollection() =
        perform {
            val result = provider.createCollection(collectionName.trim(), revision)
            if (result.success) {
                collectionId = result.collectionId ?: provider.state.value.collections
                    .lastOrNull()
                    ?.id
                    .orEmpty()
                revision = provider.state.value.revision
                creatingCollection = false
            } else {
                error = result.message ?: "Could not create collection."
            }
        }

    fun reload() =
        perform {
            provider.reload()
            revision = provider.state.value.revision
            error = provider.state.value.error
            initializeLoadedLibrary()
        }

    fun editDuplicate() {
        val snapshot = provider.state.value
        val owner = snapshot.collections.find { c -> c.bookmarks.any { it.id == duplicateId } }
        val saved = owner?.bookmarks?.find { it.id == duplicateId }
        if (saved == null) {
            error = "That bookmark is no longer available. Reload the library."
        } else {
            bookmarkId = saved.id
            name = saved.tabConfig.title
            target = saved.tabConfig.url ?: saved.tabConfig.filePath ?: saved.tabConfig.workingDirectory.orEmpty()
            command = saved.tabConfig.initialCommand.orEmpty()
            collectionId = owner.id
            favorite = addingToFavorites || saved.id in snapshot.favoriteBookmarkIds
            revision = snapshot.revision
            error = null
            duplicateId = null
        }
    }

    private fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                bookmarkProviderCall(action) { error = it }
            } finally {
                busy = false
            }
        }
    }
}

/** Plugin calls may throw arbitrary failures; keep form input and never swallow cancellation. */
@Suppress("TooGenericExceptionCaught")
internal suspend fun bookmarkProviderCall(
    action: suspend () -> Unit,
    onError: (String) -> Unit,
) {
    try {
        action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        onError("Could not update bookmarks. Your changes are still here; try again.")
    }
}

private fun BookmarkLibraryState.initialFolderId(): String =
    (collections.firstOrNull { it.id in unfiledCollectionIds } ?: collections.firstOrNull())?.id.orEmpty()
