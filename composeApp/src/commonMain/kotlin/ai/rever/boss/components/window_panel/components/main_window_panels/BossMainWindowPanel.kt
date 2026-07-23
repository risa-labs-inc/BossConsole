package ai.rever.boss.components.window_panel.components.main_window_panels

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.keymap.KeymapSettingsManager
import ai.rever.boss.keymap.model.TabSwitchMode
import BossDarkAccent
import BossDarkBackground
import BossDarkBorder
import BossDarkSurface
import BossDarkTextSecondary
import ai.rever.boss.components.bars.ScrollbarConfig
import ai.rever.boss.components.bars.horizontal.HorizontalBar
import ai.rever.boss.components.bars.horizontal.HorizontalBarRow
import ai.rever.boss.components.bars.horizontalScrollWithScrollbar
import ai.rever.boss.components.buttons.BossTabButton
import ai.rever.boss.components.common.rememberFaviconLoader
import ai.rever.boss.components.model.ScrollDirection
import ai.rever.boss.components.model.TabDraggableComponent
import ai.rever.boss.components.model.TabDropResult
import ai.rever.boss.components.model.TabDropTarget
import ai.rever.boss.plugin.api.LocalIsPanelActive
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabEvent
import ai.rever.boss.plugin.api.TabEventType
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.components.plugin.providers.publishSystemEvent
import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabUpdateProvider
import ai.rever.boss.plugin.api.TabUpdateProviderFactory
import ai.rever.boss.plugin.sandbox.TabSandboxRegistry
import ai.rever.boss.plugin.sandbox.ui.PluginCrashRegistry
import ai.rever.boss.plugin.sandbox.ui.PluginErrorBoundary
import ai.rever.boss.components.bars.horizontal.StatusMessageManager
import ai.rever.boss.components.plugin.TabUpdateRegistry
import ai.rever.boss.components.tabs_navigation.TabsNavigation
import ai.rever.boss.components.bookmarks.Bookmark
import ai.rever.boss.components.bookmarks.WorkspacePanelTarget
import ai.rever.boss.services.bookmarks.BookmarkAPIAccess
import ai.rever.boss.services.bookmarks.rememberBookmarkCollections
import ai.rever.boss.components.dialogs.BookmarkDialog
import ai.rever.boss.components.dialogs.NewTabDialog
import ai.rever.boss.components.dialogs.RemoveBookmarkConfirmationDialog
import ai.rever.boss.components.dialogs.TabType
import ai.rever.boss.components.dividers.VDivider
import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.components.overlays.contextMenu
import ai.rever.boss.plugin.tab.codeeditor.CodeEditorTabType
import ai.rever.boss.icons.FileIcons
import ai.rever.boss.utils.extractFileName
import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import ai.rever.boss.plugin.tab.jupyter.JupyterTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.tab.fluck.FluckTabType
import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.components.workspaces.TabConfig
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.components.workspaces.PredefinedWorkspaces
import ai.rever.boss.components.workspaces.applyWorkspace
import ai.rever.boss.components.window_panel.SplitOrientation
import ai.rever.boss.components.dashboard.Dashboard
import ai.rever.boss.window.LocalWindowProjectState
import ai.rever.boss.window.Project
import ai.rever.boss.window.selectProjectInWindow
import ai.rever.boss.dashboard.SplitTemplate
import ai.rever.boss.dashboard.SplitTemplatesManager
import ai.rever.boss.run.RUNNER_TERMINAL_PREFIX
import ai.rever.boss.run.RunnerTerminalService
import ai.rever.boss.window.WindowOperations
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.size
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.ViewColumn
import ai.rever.boss.utils.revealInFileManager
import ai.rever.boss.utils.revealInFileManagerLabel
import androidx.compose.material.icons.outlined.Splitscreen
import androidx.compose.runtime.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import kotlin.time.Clock
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

private val bossMainWindowPanelLogger = BossLogger.forComponent("BossMainWindowPanel")

/**
 * Wrapper for BossTabButton that loads and displays favicons from cache
 * Uses shared rememberFaviconLoader composable for DRY and error handling
 */
@Composable
private fun BossTabButtonWithFavicon(
    config: TabInfo,
    isSelected: Boolean,
    isFocused: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    contextMenuItems: List<ContextMenuItem>,
    tabWidth: androidx.compose.ui.unit.Dp,
    // Drag-related parameters
    tabDragComponent: TabDraggableComponent? = null,
    panelId: String? = null,
    tabIndex: Int = -1,
    onDragEnd: (TabDropResult?) -> Unit = {}
) {
    // Load favicon using shared composable (with error handling and caching)
    val loadedFavicon = rememberFaviconLoader(config)

    // Determine which icon to use: loaded favicon > config.tabIcon > fallback to config.icon
    val effectiveTabIcon = loadedFavicon ?: config.tabIcon

    // Middle-click handling is now in BossTabButton.kt (Issue #328)
    BossTabButton(
        fileName = config.title,
        icon = config.icon,
        tabIcon = effectiveTabIcon,
        isSelected = isSelected,
        isFocused = isFocused,
        onClick = onClick,
        onClose = onClose,
        contextMenuItems = contextMenuItems,
        tabWidth = tabWidth,
        tabDragComponent = tabDragComponent,
        tabInfo = config,
        panelId = panelId,
        tabIndex = tabIndex,
        onDragEnd = onDragEnd
    )
}

