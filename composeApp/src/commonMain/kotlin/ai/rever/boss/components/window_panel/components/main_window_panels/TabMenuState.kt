package ai.rever.boss.components.window_panel.components.main_window_panels

import ai.rever.boss.components.bars.horizontal.StatusMessageManager
import ai.rever.boss.components.bookmarks.Bookmark
import ai.rever.boss.components.dialogs.BookmarkDeleteDialog
import ai.rever.boss.components.dialogs.BookmarkEditorDialog
import ai.rever.boss.components.dialogs.bookmarkProviderCall
import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.components.window_panel.SplitOrientation
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import ai.rever.boss.plugin.tab.jupyter.JupyterTabInfo
import ai.rever.boss.project.DefaultWorkingDirectory
import ai.rever.boss.services.bookmarks.BookmarkAPIAccess
import ai.rever.boss.services.bookmarks.TerminalBookmarkLinks
import ai.rever.boss.services.bookmarks.bookmarkSaveProblem
import ai.rever.boss.services.bookmarks.rememberBookmarkCollections
import ai.rever.boss.services.bookmarks.rememberBookmarkLibrary
import ai.rever.boss.utils.revealInFileManager
import ai.rever.boss.utils.revealInFileManagerLabel
import ai.rever.boss.window.LocalWindowProjectState
import ai.rever.boss.window.WindowOperations
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Splitscreen
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import kotlinx.coroutines.launch
import java.lang.reflect.Method
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * The `getFilePath` getter for a tab class, resolved once per class.
 *
 * These menus are built during composition for every visible row, and the bar recomposes on
 * every terminal output line - so the previous `getMethod(...)` inside a `runCatching` threw and
 * caught a `NoSuchMethodException`, stack trace filled in, for every browser and terminal tab on
 * screen, every time. The vertical bar made that worse by showing many more rows at once, across
 * every pane.
 *
 * A miss is cached as much as a hit, which is the point: the tab types that do NOT have the
 * getter are the common case and the expensive one. Keyed by [Class], so a plugin classloader
 * being swapped gives its new classes new entries rather than stale methods.
 */
private val filePathGetters = ConcurrentHashMap<Class<*>, Optional<Method>>()

private fun filePathGetter(type: Class<*>): Method? =
    filePathGetters
        .computeIfAbsent(type) {
            Optional.ofNullable(runCatching { it.getMethod("getFilePath") }.getOrNull())
        }.orElse(null)

/**
 * A panel's per-tab right-click menu, and the dialogs it opens.
 *
 * Hoisted out of `rememberTabBarState` because more than one surface shows a panel's tabs and
 * every one of them owes the same menu. The sidebar's labelled rows have it, the collapsed rail's
 * favicons have it, and the favicon strip across the top of each pane needs it too - that strip
 * exists so a pane's tabs can be reached without going to the sidebar, and a strip you cannot
 * right-click sends you back there for exactly the actions it was meant to save the trip for.
 *
 * The panel that draws that strip has no [TabBarState] to borrow from: in LEFT position the
 * window owns the one bar and the panel deliberately builds none. Copying the menu there would
 * have been ~150 lines of pin, split, bookmark and close actions kept in step by hand.
 *
 * The dialogs travel WITH the items rather than being left behind, because two of the menu's
 * entries do nothing on their own - they raise a dialog - and a caller that took the items and
 * forgot the dialogs would get a menu whose bookmark actions silently did nothing.
 */
@Stable
class TabMenuState internal constructor(
    /** Menu for the tab at this index. The index is the tab's own, not a row position. */
    val items: (Int, TabInfo) -> List<ContextMenuItem>,
    /** Must be mounted wherever [items] is used. */
    val dialogs: @Composable () -> Unit,
    /** Open the bookmark dialog for a tab directly, without going through the menu. */
    val bookmarkTab: (TabInfo) -> Unit,
    /**
     * Whether one of [dialogs] is on screen right now.
     *
     * A lambda rather than a `Boolean` so the caller's read happens in ITS composition and
     * subscribes to the snapshot state, instead of freezing whatever was true when this holder
     * was built. The hover-revealed drawer needs it: that drawer unmounts its whole content when
     * the pointer leaves, dialogs included, so it has to know a dialog it raised is still open.
     */
    val anyDialogOpen: () -> Boolean,
    val editBookmark: (Bookmark) -> Unit = {},
    val deleteBookmark: (Bookmark) -> Unit = {},
)

