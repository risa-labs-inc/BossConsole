package ai.rever.boss.components.dialogs

import ai.rever.boss.components.overlays.resetOverlayFieldForTest
import ai.rever.boss.plugin.bookmark.Bookmark
import ai.rever.boss.plugin.bookmark.BookmarkCollection
import ai.rever.boss.plugin.bookmark.BookmarkLibraryProvider
import ai.rever.boss.plugin.bookmark.BookmarkLibraryState
import ai.rever.boss.plugin.bookmark.BookmarkMutationResult
import ai.rever.boss.plugin.bookmark.BookmarkSaveRequest
import ai.rever.boss.plugin.ui.BossOverlayHost
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.LocalHeavyweightOverlays
import ai.rever.boss.plugin.workspace.TabConfig
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BookmarkEditorDialogTest {
    @get:Rule val rule = createComposeRule()
    private val previousRenderer = BossOverlayHost.modalRenderer
    private val previousHeavyweight = BossOverlayHost.useHeavyweightOverlays
    private val config = TabConfig("terminal", "Original", workingDirectory = "/work", initialCommand = "pwd")
    private val provider = FakeLibrary()
    private var dismissed = 0
    private var savedId: String? = null

    @Before fun setup() {
        resetOverlayFieldForTest("modalRenderer")
        resetOverlayFieldForTest("useHeavyweightOverlays")
        BossOverlayHost.useHeavyweightOverlays = true
        BossOverlayHost.modalRenderer = { _, _, content -> content() }
    }

    @After fun cleanup() {
        resetOverlayFieldForTest("modalRenderer")
        resetOverlayFieldForTest("useHeavyweightOverlays")
        BossOverlayHost.modalRenderer = previousRenderer
        BossOverlayHost.useHeavyweightOverlays = previousHeavyweight
    }

    private fun showEditor(
        preferFavorite: Boolean? = null,
        existing: Bookmark? = null,
    ) {
        rule.setContent {
            CompositionLocalProvider(LocalHeavyweightOverlays provides true) {
                BossTheme {
                    BookmarkEditorDialog(
                        provider,
                        config,
                        preferFavorite = preferFavorite,
                        existing = existing,
                        onDismiss = { dismissed++ },
                        onSaved = { savedId = it },
                    )
                }
            }
        }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
    }

    @Test fun `successful save reports exact bookmark identity only after persistence`() {
        provider.saveResult = BookmarkMutationResult(false, message = "Disk full")
        showEditor(preferFavorite = true)
        rule.onNodeWithText("Add", substring = false).performClick()
        rule.waitForIdle()
        assertEquals(null, savedId)
        provider.saveResult = BookmarkMutationResult(true, bookmarkId = "saved-exact-id")
        rule.onNodeWithText("Add", substring = false).performClick()
        rule.waitForIdle()
        assertEquals("saved-exact-id", savedId)
        assertEquals(1, dismissed)
    }

    private fun field(label: String) = rule.onNode(hasSetTextAction() and hasText(label))

    @Test fun `plain bookmark save is compact and persists without favoriting`() {
        provider.saveResult = BookmarkMutationResult(true, bookmarkId = "plain-saved")
        showEditor(preferFavorite = false)
        rule.onNodeWithText("Save Bookmark").assertIsDisplayed()
        field("Startup folder (optional)").assertDoesNotExist()
        rule.onNodeWithText("New folder", substring = false).assertDoesNotExist()
        rule.onNode(isToggleable()).assertDoesNotExist()
        field("Name").performTextReplacement("Plain shortcut")
        rule.onNodeWithText("Save", substring = false).performClick()
        rule.waitForIdle()
        assertEquals(false, provider.saved.single().favorite)
        assertEquals("Plain shortcut", provider.saved.single().name)
        assertEquals("plain-saved", savedId)
        assertEquals(1, dismissed)
    }

    @Test fun `add favorite starts compact and retains failure for retry`() {
        provider.saveResult = BookmarkMutationResult(false, message = "Disk full")
        showEditor(preferFavorite = true)
        rule.onNodeWithText("Add to Favorites").assertIsDisplayed()
        field("Startup folder (optional)").assertDoesNotExist()
        rule.onNode(isToggleable()).assertDoesNotExist()
        field("Name").performTextReplacement("Daily work")
        rule.onNodeWithText("Add", substring = false).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Disk full").assertIsDisplayed()
        assertEquals(0, dismissed)
        assertEquals(true, provider.saved.single().favorite)
        assertEquals(
            "/work",
            provider.saved
                .single()
                .tabConfig.workingDirectory,
        )
        provider.saveResult = BookmarkMutationResult(true)
        rule.onNodeWithText("Add", substring = false).performClick()
        rule.waitForIdle()
        assertEquals(1, dismissed)
        assertEquals("Daily work", provider.saved.last().name)
    }

    @Test fun `favorite more options edits target without exposing favorite toggle`() {
        showEditor(preferFavorite = true)
        rule.onNodeWithText("Terminal shortcut · /work").assertExists()
        rule.onNodeWithText("More options").performClick()
        rule.onNodeWithText("Terminal shortcut · /work").assertDoesNotExist()
        rule.onNodeWithText("Terminal shortcut", substring = false).assertDoesNotExist()
        rule.onNodeWithText("Folder", substring = false).assertExists()
        field("Startup folder (optional)").performTextReplacement("/other")
        rule.onNode(isToggleable()).assertDoesNotExist()
        rule.onNodeWithText("Hide options").performClick()
        field("Startup folder (optional)").assertDoesNotExist()
        rule.onNodeWithText("Add", substring = false).performClick()
        rule.waitForIdle()
        assertEquals(
            "/other",
            provider.saved
                .single()
                .tabConfig.workingDirectory,
        )
        assertEquals(true, provider.saved.single().favorite)
    }

    @Test fun `failed persistence keeps edited input and allows retry`() {
        provider.saveResult = BookmarkMutationResult(false, message = "Disk full")
        showEditor()
        field("Name").performTextReplacement("Keep this draft")
        rule.onNodeWithText("Save", substring = false).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Disk full").assertIsDisplayed()
        rule.onNode(hasSetTextAction() and hasText("Keep this draft")).assertExists()
        assertEquals(0, dismissed)
        provider.saveResult = BookmarkMutationResult(true)
        rule.onNodeWithText("Save", substring = false).performClick()
        rule.waitForIdle()
        assertEquals(1, dismissed)
        assertEquals("Keep this draft", provider.saved.last().name)
    }

    @Test fun `existing bookmark retains full fields and favorite choice`() {
        provider.state.value = provider.state.value.copy(favoriteBookmarkIds = setOf("edit"))
        showEditor(preferFavorite = true, existing = Bookmark(id = "edit", workspaceName = "Work", tabConfig = config))
        field("Startup folder (optional)").assertIsDisplayed()
        field("Name").performTextReplacement("Build terminal")
        rule.onNode(isToggleable()).performClick()
        rule.onNodeWithText("Save", substring = false).performClick()
        rule.waitForIdle()
        assertEquals(1, dismissed)
        assertEquals("Build terminal", provider.saved.single().name)
        assertFalse(provider.saved.single().favorite)
        assertEquals(
            "pwd",
            provider.saved
                .single()
                .tabConfig.initialCommand,
        )
    }

    @Test fun `edit duplicate loads actual saved values before resaving`() {
        val existing =
            Bookmark(
                id = "old",
                workspaceName = "Work",
                tabConfig = config.copy(title = "Saved name", workingDirectory = "/saved", initialCommand = "ls"),
            )
        provider.state.value =
            provider.state.value.copy(
                collections = listOf(BookmarkCollection(id = "saved", name = "Saved", bookmarks = listOf(existing))),
                favoriteBookmarkIds = emptySet(),
            )
        provider.saveResult = BookmarkMutationResult(false, duplicateBookmarkId = "old", message = "Already saved")
        showEditor()
        field("Name").performTextReplacement("Unsaved draft name")
        rule.onNodeWithText("Save", substring = false).performClick()
        rule.onNodeWithText("Edit existing bookmark").performScrollTo().performClick()
        rule.onNode(hasSetTextAction() and hasText("Saved name")).assertExists()
        rule.onNode(hasSetTextAction() and hasText("/saved")).assertExists()
        rule.onNode(hasSetTextAction() and hasText("ls")).assertExists()
        provider.saveResult = BookmarkMutationResult(true)
        rule.onNodeWithText("Save", substring = false).performClick()
        rule.waitForIdle()
        assertEquals("old", provider.saved.last().bookmarkId)
        assertEquals("Saved name", provider.saved.last().name)
        assertFalse(provider.saved.last().favorite)
    }

    @Test fun `adding existing plain bookmark to favorites preserves intent after duplicate edit`() {
        prepareDuplicate(favorite = false)
        showEditor(preferFavorite = true)
        rule.onNodeWithText("Add", substring = false).performClick()
        rule.onNodeWithText("Edit existing bookmark").performScrollTo().performClick()
        rule.onNodeWithText("Edit Bookmark").assertIsDisplayed()
        provider.saveResult = BookmarkMutationResult(true, bookmarkId = "duplicate")
        rule.onNodeWithText("Save", substring = false).performClick()
        rule.waitForIdle()
        assertEquals("duplicate", provider.saved.last().bookmarkId)
        assertEquals(true, provider.saved.last().favorite)
        assertEquals("duplicate", savedId)
        assertEquals(1, dismissed)
    }

    @Test fun `plain save duplicate edit preserves existing favorite membership`() {
        prepareDuplicate(favorite = true)
        showEditor(preferFavorite = false)
        rule.onNodeWithText("Save", substring = false).performClick()
        rule.onNodeWithText("Edit existing bookmark").performScrollTo().performClick()
        provider.saveResult = BookmarkMutationResult(true, bookmarkId = "duplicate")
        rule.onNodeWithText("Save", substring = false).performClick()
        rule.waitForIdle()
        assertEquals(true, provider.saved.last().favorite)
        assertEquals("duplicate", provider.saved.last().bookmarkId)
        assertEquals("duplicate", savedId)
    }

    private fun prepareDuplicate(favorite: Boolean) {
        val bookmark = Bookmark(id = "duplicate", workspaceName = "Work", tabConfig = config)
        provider.state.value =
            provider.state.value.copy(
                collections = listOf(BookmarkCollection(id = "saved", name = "Saved", bookmarks = listOf(bookmark))),
                favoriteBookmarkIds = if (favorite) setOf(bookmark.id) else emptySet(),
            )
        provider.saveResult =
            BookmarkMutationResult(false, duplicateBookmarkId = bookmark.id, message = "Already saved")
    }

    @Test fun `only explicit unfiled identity is displayed as No folder`() {
        provider.state.value =
            provider.state.value.copy(
                collections =
                    listOf(
                        BookmarkCollection(id = "saved", name = "Unsorted"),
                        BookmarkCollection(id = "custom", name = "Unsorted"),
                    ),
                unfiledCollectionIds = setOf("saved"),
            )
        showEditor(preferFavorite = false)
        rule.onNodeWithText("More options").performClick()
        rule.onNodeWithText("No folder", substring = false).performScrollTo().performClick()
        rule.onNodeWithText("Unsorted", substring = false).assertIsDisplayed()
    }

    @Test fun `new bookmark chooses unfiled identity even when a custom folder is first`() {
        provider.state.value =
            provider.state.value.copy(
                collections =
                    listOf(
                        BookmarkCollection(id = "custom", name = "Work"),
                        BookmarkCollection(id = "unfiled", name = "Bookmarks"),
                    ),
                unfiledCollectionIds = setOf("unfiled"),
            )
        showEditor(preferFavorite = false)
        rule.onNodeWithText("More options").performClick()
        rule.onNodeWithText("No folder", substring = false).assertExists()
        rule.onNodeWithText("Save", substring = false).performClick()
        rule.waitForIdle()
        assertEquals("unfiled", provider.saved.single().collectionId)
    }

    @Test fun `collection creation failure retains input and can be cancelled`() {
        provider.failCollection = true
        showEditor()
        rule.onNodeWithText("More options").performClick()
        rule.onNodeWithText("New folder", substring = false).performScrollTo().performClick()
        field("New folder name").performTextReplacement("Research")
        rule.onNodeWithText("Create folder", substring = false).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Could not update bookmarks. Your changes are still here; try again.").assertExists()
        rule.onNode(hasSetTextAction() and hasText("Research")).assertExists()
        rule.onNodeWithText("Cancel new folder").performScrollTo().performClick()
        rule.onNodeWithText("New folder name").assertDoesNotExist()
        assertEquals(0, dismissed)
    }

    @Test fun `delete uses confirmed revision while Undo uses latest revision`() {
        provider.state.value = provider.state.value.copy(revision = 5)
        val bookmark = Bookmark(id = "delete-me", tabConfig = config, workspaceName = "Work")
        rule.setContent {
            CompositionLocalProvider(LocalHeavyweightOverlays provides true) {
                BookmarkDeleteDialog(provider, bookmark, onDismiss = { dismissed++ })
            }
        }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        provider.state.value = provider.state.value.copy(revision = 6)
        rule.onNodeWithText("Delete bookmark", substring = false).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Bookmark deleted").assertIsDisplayed()
        assertEquals(listOf("delete-me"), provider.deleted)
        assertEquals(listOf(5L), provider.deleteRevisions)
        assertEquals(0, dismissed)
        provider.state.value = provider.state.value.copy(revision = 7)
        rule.onNodeWithText("Undo", substring = false).performClick()
        rule.waitForIdle()
        assertEquals(listOf("undo"), provider.undone)
        assertEquals(listOf(7L), provider.undoRevisions)
        assertEquals(1, dismissed)
    }

    @Test fun `Enter on Cancel dismisses without saving`() {
        showEditor()
        rule
            .onNodeWithText("Cancel", substring = false)
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(1, dismissed)
        assertEquals(emptyList(), provider.saved)
    }

    @Test fun `replacing mounted terminal target resets form to the supplied browser`() {
        val target = mutableStateOf(config)
        rule.setContent {
            CompositionLocalProvider(LocalHeavyweightOverlays provides true) {
                BookmarkEditorDialog(provider, target.value, preferFavorite = false, onDismiss = {})
            }
        }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        field("Name").performTextReplacement("Old terminal draft")
        rule.runOnIdle {
            target.value = TabConfig("browser", "New website", url = "https://example.com")
        }
        rule.onNode(hasSetTextAction() and hasText("New website")).assertExists()
        rule.onNodeWithText("Website bookmark · https://example.com").assertExists()
        rule.onNode(hasSetTextAction() and hasText("Old terminal draft")).assertDoesNotExist()
        rule.onNodeWithText("Save", substring = false).performClick()
        rule.waitForIdle()
        val saved = provider.saved.single()
        assertEquals("browser", saved.tabConfig.type)
        assertEquals("https://example.com", saved.tabConfig.url)
        assertEquals("New website", saved.name)
        assertEquals(null, saved.tabConfig.workingDirectory)
        assertEquals(null, saved.tabConfig.initialCommand)
    }

    private class FakeLibrary : BookmarkLibraryProvider {
        override val state =
            MutableStateFlow(
                BookmarkLibraryState(
                    collections = listOf(BookmarkCollection(id = "saved", name = "Saved")),
                    ready = true,
                ),
            )
        var saveResult = BookmarkMutationResult(true)
        var failCollection = false
        val saved = mutableListOf<BookmarkSaveRequest>()
        val deleted = mutableListOf<String>()
        val deleteRevisions = mutableListOf<Long>()
        val undoRevisions = mutableListOf<Long>()
        val undone = mutableListOf<String>()

        override suspend fun saveBookmark(request: BookmarkSaveRequest): BookmarkMutationResult {
            saved += request
            return saveResult
        }

        override suspend fun createCollection(
            name: String,
            expectedRevision: Long,
        ): BookmarkMutationResult {
            if (failCollection) error("Disk unavailable")
            return BookmarkMutationResult(true, collectionId = "new")
        }

        override suspend fun setFavorite(
            bookmarkId: String,
            favorite: Boolean,
            expectedRevision: Long,
        ) = BookmarkMutationResult(true)

        override suspend fun moveBookmark(
            bookmarkId: String,
            collectionId: String,
            expectedRevision: Long,
        ) = BookmarkMutationResult(true)

        override suspend fun deleteBookmark(
            bookmarkId: String,
            expectedRevision: Long,
        ): BookmarkMutationResult {
            deleted += bookmarkId
            deleteRevisions += expectedRevision
            return BookmarkMutationResult(true, undoToken = "undo")
        }

        override suspend fun undo(
            token: String,
            expectedRevision: Long,
        ): BookmarkMutationResult {
            undone += token
            undoRevisions += expectedRevision
            return BookmarkMutationResult(true)
        }

        override suspend fun renameCollection(
            collectionId: String,
            name: String,
            expectedRevision: Long,
        ) = BookmarkMutationResult(true)

        override suspend fun deleteCollection(
            collectionId: String,
            moveToCollectionId: String?,
            expectedRevision: Long,
        ) = BookmarkMutationResult(true)

        override suspend fun reload() = Unit
    }

    @Test fun `diff and composer form targets preserve project and session semantics`() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val diff =
            BookmarkEditorState(
                provider,
                TabConfig("diff", "Diff", filePath = "before.txt", workingDirectory = "/project"),
                null,
                null,
                scope,
            )
        diff.target = "after.txt"
        assertEquals("after.txt", diff.edited.filePath)
        assertEquals("/project", diff.edited.workingDirectory)
        assertEquals(null, diff.problem)
        diff.target = "../other"
        assertEquals(false, diff.problem == null)
        val sessionConfig = TabConfig("composer", "Session", filePath = "session:one")
        val composer = BookmarkEditorState(provider, sessionConfig, null, null, scope)
        composer.target = "session:two"
        assertEquals("session:two", composer.edited.filePath)
        assertEquals(null, composer.problem)
    }
}