@Composable
fun BossTabsComponent.BossMainTabBar(
    splitViewState: ai.rever.boss.components.window_panel.SplitViewState? = null,
    currentPanelId: String? = null,
    focusRequester: FocusRequester? = null,
    tabDragComponent: TabDraggableComponent? = null,
    onTabDropResult: (TabDropResult) -> Unit = {}
) {
    val tabsState = tabsState.subscribeAsState()
    var showNewTabDialog by remember { mutableStateOf(false) }
    var selectedTabType by remember { mutableStateOf<TabType?>(null) }
    // Per-window project state for terminal working directory
    val windowProjectState = LocalWindowProjectState.current
    var showBookmarkDialog by remember { mutableStateOf(false) }
    var tabToBookmark by remember { mutableStateOf<TabInfo?>(null) }

    // Observe collections for reactive context menu updates (gracefully handles missing plugin)
    val collections = rememberBookmarkCollections()

    // Remove bookmark dialog state
    var showRemoveBookmarkDialog by remember { mutableStateOf(false) }
    var bookmarkToRemove by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    // Triple = (collectionId, bookmarkId, tabTitle)

    // LazyListState for tab bar scrolling
    val listState = rememberLazyListState()

    // Coroutine scope for edge scroll animation
    val edgeScrollScope = rememberCoroutineScope()

    // Set up edge scroll handler for drag-and-drop
    // Each panel registers its own callback to avoid race conditions with multiple panels
    DisposableEffect(tabDragComponent, currentPanelId) {
        if (tabDragComponent != null && currentPanelId != null) {
            tabDragComponent.registerEdgeScrollCallback(currentPanelId) { direction ->
                edgeScrollScope.launch {
                    val layoutInfo = listState.layoutInfo
                    val visibleItems = layoutInfo.visibleItemsInfo

                    when (direction) {
                        ScrollDirection.LEFT -> {
                            // Scroll to previous item
                            val firstVisible = visibleItems.firstOrNull()?.index ?: 0
                            if (firstVisible > 0) {
                                listState.animateScrollToItem(firstVisible - 1)
                            }
                        }
                        ScrollDirection.RIGHT -> {
                            // Scroll to next item
                            val lastVisible = visibleItems.lastOrNull()?.index ?: 0
                            val totalItems = tabsState.value.tabs.size
                            if (lastVisible < totalItems - 1) {
                                listState.animateScrollToItem(lastVisible + 1)
                            }
                        }
                    }
                }
            }
        }
        onDispose {
            // Unregister this panel's callback to prevent memory leaks
            if (tabDragComponent != null && currentPanelId != null) {
                tabDragComponent.unregisterEdgeScrollCallback(currentPanelId)
            }
        }
    }

    // Auto-scroll to active tab when it changes
    LaunchedEffect(tabsState.value.activeIndex) {
        val activeIndex = tabsState.value.activeIndex
        if (activeIndex >= 0 && activeIndex < tabsState.value.tabs.size) {
            // Only scroll if the tab is not fully visible
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo

            // Check if the tab is fully visible (both left and right edges within viewport)
            val activeItem = visibleItems.find { it.index == activeIndex }
            val isFullyVisible = activeItem?.let { item ->
                val itemStart = item.offset
                val itemEnd = item.offset + item.size
                val viewportStart = layoutInfo.viewportStartOffset
                val viewportEnd = layoutInfo.viewportEndOffset

                // Item is fully visible if both edges are within viewport
                itemStart >= viewportStart && itemEnd <= viewportEnd
            } ?: false

            if (!isFullyVisible) {
                // Scroll to bring the tab fully into view
                listState.scrollToItem(activeIndex)
            }
        }
    }

    // Track drop target for reorder indicator
    val dropTarget = tabDragComponent?.dropTarget

    HorizontalBar(
        height = 42.dp,
        backgroundColor = BossDarkBackground
    ) {
        HorizontalBarRow(
            modifier = Modifier.onGloballyPositioned { coordinates ->
                // Register tab bar bounds for drag detection
                if (currentPanelId != null && tabDragComponent != null) {
                    val bounds = coordinates.boundsInWindow()
                    tabDragComponent.registerTabBarBounds(currentPanelId, bounds)
                }
            }
        ) {
            BossLeftTabBar(listState, tabCount = tabsState.value.tabs.size) { tabWidth ->
                // Render tab buttons as lazy items
                itemsIndexed(tabsState.value.tabs) { index, config ->
                    val isSelected = index == tabsState.value.activeIndex
                    val totalTabs = tabsState.value.tabs.size

                    // Show reorder indicator before this tab if it's the drop target
                    val showIndicatorBefore = dropTarget is TabDropTarget.Reorder &&
                        dropTarget.panelId == currentPanelId &&
                        dropTarget.targetIndex == index

                    if (showIndicatorBefore) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .padding(vertical = 8.dp)
                                .background(BossDarkAccent)
                        )
                    }

                    BossTabButtonWithFavicon(
                        config = config,
                        isSelected = isSelected,
                        isFocused = true, // Tab bars are always considered focused when window is active
                        tabWidth = tabWidth,
                        onClick = {
                            selectTab(index)
                            // Track this tab interaction for Cmd+R/Cmd+N
                            if (splitViewState != null && currentPanelId != null) {
                                splitViewState.trackTabInteraction(currentPanelId, config.id)
                            }
                        },
                        onClose = {
                            removeTab(index)
                            // Request focus back to the main panel after closing tab
                            // This ensures keyboard shortcuts continue to work
                            focusRequester?.requestFocus()
                        },
                        tabDragComponent = tabDragComponent,
                        panelId = currentPanelId,
                        tabIndex = index,
                        onDragEnd = { result ->
                            // endDrag() already called in BossTabButton, just handle result
                            result?.let { onTabDropResult(it) }
                        },
                        contextMenuItems = buildList {
                            // NOTE: Do NOT call trackTabInteraction/setActivePanel here.
                            // buildList runs during composition (every tab-bar
                            // recomposition, e.g. on every terminal output line), so
                            // doing it here flips the active panel away from whichever
                            // split the user is actually in — stealing focus back to the
                            // output-producing panel. Panel activation on right-click is
                            // already handled by the panel's pointerInput press handler;
                            // left-click activation by the tab onClick above.

                            // Split operations (if split state is available)
                            if (splitViewState != null && currentPanelId != null) {
                                add(ContextMenuItem("Split Right", Icons.Outlined.ViewColumn, onClick = {
                                    splitViewState.splitPanel(
                                        panelId = currentPanelId,
                                        orientation = ai.rever.boss.components.window_panel.SplitOrientation.VERTICAL,
                                        tabToMove = config
                                    )
                                }))
                                add(ContextMenuItem("Split Down", Icons.Outlined.Splitscreen, onClick = {
                                    splitViewState.splitPanel(
                                        panelId = currentPanelId,
                                        orientation = ai.rever.boss.components.window_panel.SplitOrientation.HORIZONTAL,
                                        tabToMove = config
                                    )
                                }))
                                add(ContextMenuItem(isDivider = true))
                            }

                            // Reveal the tab's backing file in the OS file manager (file-backed tabs).
                            // Host tab types expose filePath directly. Dynamic plugin tabs (e.g. the
                            // editor-tab plugin's EditorTabData) live in a plugin classloader we can't
                            // reference by type, so fall back to reading a `filePath` getter reflectively
                            // — the same duck-typing the editor-tab plugin uses for host tab types.
                            // The reflected value is assumed absolute: revealInFileManager resolves via
                            // File(path).absolutePath, so a relative path would resolve against the CWD.
                            val revealPath = when (val tab = config) {
                                is EditorTabInfo -> tab.filePath
                                is JupyterTabInfo -> tab.filePath
                                else -> runCatching {
                                    tab.javaClass.getMethod("getFilePath").invoke(tab) as? String
                                }.getOrNull()
                            }?.takeIf { it.isNotBlank() }
                            if (revealPath != null) {
                                add(ContextMenuItem(revealInFileManagerLabel(), Icons.Outlined.FolderOpen, onClick = {
                                    revealInFileManager(revealPath)
                                }))
                                add(ContextMenuItem(isDivider = true))
                            }

                            // Bookmark current tab
                            // Reference collections to ensure recomposition on bookmark changes
                            collections

                            val tabConfig = convertTabInfoToTabConfig(config)
                            val existingBookmark = BookmarkAPIAccess.findBookmarkForTab(tabConfig)

                            if (existingBookmark != null) {
                                // Tab is already bookmarked - show remove option WITH CONFIRMATION
                                val (collectionId, bookmarkId) = existingBookmark
                                add(ContextMenuItem("Remove from Bookmarks", Icons.Filled.Star, onClick = {
                                    bookmarkToRemove = Triple(collectionId, bookmarkId, config.title)
                                    showRemoveBookmarkDialog = true
                                }))
                            } else {
                                // Tab is not bookmarked - show add option
                                add(ContextMenuItem("Add to Bookmarks", Icons.Outlined.Star, onClick = {
                                    tabToBookmark = config
                                    showBookmarkDialog = true
                                }))
                            }

                            // Favorite current workspace
                            val currentWorkspace = workspaceManager.currentWorkspace.value
                            if (currentWorkspace != null) {
                                val isFavorited = BookmarkAPIAccess.isFavorite(currentWorkspace.id)
                                add(ContextMenuItem(
                                    if (isFavorited) "Unfavorite Workspace" else "Favorite Workspace",
                                    if (isFavorited) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                    onClick = {
                                        if (isFavorited) {
                                            BookmarkAPIAccess.removeFavoriteWorkspace(currentWorkspace.id)
                                        } else {
                                            BookmarkAPIAccess.addFavoriteWorkspace(currentWorkspace.id, currentWorkspace.name)
                                        }
                                    }
                                ))
                            }

                            add(ContextMenuItem(isDivider = true))

                            // Open in New Window (if multi-window is supported)
                            if (ai.rever.boss.window.WindowOperations.isMultiWindowSupported()) {
                                add(ContextMenuItem("Open in New Window", Icons.AutoMirrored.Outlined.OpenInNew, onClick = {
                                    ai.rever.boss.window.WindowOperations.openTabInNewWindow(config)
                                    // Remove tab from current window after opening in new window
                                    removeTab(index)
                                    // Request focus back to the main panel
                                    focusRequester?.requestFocus()
                                }))
                                add(ContextMenuItem(isDivider = true))
                            }

                            // Close current tab
                            add(ContextMenuItem("Close Tab", Icons.Outlined.Close, onClick = {
                                removeTab(index)
                                // Request focus back to the main panel
                                focusRequester?.requestFocus()
                            }))

                            // Close other tabs (only show if there are other tabs)
                            if (totalTabs > 1) {
                                add(ContextMenuItem("Close Other Tabs", Icons.Outlined.Clear, onClick = {
                                    closeOtherTabs(index)
                                    // Request focus back to the main panel
                                    focusRequester?.requestFocus()
                                }))
                            }

                            // Close tabs to the right (only show if there are tabs to the right)
                            if (index < totalTabs - 1) {
                                add(ContextMenuItem("Close Tabs to the Right", Icons.Outlined.ChevronRight, onClick = {
                                    closeTabsToRight(index)
                                    // Request focus back to the main panel
                                    focusRequester?.requestFocus()
                                }))
                            }

                            // Close tabs to the left (only show if there are tabs to the left)
                            if (index > 0) {
                                add(ContextMenuItem("Close Tabs to the Left", Icons.Outlined.ChevronLeft, onClick = {
                                    closeTabsToLeft(index)
                                    // Request focus back to the main panel
                                    focusRequester?.requestFocus()
                                }))
                            }
                        }
                    )

                    // Vertical divider after tab (only if not the last tab)
                    if (index < tabsState.value.tabs.size - 1) {
                        VDivider(modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp))
                    }

                    // Show reorder indicator after the last tab if dropping at the end
                    val isLastTab = index == tabsState.value.tabs.size - 1
                    val showIndicatorAfter = isLastTab &&
                        dropTarget is TabDropTarget.Reorder &&
                        dropTarget.panelId == currentPanelId &&
                        dropTarget.targetIndex == tabsState.value.tabs.size

                    if (showIndicatorAfter) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .padding(vertical = 8.dp)
                                .background(BossDarkAccent)
                        )
                    }
                }

            }

            // Plus button — always rendered outside the LazyRow as a sibling
            // of BossLeftTabBar, so it stays put when the tab strip scrolls.
            // Previously this had inside/outside variants gated on `isScrollable`;
            // the inside variant (a LazyRow `item { }`) scrolled off-screen the
            // moment the user dragged the tab strip, before isScrollable could
            // flip and the outside fallback could paint.
            //
            // `padding(end = 12.dp)` reserves extra breathing room on the right
            // edge so the icon doesn't feel jammed against the next bar
            // element (the right tab-bar section / window-control area).
            Box(
                modifier = Modifier
                    .padding(end = 12.dp)
                    .height(32.dp)
                    .width(32.dp)
                    .padding(4.dp)
                    .background(
                        color = BossDarkSurface,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                    )
                    .clickable {
                        showNewTabDialog = true
                        // Track panel interaction when plus button is clicked
                        if (splitViewState != null && currentPanelId != null) {
                            splitViewState.setActivePanel(currentPanelId)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "New Tab",
                    tint = BossDarkTextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(
                modifier = Modifier
                    .fillMaxHeight()
                    .contextMenu(
                        items = buildList {
                            add(ContextMenuItem("New Tab", Icons.Default.Add, onClick = {
                                showNewTabDialog = true
                                // Track panel interaction when context menu is used
                                if (splitViewState != null && currentPanelId != null) {
                                    splitViewState.setActivePanel(currentPanelId)
                                }
                            }))

                            add(ContextMenuItem(isDivider = true))

                            // Favorite current workspace
                            val currentWorkspace = workspaceManager.currentWorkspace.value
                            if (currentWorkspace != null) {
                                val isFavorited = BookmarkAPIAccess.isFavorite(currentWorkspace.id)
                                add(ContextMenuItem(
                                    if (isFavorited) "Unfavorite Workspace" else "Favorite Workspace",
                                    if (isFavorited) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                    onClick = {
                                        if (isFavorited) {
                                            BookmarkAPIAccess.removeFavoriteWorkspace(currentWorkspace.id)
                                        } else {
                                            BookmarkAPIAccess.addFavoriteWorkspace(currentWorkspace.id, currentWorkspace.name)
                                        }
                                    }
                                ))
                            }
                        }
                    )
            )
        }
    }
    
    // New Tab Dialog
    if (showNewTabDialog) {
        NewTabDialog(
            onDismiss = {
                showNewTabDialog = false
                selectedTabType = null
            },
            tabRegistry = tabRegistry,
            initialTabType = selectedTabType,
            onCreateTab = { type, path ->
                when (type) {
                    TabType.URL -> {
                        val timestamp = Clock.System.now().toEpochMilliseconds()
                        val fluckTab = ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo(
                            id = "fluck-$timestamp",
                            typeId = FluckTabType.typeId,
                            _title = "Loading...",
                            url = path
                        )
                        val tabIndex = addTab(fluckTab)
                        if (tabIndex >= 0) {
                            selectTab(tabIndex)
                        }
                    }
                    TabType.FILE -> {
                        val timestamp = Clock.System.now().toEpochMilliseconds()
                        val fileName = path.extractFileName().ifEmpty { "untitled.txt" }
                        val fileIconInfo = FileIcons.forFile(fileName)
                        val editorTab = EditorTabInfo(
                            id = "editor-$timestamp",
                            title = fileName,
                            typeId = CodeEditorTabType.typeId,
                            icon = fileIconInfo.icon,
                            tabIcon = ai.rever.boss.plugin.api.TabIcon.Vector(fileIconInfo.icon, fileIconInfo.color),
                            filePath = path
                        )
                        val tabIndex = addTab(editorTab)
                        if (tabIndex >= 0) {
                            selectTab(tabIndex)
                        }
                    }
                    TabType.TERMINAL -> {
                        val timestamp = Clock.System.now().toEpochMilliseconds()
                        // Get current project path for terminal working directory (per-window)
                        val projectPath = windowProjectState?.selectedProject?.value?.path ?: ""
                        val terminalTab = TerminalTabInfo(
                            id = "terminal-$timestamp",
                            typeId = ai.rever.boss.plugin.tab.terminal.TerminalTabType.typeId,
                            title = "Terminal",
                            icon = ai.rever.boss.plugin.tab.terminal.TerminalTabType.icon,
                            initialCommand = path.ifBlank { null },
                            workingDirectory = projectPath.ifEmpty { null }
                        )
                        val tabIndex = addTab(terminalTab)
                        if (tabIndex >= 0) {
                            selectTab(tabIndex)
                        }
                    }
                    TabType.JUPYTER -> {
                        val jupyterTab = JupyterTabInfo.createUntitled(path)
                        val tabIndex = addTab(jupyterTab)
                        if (tabIndex >= 0) {
                            selectTab(tabIndex)
                        }
                    }
                }
            },
            // Plugin tab types build their own TabInfo; open it the same way.
            onCreateTabInfo = { tabInfo ->
                val tabIndex = addTab(tabInfo)
                if (tabIndex >= 0) {
                    selectTab(tabIndex)
                }
            },
            projectPath = windowProjectState?.selectedProject?.value?.path
        )
    }

    // Bookmark dialog (gracefully handles missing bookmarks plugin)
    if (showBookmarkDialog && tabToBookmark != null) {
        val dialogCollections = rememberBookmarkCollections()
        val workspaces by workspaceManager.workspaces.collectAsState()
        BookmarkDialog(
            tabTitle = tabToBookmark!!.title,
            collections = dialogCollections,
            workspaces = workspaces,
            onDismiss = {
                showBookmarkDialog = false
                tabToBookmark = null
            },
            onConfirm = { collectionIds, workspacePanelMap ->
                val tabConfig = convertTabInfoToTabConfig(tabToBookmark!!)
                val workspace = workspaceManager.currentWorkspace.value

                // Convert workspacePanelMap to list of WorkspacePanelTarget
                val targetWorkspaces = workspacePanelMap.map { (workspaceName, panelId) ->
                    WorkspacePanelTarget(workspaceName = workspaceName, panelId = panelId)
                }

                // Create bookmark for each selected collection
                collectionIds.forEach { collectionId ->
                    val bookmark = Bookmark(
                        tabConfig = tabConfig,
                        workspaceName = workspace?.name ?: "Unknown",
                        targetWorkspaces = targetWorkspaces
                    )
                    val collection = dialogCollections.find { it.id == collectionId }
                    if (collection != null) {
                        BookmarkAPIAccess.addBookmark(collection.name, bookmark)
                    }
                }

                showBookmarkDialog = false
                tabToBookmark = null
            }
        )
    }

    // Remove bookmark confirmation dialog
    if (showRemoveBookmarkDialog && bookmarkToRemove != null) {
        RemoveBookmarkConfirmationDialog(
            bookmarkTitle = bookmarkToRemove!!.third,
            onDismiss = {
                showRemoveBookmarkDialog = false
                bookmarkToRemove = null
            },
            onConfirm = {
                bookmarkToRemove?.let { (collectionId, bookmarkId, _) ->
                    BookmarkAPIAccess.removeBookmark(collectionId, bookmarkId)
                }
                showRemoveBookmarkDialog = false
                bookmarkToRemove = null
            }
        )
    }
}