/**
 * Build the menu for one panel's tabs.
 *
 * @param vertical only changes WORDING - "Close Tabs Below" rather than "to the Right". A menu
 *   that lies about direction is worse than one that omits it.
 */
@Composable
fun BossTabsComponent.rememberTabMenuState(
    splitViewState: SplitViewState? = null,
    currentPanelId: String? = null,
    focusRequester: FocusRequester? = null,
    vertical: Boolean = false,
): TabMenuState {
    val tabsState = tabsState.subscribeAsState()
    val selectedProject =
        LocalWindowProjectState.current
            ?.selectedProject
            ?.collectAsState()
            ?.value
    val defaultDirectory = selectedProject?.path?.takeIf { it.isNotBlank() } ?: DefaultWorkingDirectory.nominalPath()

    var showBookmarkDialog by remember { mutableStateOf(false) }
    var tabToBookmark by remember { mutableStateOf<TabInfo?>(null) }

    var bookmarkToEdit by remember { mutableStateOf<Bookmark?>(null) }
    var deleteTarget by remember { mutableStateOf<Bookmark?>(null) }
    var preferFavorite by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()
    val library = rememberBookmarkLibrary()
    val libraryState = library?.state?.collectAsState()?.value

    // Observe collections for reactive context menu updates (gracefully handles missing plugin)
    val collections = rememberBookmarkCollections()

    // Wording that follows the axis: "to the Right" is a lie in a column, and a menu that lies
    // about direction is worse than one that omits it. BossTerm renames its own Move Tab items
    // the same way, for the same reason.
    val closeAfterLabel = if (vertical) "Close Tabs Below" else "Close Tabs to the Right"
    val closeBeforeLabel = if (vertical) "Close Tabs Above" else "Close Tabs to the Left"
    val closeAfterIcon = if (vertical) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.ChevronRight
    val closeBeforeIcon = if (vertical) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.ChevronLeft

    val tabMenuItems: (Int, TabInfo) -> List<ContextMenuItem> = { index, config ->
        val totalTabs = tabsState.value.tabs.size
        buildList {
            // NOTE: Do NOT call trackTabInteraction/setActivePanel here.
            // buildList runs during composition (every tab-bar
            // recomposition, e.g. on every terminal output line), so
            // doing it here flips the active panel away from whichever
            // split the user is actually in - stealing focus back to the
            // output-producing panel. Panel activation on right-click is
            // already handled by the panel's pointerInput press handler;
            // left-click activation by the tab onClick above.

            // Pin / Unpin. First in the menu because it is the one action here that changes
            // where the tab lives rather than what happens to it, and because it is the only way
            // to discover pinning - the sidebar shows no Pinned section until something is in it.
            //
            // Offered in BOTH orientations even though only the vertical bar draws sections: the
            // ordering invariant is the model's, not the sidebar's, so pinning from the top strip
            // still moves the tab to the front and still survives restart.
            if (isPinned(index)) {
                add(
                    ContextMenuItem("Unpin Tab", Icons.Outlined.PushPin, onClick = { unpinTab(index) }),
                )
            } else {
                add(
                    ContextMenuItem("Pin Tab", Icons.Outlined.PushPin, onClick = { pinTab(index) }),
                )
            }
            add(ContextMenuItem(isDivider = true))

            // Split operations (if split state is available)
            if (splitViewState != null && currentPanelId != null) {
                add(
                    ContextMenuItem("Split Right", Icons.Outlined.ViewColumn, onClick = {
                        splitViewState.splitPanel(
                            panelId = currentPanelId,
                            orientation = ai.rever.boss.components.window_panel.SplitOrientation.VERTICAL,
                            tabToMove = config,
                        )
                    }),
                )
                add(
                    ContextMenuItem("Split Down", Icons.Outlined.Splitscreen, onClick = {
                        splitViewState.splitPanel(
                            panelId = currentPanelId,
                            orientation = ai.rever.boss.components.window_panel.SplitOrientation.HORIZONTAL,
                            tabToMove = config,
                        )
                    }),
                )
                add(ContextMenuItem(isDivider = true))
            }

            // Reveal the tab's backing file in the OS file manager (file-backed tabs).
            // Host tab types expose filePath directly. Dynamic plugin tabs (e.g. the
            // editor-tab plugin's EditorTabData) live in a plugin classloader we can't
            // reference by type, so fall back to reading a `filePath` getter reflectively
            // - the same duck-typing the editor-tab plugin uses for host tab types.
            // The reflected value is assumed absolute: revealInFileManager resolves via
            // File(path).absolutePath, so a relative path would resolve against the CWD.
            val revealPath =
                when (val tab = config) {
                    is EditorTabInfo -> {
                        tab.filePath
                    }

                    is JupyterTabInfo -> {
                        tab.filePath
                    }

                    else -> {
                        filePathGetter(tab.javaClass)?.let { getter ->
                            runCatching { getter.invoke(tab) as? String }.getOrNull()
                        }
                    }
                }?.takeIf { it.isNotBlank() }
            if (revealPath != null) {
                add(
                    ContextMenuItem(revealInFileManagerLabel(), Icons.Outlined.FolderOpen, onClick = {
                        revealInFileManager(revealPath)
                    }),
                )
                add(ContextMenuItem(isDivider = true))
            }

            // Bookmark current tab
            // Deliberate bare snapshot read: subscribes this scope to
            // recomposition on bookmark-collection changes.
            @Suppress("UNUSED_EXPRESSION")
            collections

            val tabConfig = convertTabInfoToTabConfig(config, defaultDirectory)
            val existingIds = BookmarkAPIAccess.findBookmarkForTab(tabConfig)
            val matchedId =
                if (tabConfig.type == "terminal") {
                    TerminalBookmarkLinks.find(config.id)
                } else {
                    existingIds?.second
                }
            val existingBookmark =
                libraryState?.collections?.flatMap { it.bookmarks }?.find { it.id == matchedId }
            val problem = bookmarkSaveProblem(tabConfig)
            val available = library != null && libraryState?.ready == true
            val isFavorite = existingBookmark?.id in libraryState?.favoriteBookmarkIds.orEmpty()
            if (available && problem == null) {
                add(
                    ContextMenuItem(
                        if (existingBookmark == null) "Save Bookmark…" else "Remove Bookmark…",
                        if (existingBookmark == null) Icons.Outlined.BookmarkBorder else Icons.Outlined.BookmarkRemove,
                        onClick = {
                            if (existingBookmark != null) {
                                deleteTarget = existingBookmark
                            } else {
                                bookmarkToEdit = null
                                tabToBookmark = config
                                preferFavorite = false
                                showBookmarkDialog = true
                            }
                        },
                    ),
                )
            }
            add(
                ContextMenuItem(
                    when {
                        !available -> "Bookmarks unavailable - update or enable Bookmarks in Tools"
                        problem != null -> problem
                        isFavorite -> "Remove from Favorites"
                        else -> "Add to Favorites"
                    },
                    Icons.Outlined.Star,
                    enabled = available && problem == null,
                    onClick = {
                        if (existingBookmark != null && library != null) {
                            scope.launch {
                                updateTabFavorite(library, existingBookmark.id, !isFavorite)
                            }
                        } else {
                            bookmarkToEdit = null
                            tabToBookmark = config
                            preferFavorite = true
                            showBookmarkDialog = true
                        }
                    },
                ),
            )

            add(ContextMenuItem(isDivider = true))

            // Open in New Window (if multi-window is supported)
            if (ai.rever.boss.window.WindowOperations
                    .isMultiWindowSupported()
            ) {
                add(
                    ContextMenuItem("Open in New Window", Icons.AutoMirrored.Outlined.OpenInNew, onClick = {
                        ai.rever.boss.window.WindowOperations
                            .openTabInNewWindow(config)
                        // Remove tab from current window after opening in new window
                        removeTab(index)
                        // Request focus back to the main panel
                        focusRequester?.requestFocus()
                    }),
                )
                add(ContextMenuItem(isDivider = true))
            }

            // Close current tab
            add(
                ContextMenuItem("Close Tab", Icons.Outlined.Close, onClick = {
                    removeTab(index)
                    // Request focus back to the main panel
                    focusRequester?.requestFocus()
                }),
            )

            // Close other tabs (only show if there are other tabs)
            if (totalTabs > 1) {
                add(
                    ContextMenuItem("Close Other Tabs", Icons.Outlined.Clear, onClick = {
                        closeOtherTabs(index)
                        // Request focus back to the main panel
                        focusRequester?.requestFocus()
                    }),
                )
            }

            // Close tabs to the right (only show if there are tabs to the right)
            if (index < totalTabs - 1) {
                add(
                    ContextMenuItem(closeAfterLabel, closeAfterIcon, onClick = {
                        closeTabsToRight(index)
                        // Request focus back to the main panel
                        focusRequester?.requestFocus()
                    }),
                )
            }

            // Close tabs to the left (only show if there are tabs to the left)
            if (index > 0) {
                add(
                    ContextMenuItem(closeBeforeLabel, closeBeforeIcon, onClick = {
                        closeTabsToLeft(index)
                        // Request focus back to the main panel
                        focusRequester?.requestFocus()
                    }),
                )
            }
        }
    }

    return TabMenuState(
        items = tabMenuItems,
        anyDialogOpen = { showBookmarkDialog || deleteTarget != null },
        bookmarkTab = { tab ->
            tabToBookmark = tab
            bookmarkToEdit = null
            preferFavorite = true
            showBookmarkDialog = true
        },
        editBookmark = { bookmark ->
            tabToBookmark = null
            bookmarkToEdit = bookmark
            preferFavorite = null
            showBookmarkDialog = true
        },
        deleteBookmark = { deleteTarget = it },
        dialogs = {
            val editedConfig =
                bookmarkToEdit?.tabConfig ?: tabToBookmark?.let { convertTabInfoToTabConfig(it, defaultDirectory) }
            if (showBookmarkDialog && editedConfig != null) {
                if (library != null) {
                    BookmarkEditorDialog(
                        provider = library,
                        config = editedConfig,
                        existing = bookmarkToEdit,
                        preferFavorite = preferFavorite,
                        onSaved = { bookmarkId ->
                            tabToBookmark?.takeIf { editedConfig.type == "terminal" }?.let {
                                TerminalBookmarkLinks.bind(it.id, bookmarkId)
                            }
                        },
                        onDismiss = {
                            showBookmarkDialog = false
                            tabToBookmark = null
                            bookmarkToEdit = null
                        },
                    )
                } else {
                    androidx.compose.material.AlertDialog(
                        onDismissRequest = { showBookmarkDialog = false },
                        title = { androidx.compose.material.Text("Bookmarks unavailable") },
                        text = {
                            androidx.compose.material.Text(
                                "Update or enable Bookmarks in Tools, then try again.",
                            )
                        },
                        confirmButton = {
                            androidx.compose.material.TextButton(onClick = { showBookmarkDialog = false }) {
                                androidx.compose.material.Text("Close")
                            }
                        },
                    )
                }
            }
            deleteTarget?.let { target ->
                library?.let { provider ->
                    BookmarkDeleteDialog(provider, target, onDismiss = { deleteTarget = null })
                }
            }
        },
    )
}

private suspend fun updateTabFavorite(
    library: ai.rever.boss.plugin.bookmark.BookmarkLibraryProvider,
    bookmarkId: String,
    favorite: Boolean,
) {
    bookmarkProviderCall(
        action = {
            val result = library.setFavorite(bookmarkId, favorite, library.state.value.revision)
            val successMessage = if (favorite) "Added to Favorites" else "Removed from Favorites"
            StatusMessageManager.showMessage(
                if (result.success) successMessage else result.message ?: "Could not update Favorites",
            )
        },
        onError = { StatusMessageManager.showMessage(it) },
    )
}