@Composable
fun BossTabsComponent.BossMainPanel(
    modifier: Modifier = Modifier,
    splitViewState: ai.rever.boss.components.window_panel.SplitViewState? = null,
    currentPanelId: String? = null,
    tabDragComponent: TabDraggableComponent? = null,
    onTabDropResult: (TabDropResult) -> Unit = {},
    onShowSettings: (() -> Unit)? = null,
    onOpenProjectDialog: (() -> Unit)? = null,
    onNewProject: (() -> Unit)? = null
) {
    val focusRequester = remember { FocusRequester() }
    val isFocused = remember { mutableStateOf(false) }

    // Track the active panel state to force recomposition
    val activePanelId by splitViewState?.activePanelIdState ?: remember { mutableStateOf("") }
    val isActivePanel = activePanelId == currentPanelId


    Column(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .onFocusChanged { focusState ->
                isFocused.value = focusState.isFocused || focusState.hasFocus
                if ((focusState.isFocused || focusState.hasFocus) && currentPanelId != null) {
                    splitViewState?.setActivePanel(currentPanelId)
                }
            }
            .focusable()
            // Detect pointer presses to mark this panel active even when child content
            // (JxBrowser, native AWT components) doesn't propagate Compose focus events
            // upward. PointerEventPass.Initial observes without consuming, so children
            // still receive the press unchanged.
            .pointerInput(currentPanelId, splitViewState) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press && currentPanelId != null) {
                            splitViewState?.setActivePanel(currentPanelId)
                        }
                    }
                }
            }
            // Removed .clickable() - it was stealing focus from child components (terminals)
            // Panel activation is handled by .onFocusChanged() above and .pointerInput() above
            .then(
                if (isActivePanel) {
                    Modifier.border(2.dp, MaterialTheme.colors.primary.copy(alpha = 0.5f))
                } else {
                    Modifier
                }
            )
    ) {
        BossMainTabBar(
            splitViewState = splitViewState,
            currentPanelId = currentPanelId,
            focusRequester = focusRequester,
            tabDragComponent = tabDragComponent,
            onTabDropResult = onTabDropResult
        )
        Divider(color = BossDarkBorder)
        // Expose `isActivePanel` to nested plugin composables via a
        // CompositionLocal. Plugins that embed widgets sensitive to
        // host focus transitions (e.g. the BossTerm-backed terminal-tab
        // plugin) read this and forward it into their widget so the
        // widget can re-issue its internal focus requester when the
        // surrounding panel regains user attention.
        CompositionLocalProvider(LocalIsPanelActive provides isActivePanel) {
            BossMainPanelContent(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                splitViewState = splitViewState,
                currentPanelId = currentPanelId,
                onShowSettings = onShowSettings,
                onOpenProjectDialog = onOpenProjectDialog,
                onNewProject = onNewProject
            )
        }
    }
}

/**
 * Main UI composable that displays the root component
 */
@Composable
fun BossTabsComponent.BossMainPanelContent(
    modifier: Modifier,
    splitViewState: ai.rever.boss.components.window_panel.SplitViewState? = null,
    currentPanelId: String? = null,
    onShowSettings: (() -> Unit)? = null,
    onOpenProjectDialog: (() -> Unit)? = null,
    onNewProject: (() -> Unit)? = null
) {
    // Subscribe to tab state changes to trigger recomposition
    val tabsState = tabsState.subscribeAsState()

    // State for new tab dialog (needed for EmptyContent callbacks)
    var showNewTabDialog by remember { mutableStateOf(false) }
    var selectedTabType by remember { mutableStateOf<TabType?>(null) }

    // Coroutine scope for async operations
    val scope = rememberCoroutineScope()

    // Per-window project state for Dashboard (required for multi-window support)
    val windowProjectState = LocalWindowProjectState.current
    val selectedProject by windowProjectState?.selectedProject?.collectAsState()
        ?: remember { mutableStateOf(Project("No Project", "", 0L)) }

    Box(modifier = modifier) {
        val activeTab = tabsState.value.activeTab
        val activeComponent = getActiveComponent()

        // Only render the active tab - hidden tabs would still receive input
        // Terminal state is preserved by TerminalStateRegistry (keyed by tab ID)
        if (activeTab != null && activeComponent != null) {
            val sandbox = TabSandboxRegistry.getSandbox(activeTab.typeId)

            // Register pluginId → (tabId, closeAction) BEFORE entering PluginErrorBoundary.
            // This runs during composition (via remember), so it's set before content()
            // is invoked and before any crash can occur. The closeAction captures a direct
            // reference to this BossTabsComponent, avoiding dependency on SplitViewStateRegistry
            // (which may not be populated yet during the first composition frame).
            if (sandbox != null) {
                val tabIdToClose = activeTab.id
                val pluginIdToRegister = sandbox.pluginId
                remember(tabIdToClose, pluginIdToRegister) {
                    PluginCrashRegistry.registerActiveTab(
                        pluginIdToRegister,
                        tabIdToClose,
                        closeAction = { this@BossMainPanelContent.removeTabById(tabIdToClose) }
                    )
                }
                DisposableEffect(tabIdToClose, pluginIdToRegister) {
                    onDispose {
                        PluginCrashRegistry.unregisterActiveTab(pluginIdToRegister, tabIdToClose)
                    }
                }
            }

            val pluginLogger = if (sandbox != null) remember { BossLogger.forComponent("BossMainPanelContent") } else null

            key(activeTab.id) {
                if (sandbox != null) {
                    PluginErrorBoundary(
                        pluginId = sandbox.pluginId,
                        sandbox = sandbox,
                        onRestart = {
                            scope.launch {
                                val result = sandbox.restart()
                                if (result.isFailure) {
                                    pluginLogger?.error(LogCategory.UI, "Failed to restart plugin", mapOf(
                                        "pluginId" to sandbox.pluginId,
                                        "error" to (result.exceptionOrNull()?.message ?: "unknown")
                                    ))
                                    StatusMessageManager.showMessage(
                                        "Failed to restart plugin: ${sandbox.pluginId}",
                                        durationMs = 5000
                                    )
                                }
                            }
                        }
                    ) {
                        activeComponent.Content()
                    }
                } else {
                    // No sandbox - render directly (built-in tabs or backwards compatibility)
                    activeComponent.Content()
                }
            }
        } else {
            // Show Dashboard when no tabs are open
            Dashboard(
                onOpenFile = { filePath ->
                    splitViewState?.openFileInActivePanel(
                        filePath,
                        filePath.extractFileName().ifEmpty { "untitled" }
                    )
                },
                onOpenUrl = { url ->
                    splitViewState?.openUrlInActivePanel(url, "Loading...")
                },
                onOpenProject = { project ->
                    selectProjectInWindow(windowProjectState, project)
                },
                selectedProject = selectedProject,
                onNewTab = {
                    selectedTabType = null
                    showNewTabDialog = true
                },
                onNewTerminal = {
                    // Create a new terminal tab
                    val timestamp = Clock.System.now().toEpochMilliseconds()
                    // Use per-window project state for terminal working directory
                    val projectPath = selectedProject.path
                    val terminalTab = TerminalTabInfo(
                        id = "terminal-$timestamp",
                        typeId = ai.rever.boss.plugin.tab.terminal.TerminalTabType.typeId,
                        title = "Terminal",
                        icon = ai.rever.boss.plugin.tab.terminal.TerminalTabType.icon,
                        workingDirectory = projectPath.ifEmpty { null }
                    )
                    val tabIndex = addTab(terminalTab)
                    if (tabIndex >= 0) {
                        selectTab(tabIndex)
                    }
                },
                onNewWindow = {
                    WindowOperations.createNewWindow()
                },
                onOpenProjectDialog = {
                    // Use the callback passed from BossApp which has access to windowId
                    onOpenProjectDialog?.invoke()
                },
                onOpenFileDialog = {
                    selectedTabType = TabType.FILE
                    showNewTabDialog = true
                },
                onApplySplitTemplate = { template ->
                    // Find matching predefined workspace by template ID
                    val workspaceId = "workspace-${template.id}"
                    val matchingWorkspace = PredefinedWorkspaces.allWorkspaces.find { it.id == workspaceId }
                        ?: PredefinedWorkspaces.allWorkspaces.find { it.name == template.name }

                    // Always apply the workspace first (Issue #445)
                    // This ensures terminal + browser both open in split view
                    if (matchingWorkspace != null && splitViewState != null) {
                        workspaceManager.loadWorkspace(matchingWorkspace)
                        scope.launch {
                            applyWorkspace(matchingWorkspace, splitViewState)
                        }
                    } else {
                        applySplitTemplate(template, splitViewState, currentPanelId, selectedProject.path)
                    }
                },
                onActivatePlugin = { pluginId ->
                    // Plugin activation is handled via sidebar panels
                    // Dashboard displays available plugins but activation uses existing sidebar UI
                    bossMainWindowPanelLogger.debug(LogCategory.UI, "Plugin activation requested", mapOf("pluginId" to pluginId))
                },
                onShowSettings = onShowSettings,
                onNewProject = { onNewProject?.invoke() }
            )
        }
    }

    // New Tab Dialog (for EmptyContent interactions)
    if (showNewTabDialog) {
        NewTabDialog(
            onDismiss = {
                showNewTabDialog = false
                selectedTabType = null
            },
            tabRegistry = tabRegistry,
            initialTabType = selectedTabType,
            onCreateTab = { type, path ->
                when (type) {
                    TabType.URL -> {
                        val timestamp = Clock.System.now().toEpochMilliseconds()
                        val fluckTab = ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo(
                            id = "fluck-$timestamp",
                            typeId = FluckTabType.typeId,
                            _title = "Loading...",
                            url = path
                        )
                        val tabIndex = addTab(fluckTab)
                        if (tabIndex >= 0) {
                            selectTab(tabIndex)
                        }
                    }
                    TabType.FILE -> {
                        val timestamp = Clock.System.now().toEpochMilliseconds()
                        val fileName = path.extractFileName().ifEmpty { "untitled.txt" }
                        val fileIconInfo = FileIcons.forFile(fileName)
                        val editorTab = EditorTabInfo(
                            id = "editor-$timestamp",
                            title = fileName,
                            typeId = CodeEditorTabType.typeId,
                            icon = fileIconInfo.icon,
                            tabIcon = ai.rever.boss.plugin.api.TabIcon.Vector(fileIconInfo.icon, fileIconInfo.color),
                            filePath = path
                        )
                        val tabIndex = addTab(editorTab)
                        if (tabIndex >= 0) {
                            selectTab(tabIndex)
                        }
                    }
                    TabType.TERMINAL -> {
                        val timestamp = Clock.System.now().toEpochMilliseconds()
                        // Get current project path for terminal working directory (per-window)
                        val projectPath = selectedProject.path
                        val terminalTab = TerminalTabInfo(
                            id = "terminal-$timestamp",
                            typeId = ai.rever.boss.plugin.tab.terminal.TerminalTabType.typeId,
                            title = "Terminal",
                            icon = ai.rever.boss.plugin.tab.terminal.TerminalTabType.icon,
                            initialCommand = path.ifBlank { null },
                            workingDirectory = projectPath.ifEmpty { null }
                        )
                        val tabIndex = addTab(terminalTab)
                        if (tabIndex >= 0) {
                            selectTab(tabIndex)
                        }
                    }
                    TabType.JUPYTER -> {
                        val jupyterTab = JupyterTabInfo.createUntitled(path)
                        val tabIndex = addTab(jupyterTab)
                        if (tabIndex >= 0) {
                            selectTab(tabIndex)
                        }
                    }
                }
            },
            // Plugin tab types build their own TabInfo; open it the same way.
            onCreateTabInfo = { tabInfo ->
                val tabIndex = addTab(tabInfo)
                if (tabIndex >= 0) {
                    selectTab(tabIndex)
                }
            },
            projectPath = selectedProject.path.ifEmpty { null }
        )
    }
}

/**
 * Apply a split template to create a split view with pre-configured tabs.
 *
 * @param template The split template to apply
 * @param splitViewState The split view state (if available)
 * @param currentPanelId The current panel ID
 * @param projectPath The current project path for template placeholders
 */
private fun BossTabsComponent.applySplitTemplate(
    template: SplitTemplate,
    splitViewState: ai.rever.boss.components.window_panel.SplitViewState?,
    currentPanelId: String?,
    projectPath: String
) {
    if (splitViewState == null || currentPanelId == null) return

    val resolvedProjectPath = projectPath.ifEmpty {
        System.getProperty("user.home") ?: ""
    }

    // Process the template panels
    val panels = template.panels
    if (panels.isEmpty()) return

    // Get left and right panel configs
    val leftPanelConfig = panels.find { it.position == "left" }
    val rightPanelConfig = panels.find { it.position == "right" }

    // Create left panel tab first (in current panel)
    val leftTab = leftPanelConfig?.let { createTabFromConfig(it, resolvedProjectPath) }
    val rightTab = rightPanelConfig?.let { createTabFromConfig(it, resolvedProjectPath) }

    if (leftTab != null) {
        // Add left tab to current panel
        val leftIndex = addTab(leftTab)
        if (leftIndex >= 0) {
            selectTab(leftIndex)
        }

        // If there's a right panel, create a split
        if (rightTab != null) {
            splitViewState.splitPanel(
                panelId = currentPanelId,
                orientation = SplitOrientation.VERTICAL,
                tabToMove = rightTab
            )
        }
    } else if (rightTab != null) {
        // Only right panel specified, just add it
        val rightIndex = addTab(rightTab)
        if (rightIndex >= 0) {
            selectTab(rightIndex)
        }
    }
}

/**
 * Create a tab from template panel configuration.
 */
private fun createTabFromConfig(
    panelConfig: ai.rever.boss.dashboard.TemplatePanelConfig,
    projectPath: String
): ai.rever.boss.components.registery.TabInfo? {
    val timestamp = Clock.System.now().toEpochMilliseconds()

    return when (panelConfig.type) {
        "terminal" -> {
            val command = panelConfig.content.command?.let {
                // shell command → quote {projectPath} so paths with spaces/quotes survive
                SplitTemplatesManager.processPlaceholders(it, projectPath, null, quoteProjectPath = true)
            }
            TerminalTabInfo(
                id = "terminal-$timestamp",
                typeId = ai.rever.boss.plugin.tab.terminal.TerminalTabType.typeId,
                title = "Terminal",
                icon = ai.rever.boss.plugin.tab.terminal.TerminalTabType.icon,
                initialCommand = command,
                workingDirectory = projectPath
            )
        }
        "browser" -> {
            val url = panelConfig.content.url?.let {
                SplitTemplatesManager.processPlaceholders(it, projectPath, null)
            } ?: "https://google.com"
            FluckTabInfo(
                id = "fluck-$timestamp",
                typeId = FluckTabType.typeId,
                _title = "Loading...",
                url = url
            )
        }
        "editor" -> {
            val filePath = panelConfig.content.filePath?.let {
                SplitTemplatesManager.processPlaceholders(it, projectPath, null)
            }
            if (filePath != null) {
                val fileName = filePath.extractFileName().ifEmpty { "untitled" }
                val fileIconInfo = FileIcons.forFile(fileName)
                EditorTabInfo(
                    id = "editor-$timestamp",
                    title = fileName,
                    typeId = CodeEditorTabType.typeId,
                    icon = fileIconInfo.icon,
                    tabIcon = ai.rever.boss.plugin.api.TabIcon.Vector(fileIconInfo.icon, fileIconInfo.color),
                    filePath = filePath
                )
            } else null
        }
        else -> null
    }
}

val createBossAppContext get() = DefaultComponentContext(LifecycleRegistry())

/**
 * Snapshot of an in-progress MRU tab cycle, used to render the tab-switcher overlay:
 * the open tabs in cycle order plus the index of the currently-highlighted candidate.
 */
data class TabCycleOverlayData(
    val tabs: List<TabInfo>,
    val highlightedIndex: Int
)

/**
 * Root component for the BOSS app using Decompose for navigation
 *
 * @param windowId The window ID for per-window terminal isolation (Issue #498)
 */
class BossTabsComponent(
    componentContext: ComponentContext,
    val tabRegistry: TabRegistry,
    val windowId: String
) : ComponentContext by componentContext {

    // Unique ID for this component (used for TabUpdateRegistry)
    private val componentId = "${windowId}_${System.identityHashCode(this)}"

    private val tabComponents = mutableStateMapOf<String, TabComponentWithUI>()

    // Per-tab lifecycle registries. Each tab component gets its own ComponentContext whose
    // lifecycle is destroyed when the tab is closed, so components that clean up in
    // lifecycle.onDestroy (e.g. the fluck-browser plugin disposing its JxBrowser handle)
    // actually get destroyed. Previously all tab components shared this panel's context,
    // whose LifecycleRegistry is never destroyed — closing or moving a browser tab leaked
    // a live Chromium process (audio kept playing in the background).
    //
    // Plain map on purpose (tabComponents is a state map only because composition reads
    // it): all tab mutations happen on the UI thread, matching Essenty's lifecycle
    // threading expectations.
    private val tabLifecycles = mutableMapOf<String, LifecycleRegistry>()
    private val tabsNavigation = TabsNavigation<TabInfo>()

    // Expose tab state for UI
    val tabsState: Value<TabsNavigation.TabsState<TabInfo>> = tabsNavigation.state

    // --- Ctrl+Tab tab switching state ---
    // Most-recently-used order of tab ids (most recent first), used by MRU switch mode.
    private val mruTabIds = mutableListOf<String>()
    // Snapshot of the cycle order while an MRU cycle is in progress (hold-modifier,
    // tap-Tab, commit on release); null when no cycle is active.
    private var tabCycleOrder: List<String>? = null
    private var tabCyclePointer: Int = 0

    // Listener for tab type unregistration
    private val unregisterListener: (ai.rever.boss.plugin.api.TabTypeId) -> Unit = { typeId ->
        bossMainWindowPanelLogger.info(LogCategory.UI, "Received unregister notification", mapOf(
            "typeId" to typeId.typeId,
            "pluginId" to typeId.pluginId,
            "windowId" to windowId
        ))
        closeTabsByType(typeId)
    }

    /**
     * Factory for creating TabUpdateProviders for dynamic plugins.
     *
     * This allows tab-based plugins to update their tab's title, icon, and other
     * metadata displayed in the tab bar without needing direct access to the
     * BossTabsComponent.
     */
    val tabUpdateProviderFactory: TabUpdateProviderFactory = object : TabUpdateProviderFactory {
        override fun createProvider(tabId: String, typeId: TabTypeId): TabUpdateProvider? {
            // Find the tab index
            val tabs = tabsState.value.tabs
            val tabIndex = tabs.indexOfFirst { it.id == tabId }
            if (tabIndex < 0) {
                bossMainWindowPanelLogger.warn(LogCategory.UI, "Cannot create TabUpdateProvider - tab not found", mapOf(
                    "tabId" to tabId,
                    "typeId" to typeId.typeId
                ))
                return null
            }

            return BossTabUpdateProvider(
                tabId = tabId,
                typeId = typeId,
                bossTabsComponent = this@BossTabsComponent
            )
        }
    }

    /**
     * Implementation of TabUpdateProvider that updates tabs in BossTabsComponent.
     */
    private inner class BossTabUpdateProvider(
        override val tabId: String,
        private val typeId: TabTypeId,
        private val bossTabsComponent: BossTabsComponent
    ) : TabUpdateProvider {

        override fun updateTitle(title: String) {
            // A blank title never improves the tab chip — e.g. about:blank (the
            // dashboard/home state) fires TitleChanged with an empty title on
            // back-navigation, which used to blank the tab. Keep the last
            // meaningful title instead.
            if (title.isBlank()) return

            val tabs = bossTabsComponent.tabsState.value.tabs
            val tabIndex = tabs.indexOfFirst { it.id == tabId }
            if (tabIndex < 0) return

            val currentTab = tabs[tabIndex]

            // Update based on tab type - built-in FluckTabInfo or generic via reflection
            val updatedTab = when (currentTab) {
                is FluckTabInfo -> currentTab.updateTitle(title)
                else -> {
                    // Try reflection for dynamic plugin tab types that have updateTitle(String)
                    try {
                        val updateMethod = currentTab::class.members.find {
                            it.name == "updateTitle" && it.parameters.size == 2 // receiver + title param
                        }
                        val result = updateMethod?.call(currentTab, title) as? TabInfo
                        if (result != null) {
                            result
                        } else {
                            bossMainWindowPanelLogger.debug(LogCategory.UI, "Cannot update title - no updateTitle method", mapOf(
                                "tabId" to tabId,
                                "tabType" to currentTab::class.simpleName
                            ))
                            return
                        }
                    } catch (e: Exception) {
                        bossMainWindowPanelLogger.debug(LogCategory.UI, "Cannot update title via reflection", mapOf(
                            "tabId" to tabId,
                            "tabType" to currentTab::class.simpleName,
                            "error" to (e.message ?: "unknown")
                        ))
                        return
                    }
                }
            }

            bossTabsComponent.updateTab(tabIndex, updatedTab)
        }

        override fun updateFavicon(faviconUrl: String?) {
            val tabs = bossTabsComponent.tabsState.value.tabs
            val tabIndex = tabs.indexOfFirst { it.id == tabId }
            if (tabIndex < 0) return

            val currentTab = tabs[tabIndex]

            // Update based on tab type - built-in FluckTabInfo or generic via reflection
            val updatedTab = when (currentTab) {
                is FluckTabInfo -> currentTab.updateFaviconCacheKey(faviconUrl)
                else -> {
                    // Try reflection for dynamic plugin tab types that have updateFaviconCacheKey(String?)
                    try {
                        val updateMethod = currentTab::class.members.find {
                            it.name == "updateFaviconCacheKey" && it.parameters.size == 2
                        }
                        val result = updateMethod?.call(currentTab, faviconUrl) as? TabInfo
                        if (result != null) {
                            result
                        } else {
                            bossMainWindowPanelLogger.debug(LogCategory.UI, "Cannot update favicon - no updateFaviconCacheKey method", mapOf(
                                "tabId" to tabId,
                                "tabType" to currentTab::class.simpleName
                            ))
                            return
                        }
                    } catch (e: Exception) {
                        bossMainWindowPanelLogger.debug(LogCategory.UI, "Cannot update favicon via reflection", mapOf(
                            "tabId" to tabId,
                            "tabType" to currentTab::class.simpleName,
                            "error" to (e.message ?: "unknown")
                        ))
                        return
                    }
                }
            }

            bossTabsComponent.updateTab(tabIndex, updatedTab)
        }

        override fun updateUrl(url: String) {
            val tabs = bossTabsComponent.tabsState.value.tabs
            val tabIndex = tabs.indexOfFirst { it.id == tabId }
            if (tabIndex < 0) return

            val currentTab = tabs[tabIndex]

            if (currentTab is FluckTabInfo) {
                // Landing on home (about:blank renders the dashboard) means no
                // TitleChanged/FaviconChanged will follow — apply the home identity
                // here so the tab never keeps the previous page's title/favicon.
                // The home title also goes into the navigation-history entry, so
                // the visit isn't recorded under the previous page's title.
                val isHome = FluckTabInfo.isHomeUrl(url)
                val title = if (isHome) FluckTabInfo.HOME_TITLE else currentTab.title
                var updatedTab = currentTab.updateNavigation(title, url)
                if (isHome) {
                    updatedTab = updatedTab
                        .updateTitle(FluckTabInfo.HOME_TITLE)
                        .updateFaviconCacheKey(null)
                }
                bossTabsComponent.updateTab(tabIndex, updatedTab)
            }
        }

        override fun closeTab() {
            bossTabsComponent.removeTabById(tabId)
        }

        override fun openNewTab(url: String): String? {
            val newTabId = "browser_${System.currentTimeMillis()}"
            val newTab = FluckTabInfo(
                id = newTabId,
                typeId = ai.rever.boss.plugin.api.TabTypeId("fluck"),
                _title = "Loading...",
                url = url
            )
            val index = bossTabsComponent.addTab(newTab)
            return if (index >= 0) newTabId else null
        }
    }

    init {
        // Register listener to close tabs when their type is unregistered (plugin disabled)
        tabRegistry.addUnregisterListener(unregisterListener)
        bossMainWindowPanelLogger.info(LogCategory.UI, "Registered unregister listener", mapOf(
            "windowId" to windowId
        ))

        // Register this component's TabUpdateProviderFactory with the global registry
        TabUpdateRegistry.register(componentId, tabUpdateProviderFactory)
        bossMainWindowPanelLogger.debug(LogCategory.UI, "Registered TabUpdateProviderFactory", mapOf(
            "componentId" to componentId
        ))
    }

    /**
     * Close all tabs of a specific type.
     * Called when a plugin is disabled/unloaded to clean up its open tabs.
     */
    fun closeTabsByType(typeId: ai.rever.boss.plugin.api.TabTypeId) {
        val tabs = tabsState.value.tabs
        val indicesToRemove = mutableListOf<Int>()
        
        bossMainWindowPanelLogger.info(LogCategory.UI, "closeTabsByType called", mapOf(
            "targetTypeId" to typeId.typeId,
            "targetPluginId" to typeId.pluginId,
            "tabCount" to tabs.size
        ))
        
        for (i in tabs.indices) {
            val tabTypeId = tabs[i].typeId
            bossMainWindowPanelLogger.debug(LogCategory.UI, "Checking tab", mapOf(
                "index" to i,
                "tabId" to tabs[i].id,
                "tabTypeId" to tabTypeId.typeId,
                "tabPluginId" to tabTypeId.pluginId,
                "matches" to (tabTypeId == typeId)
            ))
            if (tabTypeId == typeId) {
                indicesToRemove.add(i)
                bossMainWindowPanelLogger.info(LogCategory.UI, "Will close tab", mapOf(
                    "tabId" to tabs[i].id,
                    "typeId" to typeId.typeId
                ))
            }
        }
        
        // Remove tabs in reverse order to avoid index issues
        for (i in indicesToRemove.sortedDescending()) {
            removeTab(i)
        }
        
        if (indicesToRemove.isNotEmpty()) {
            bossMainWindowPanelLogger.info(LogCategory.UI, "Closed tabs for disabled plugin", mapOf(
                "typeId" to typeId.typeId,
                "count" to indicesToRemove.size
            ))
        }
    }

    // Add a new tab
    fun addTab(config: TabInfo): Int {
        // Create component for this tab, with its own lifecycle so tab close can destroy it
        // (fires the component's lifecycle.onDestroy — see tabLifecycles).
        val tabLifecycle = LifecycleRegistry()
        val component = tabRegistry.createTabComponent(config, DefaultComponentContext(tabLifecycle))

        if (component != null) {
            // Drive the lifecycle to RESUMED: subscribers added in the component's init get
            // their up-callbacks replayed, and destroy() below CREATED would otherwise be a
            // silent no-op (Essenty only fires onDestroy from CREATED or above).
            tabLifecycle.resume()

            // Store component
            tabComponents[config.id] = component
            tabLifecycles[config.id] = tabLifecycle

            // Register tab with TabUpdateRegistry for plugin updates
            TabUpdateRegistry.registerTab(config.id, componentId)

            // Add to navigation
            val index = tabsNavigation.addTab(config)
            // A newly opened tab becomes active; record it as most-recently-used and end
            // any in-progress MRU cycle.
            recordTabUsage(config.id)
            tabCycleOrder = null
            publishSystemEvent(TabEvent(tabId = config.id, tabType = TabEventType.OPENED, windowId = windowId))
            return index
        }

        // No factory for this type — usually the owning plugin hasn't finished
        // loading. The tab is dropped; workspace restore gates on tab-type
        // registration to avoid hitting this, so reaching here is worth a log.
        // (tabLifecycle stays INITIALIZED with no subscribers and no references — GC'd.)
        bossMainWindowPanelLogger.warn(LogCategory.UI, "Dropped tab - no factory registered for its type", mapOf(
            "typeId" to config.typeId.typeId,
            "title" to config.title
        ))
        return -1 // Failed to create component
    }

    // Remove a tab by index
    fun removeTab(index: Int) {
        val config = tabsState.value.tabs.getOrNull(index)
        config?.let {
            // Unregister tab from TabUpdateRegistry (ownership-checked: a no-op if a move
            // already re-registered this tab id to its destination component)
            TabUpdateRegistry.unregisterTab(it.id, componentId)
            publishSystemEvent(TabEvent(tabId = it.id, tabType = TabEventType.CLOSED, windowId = windowId))

            // Dispose the component if it has a dispose method
            val component = tabComponents.remove(it.id)
            if (component is ai.rever.boss.components.plugin.tab_types.fluck.FluckTabComponent) {
                component.dispose()
            }
            // Destroy the tab's own lifecycle so components that clean up in
            // lifecycle.onDestroy (dynamic plugin tabs like fluck-browser) release their
            // resources — without this a closed browser tab's Chromium process lives on.
            tabLifecycles.remove(it.id)?.destroy()
            // Panel-host tabs keep an explicit close signal (the hosted panel component is
            // owned by PanelComponentStore, not the tab lifecycle — see PanelHostTab.kt):
            // decrements the hosted-as-tab count so the sidebar icon reopens the plugin
            // in its sidebar location once the last hosting tab is closed.
            if (component is ai.rever.boss.components.plugin.tab_types.PanelHostTabComponent) {
                component.onClosed()
            }

            // If this is a runner terminal, notify the service to clean up tracking
            // This handles the case where user closes the tab directly (not via Stop button)
            if (it.id.startsWith(RUNNER_TERMINAL_PREFIX)) {
                RunnerTerminalService.removeTerminal(windowId, it.id)
            }
            // Drop the closed tab from MRU tracking and abandon any in-progress cycle.
            mruTabIds.remove(it.id)
            tabCycleOrder = null
        }
        tabsNavigation.removeTab(index)
    }

    // Remove a tab by ID - safer than index-based removal when state may have changed.
    // Returns true if a tab with that id existed and was removed.
    fun removeTabById(tabId: String): Boolean {
        val index = tabsState.value.tabs.indexOfFirst { it.id == tabId }
        if (index >= 0) {
            removeTab(index)
            return true
        }
        return false
    }

    /**
     * A tab lifted out of one panel for adoption by another (see [detachTab]/[adoptTab]).
     * Carries the live component instance and its lifecycle so a cross-panel move transfers
     * the running tab instead of destroy-and-recreate — a moved browser tab keeps its page
     * (and playing media) instead of reloading and leaking the old browser instance.
     *
     * Contract: the caller MUST hand a non-null DetachedTab to [adoptTab] or call
     * [destroy] on it. Dropping it on the floor keeps the component running (its
     * lifecycle stays RESUMED) with no panel showing it and no cleanup path — the
     * exact leak the detach/adopt mechanism exists to eliminate.
     */
    class DetachedTab internal constructor(
        val config: TabInfo,
        internal val component: TabComponentWithUI,
        internal val lifecycle: LifecycleRegistry?
    ) {
        /** Destroy the detached component instead of adopting it (fires its onDestroy cleanup). */
        fun destroy() {
            lifecycle?.destroy()
        }
    }

    /**
     * Detach a tab for a move: remove it from this panel WITHOUT destroying its component or
     * publishing a CLOSED event. Returns null if the tab or its component is unknown (caller
     * should fall back to remove+add). The returned [DetachedTab.config] is the panel's
     * CURRENT TabInfo (fresh navigation state), not whatever the caller captured at drag start.
     */
    fun detachTab(tabId: String): DetachedTab? {
        val index = tabsState.value.tabs.indexOfFirst { it.id == tabId }
        if (index < 0) return null
        val config = tabsState.value.tabs[index]
        val component = tabComponents.remove(tabId) ?: return null
        val lifecycle = tabLifecycles.remove(tabId)

        // Ownership-checked: no-op if the destination already re-registered this id.
        TabUpdateRegistry.unregisterTab(tabId, componentId)
        mruTabIds.remove(tabId)
        tabCycleOrder = null
        tabsNavigation.removeTab(index)
        return DetachedTab(config, component, lifecycle)
    }

    /**
     * Adopt a tab detached from another panel: the component instance (and its lifecycle)
     * transfer as-is, so the tab keeps running across the move. Counterpart of [detachTab].
     */
    fun adoptTab(detached: DetachedTab): Int {
        // Tab ids are unique across a window, but guard anyway: silently overwriting an
        // existing entry would orphan its component without destroy — the leak shape this
        // change exists to eliminate. Close the stale holder first.
        if (tabComponents.containsKey(detached.config.id)) {
            removeTabById(detached.config.id)
        }
        tabComponents[detached.config.id] = detached.component
        detached.lifecycle?.let { tabLifecycles[detached.config.id] = it }
        TabUpdateRegistry.registerTab(detached.config.id, componentId)
        val index = tabsNavigation.addTab(detached.config)
        recordTabUsage(detached.config.id)
        tabCycleOrder = null
        publishSystemEvent(TabEvent(tabId = detached.config.id, tabType = TabEventType.MOVED, windowId = windowId))
        return index
    }

    // Select a tab
    fun selectTab(index: Int) {
        // A direct selection (tab click, programmatic open) ends any in-progress MRU
        // cycle and marks the tab as most-recently-used.
        tabCycleOrder = null
        tabsNavigation.selectTab(index)
        tabsState.value.tabs.getOrNull(index)?.let { recordTabUsage(it.id) }
    }

    /**
     * Switch to the next tab via Ctrl+Tab. Behavior follows the configured
     * [TabSwitchMode]: positional (next in tab-bar order) or MRU (Alt+Tab style).
     */
    fun switchToNextTab() = switchTab(forward = true)

    /** Switch to the previous tab via Ctrl+Shift+Tab. See [switchToNextTab]. */
    fun switchToPreviousTab() = switchTab(forward = false)

    private fun switchTab(forward: Boolean) {
        val tabs = tabsState.value.tabs
        if (tabs.size <= 1) return
        when (KeymapSettingsManager.currentSettings.value.tabSwitchMode) {
            TabSwitchMode.POSITIONAL -> {
                val cur = tabsState.value.activeIndex.coerceAtLeast(0)
                val step = if (forward) 1 else -1
                val next = ((cur + step) % tabs.size + tabs.size) % tabs.size
                selectTab(next)
            }
            TabSwitchMode.MRU -> stepMruCycle(forward)
        }
    }

    private fun stepMruCycle(forward: Boolean) {
        val tabs = tabsState.value.tabs
        // Build the cycle order once at the start of a cycle: MRU order first, then any
        // open tabs not yet tracked (in tab-bar order) so every tab stays reachable.
        var order = tabCycleOrder
        if (order == null) {
            val tabIds = tabs.map { it.id }
            val tracked = tabIds.filter { mruTabIds.contains(it) }.sortedBy { mruTabIds.indexOf(it) }
            val untracked = tabIds.filter { !mruTabIds.contains(it) }
            order = tracked + untracked
            tabCycleOrder = order
            tabCyclePointer = order.indexOf(tabsState.value.activeTab?.id).coerceAtLeast(0)
        }
        if (order.isEmpty()) return
        val step = if (forward) 1 else -1
        tabCyclePointer = ((tabCyclePointer + step) % order.size + order.size) % order.size
        val targetIndex = tabs.indexOfFirst { it.id == order[tabCyclePointer] }
        // Move selection without reordering MRU; commitTabCycle() promotes the landed tab.
        if (targetIndex >= 0) tabsNavigation.selectTab(targetIndex)
    }

    /**
     * Commit an in-progress MRU cycle (called when the cycling modifier is released):
     * promote the landed tab to the front of the MRU order and end the cycle.
     * No-op when no cycle is active (e.g. positional mode).
     */
    fun commitTabCycle() {
        tabCycleOrder ?: return
        tabCycleOrder = null
        tabsState.value.activeTab?.let { recordTabUsage(it.id) }
    }

    /**
     * Snapshot of the in-progress MRU cycle for the switcher overlay, or null when no
     * cycle is active (e.g. positional mode or after commit).
     */
    fun currentCycleOverlay(): TabCycleOverlayData? {
        val order = tabCycleOrder ?: return null
        val byId = tabsState.value.tabs.associateBy { it.id }
        val tabs = order.mapNotNull { byId[it] }
        if (tabs.isEmpty()) return null
        return TabCycleOverlayData(tabs = tabs, highlightedIndex = tabCyclePointer.coerceIn(0, tabs.size - 1))
    }

    private fun recordTabUsage(tabId: String) {
        mruTabIds.remove(tabId)
        mruTabIds.add(0, tabId)
    }

    // Move a tab from one position to another
    fun moveTab(fromIndex: Int, toIndex: Int) {
        tabsNavigation.moveTab(fromIndex, toIndex)
    }

    // Update a tab
    fun updateTab(index: Int, config: TabInfo) {
        tabsNavigation.updateTab(index, config)
    }

    // Get active tab component
    fun getActiveComponent(): TabComponentWithUI? {
        val activeTab = tabsState.value.activeTab ?: return null
        return tabComponents[activeTab.id]
    }
    
    // Get tab component by ID
    fun getComponentById(tabId: String): TabComponentWithUI? {
        return tabComponents[tabId]
    }


    // Get the currently selected tab
    fun getCurrentTab(): TabInfo? {
        return tabsState.value.activeTab
    }
    
    // Clear all tabs safely
    fun clearAllTabs() {
        // Remove tabs in reverse order to avoid index issues
        val tabCount = tabsState.value.tabs.size
        for (i in tabCount - 1 downTo 0) {
            removeTab(i)
        }
    }
    
    // Close other tabs (keep only the specified tab)
    fun closeOtherTabs(keepIndex: Int) {
        val tabs = tabsState.value.tabs
        if (keepIndex < 0 || keepIndex >= tabs.size) return
        
        // Remove tabs in reverse order to avoid index issues
        for (i in tabs.size - 1 downTo 0) {
            if (i != keepIndex) {
                removeTab(i)
            }
        }
    }
    
    // Close tabs to the right of the specified index
    fun closeTabsToRight(fromIndex: Int) {
        val tabs = tabsState.value.tabs
        if (fromIndex < 0 || fromIndex >= tabs.size - 1) return
        
        // Remove tabs from right to left to avoid index issues
        for (i in tabs.size - 1 downTo fromIndex + 1) {
            removeTab(i)
        }
    }
    
    // Close tabs to the left of the specified index
    fun closeTabsToLeft(fromIndex: Int) {
        if (fromIndex <= 0) return

        // Remove tabs from right to left to avoid index issues
        for (i in fromIndex - 1 downTo 0) {
            removeTab(i)
        }
    }

    // Close tab by URL (used for auto-closing download redirects)
    fun closeTabByUrl(url: String) {
        val tabs = tabsState.value.tabs

        // Find all tabs with matching URL (might be multiple)
        val indicesToRemove = mutableListOf<Int>()
        for (i in tabs.indices) {
            val tab = tabs[i]
            val tabUrl = when (tab) {
                is FluckTabInfo -> tab.currentUrl
                else -> null
            }

            if (tabUrl == url) {
                indicesToRemove.add(i)
                bossMainWindowPanelLogger.debug(LogCategory.UI, "Found tab to close", mapOf("index" to i))
            }
        }

        // Remove tabs in reverse order to avoid index issues
        for (i in indicesToRemove.sortedDescending()) {
            removeTab(i)
        }

        if (indicesToRemove.isNotEmpty()) {
            bossMainWindowPanelLogger.debug(LogCategory.UI, "Closed tabs", mapOf("count" to indicesToRemove.size))
        }
    }

    // Close the most recently opened tab (used for auto-closing download redirects)
    fun closeMostRecentTab() {
        val tabs = tabsState.value.tabs
        if (tabs.isNotEmpty()) {
            val lastIndex = tabs.size - 1
            bossMainWindowPanelLogger.debug(LogCategory.UI, "Closing most recent tab", mapOf("index" to lastIndex))
            removeTab(lastIndex)
        } else {
            bossMainWindowPanelLogger.debug(LogCategory.UI, "No tabs to close")
        }
    }

    /**
     * Synchronously dispose all browser tabs in this component.
     * Called when the window is closing to ensure JxBrowser instances
     * are fully closed before AWT window destruction.
     *
     * This prevents crashes caused by JxBrowser trying to access
     * disposed AWT window handles during rendering.
     */
    fun disposeAllTabsBlocking() {
        tabComponents.values.toList().forEach { component ->
            if (component is ai.rever.boss.components.plugin.tab_types.fluck.FluckTabComponent) {
                component.disposeBlocking()
            }
        }
        tabComponents.clear()
        // Destroy the per-tab lifecycles so plugin components that clean up in
        // lifecycle.onDestroy release their resources on window close too — same
        // contract as removeTab, without relying on the disposeAll() hammer below.
        tabLifecycles.values.toList().forEach { it.destroy() }
        tabLifecycles.clear()
        // Also dispose any browsers created by dynamic plugins via BrowserService
        ai.rever.boss.components.plugin.disposePluginBrowsers()
    }
}

/**
 * Convert TabInfo to TabConfig for bookmark storage
 */
private fun convertTabInfoToTabConfig(tabInfo: TabInfo): TabConfig {
    return when (tabInfo) {
        is FluckTabInfo -> TabConfig(
            type = "browser",
            title = tabInfo.title,
            url = tabInfo.url,
            faviconCacheKey = tabInfo.faviconCacheKey
        )
        is EditorTabInfo -> TabConfig(
            type = "editor",
            title = tabInfo.title,
            filePath = tabInfo.filePath
        )
        is TerminalTabInfo -> TabConfig(
            type = "terminal",
            title = tabInfo.title
        )
        is JupyterTabInfo -> TabConfig(
            type = "jupyter",
            title = tabInfo.title,
            filePath = tabInfo.filePath
        )
        else -> TabConfig(
            type = "unknown",
            title = tabInfo.title
        )
    }
}

