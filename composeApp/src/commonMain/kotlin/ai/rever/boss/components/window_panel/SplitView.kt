package ai.rever.boss.components.window_panel

import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.components.plugin.disposePluginBrowsers
import ai.rever.boss.components.model.PanelDropZones
import ai.rever.boss.components.model.TabDraggableComponent
import ai.rever.boss.components.model.TabDropResult
import ai.rever.boss.components.model.TabDropTarget
import ai.rever.boss.topofmind.ActiveTab
import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.icons.FileIcons
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.components.window_panel.components.BossResizablePanel
import ai.rever.boss.components.window_panel.components.main_window_panels.BossMainPanel
import ai.rever.boss.components.window_panel.components.main_window_panels.BossTabsComponent
import ai.rever.boss.components.window_panel.components.main_window_panels.createBossAppContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlin.random.Random
import androidx.compose.material.icons.outlined.Code
import kotlinx.coroutines.delay
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import ai.rever.boss.components.plugin.tab_types.PanelHostTabInfo
import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import ai.rever.boss.plugin.tab.jupyter.JupyterTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.window.WindowProjectStateRegistry

// Sealed class representing the split tree structure
sealed class SplitNode {
    data class Panel(
        val id: String,
        val tabsComponent: BossTabsComponent
    ) : SplitNode()
    
    data class VerticalSplit(
        val left: SplitNode,
        val right: SplitNode
    ) : SplitNode()
    
    data class HorizontalSplit(
        val top: SplitNode,
        val bottom: SplitNode
    ) : SplitNode()
}

enum class SplitOrientation {
    HORIZONTAL, // Split top/bottom
    VERTICAL    // Split left/right
}

/**
 * Represents the screen bounds of a panel in global coordinates.
 */
data class PanelBounds(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
) {
    val left: Float get() = x
    val right: Float get() = x + width
    val top: Float get() = y
    val bottom: Float get() = y + height

    val centerX: Float get() = x + width / 2
    val centerY: Float get() = y + height / 2

    /** Check if this bounds overlaps with another vertically */
    fun hasVerticalOverlapWith(other: PanelBounds): Boolean {
        return !(bottom <= other.top || top >= other.bottom)
    }

    /** Check if this bounds overlaps with another horizontally */
    fun hasHorizontalOverlapWith(other: PanelBounds): Boolean {
        return !(right <= other.left || left >= other.right)
    }
}

/**
 * Navigation direction for spatial panel navigation.
 */
enum class NavigationDirection {
    LEFT, RIGHT, UP, DOWN
}

private val splitViewLogger = BossLogger.forComponent("SplitView")

@Stable
class SplitViewState(
    internal val tabRegistry: TabRegistry,
    private val windowId: String,
    initialTabsComponent: BossTabsComponent? = null
) {
    // Root node of the split tree
    private var _rootNode = mutableStateOf<SplitNode>(
        SplitNode.Panel(
            id = "main",
            tabsComponent = initialTabsComponent ?: BossTabsComponent(createBossAppContext, tabRegistry, windowId)
        )
    )
    val rootNode: SplitNode get() = _rootNode.value
    
    // Track active panel for file operations
    private var _activePanelId = mutableStateOf("main")
    val activePanelId: String get() = _activePanelId.value
    val activePanelIdState: State<String> get() = _activePanelId

    // Track last interacted tab ID
    private var _lastInteractedTabId: String? = null

    // Track panel activation history for MOST_RECENT_ACTIVE mode in terminal link handling
    // Maintains order of recently activated panels (most recent first, limited to last 10)
    private val _panelActivationHistory = mutableListOf("main")

    // Track preserved workspace states
    private val preservedWorkspaceStates = mutableMapOf<String, PreservedWorkspaceState>()
    private var _currentWorkspaceId: String? = null
    val currentWorkspaceId: String? get() = _currentWorkspaceId

    // Data class to hold preserved state
    data class PreservedWorkspaceState(
        val rootNode: SplitNode,
        val activePanelId: String,
        val workspaceName: String = ""
    )

    // Track panel positions for spatial navigation
    /**
     * Maps panel IDs to their screen bounds.
     * Updated by RenderSplitNode via onGloballyPositioned callbacks.
     */
    private val _panelBounds = mutableStateMapOf<String, PanelBounds>()

    /**
     * Update the bounds for a specific panel.
     * Called from Compose layout during positioning.
     */
    fun updatePanelBounds(panelId: String, bounds: PanelBounds) {
        _panelBounds[panelId] = bounds
    }

    /**
     * Get the current bounds for a panel, or null if not yet positioned.
     */
    fun getPanelBounds(panelId: String): PanelBounds? {
        return _panelBounds[panelId]
    }

    /**
     * Clear bounds for a specific panel (e.g., when removed).
     */
    fun clearPanelBounds(panelId: String) {
        _panelBounds.remove(panelId)
    }

    // Debounce active panel changes to prevent rapid oscillation from spurious focus events.
    // 50ms chosen based on observed oscillation intervals (~8ms) - provides enough filtering
    // while remaining responsive to genuine user interactions.
    private val lastActivePanelChangeTime = java.util.concurrent.atomic.AtomicLong(0L)
    private val activePanelDebounceMs = 50L

    fun setActivePanel(panelId: String) {
        // Skip if already active
        if (panelId == _activePanelId.value) return

        // Debounce: ignore rapid changes (likely spurious focus events from Compose recomposition)
        // Uses AtomicLong for thread-safe timestamp comparison (see docs/THREADING.md)
        val now = System.currentTimeMillis()
        val lastChange = lastActivePanelChangeTime.get()
        if (now - lastChange < activePanelDebounceMs) return

        // Atomic update to prevent race conditions if called from multiple threads
        if (!lastActivePanelChangeTime.compareAndSet(lastChange, now)) return

        _activePanelId.value = panelId
        // Record in activation history for MOST_RECENT_ACTIVE mode
        recordPanelActivation(panelId)
    }

    /**
     * Records a panel activation in the history.
     * Moves the panel to the front of the list (most recent), removes duplicates,
     * and limits history to last 10 panels.
     */
    private fun recordPanelActivation(panelId: String) {
        _panelActivationHistory.remove(panelId)
        _panelActivationHistory.add(0, panelId)
        // Limit to last 10 panels to avoid unbounded growth
        while (_panelActivationHistory.size > 10) {
            _panelActivationHistory.removeAt(_panelActivationHistory.size - 1)
        }
    }
    
    fun trackTabInteraction(panelId: String, tabId: String) {
        _lastInteractedTabId = tabId
        setActivePanel(panelId)  // Now handles both active and lastInteracted
    }
    
    fun getLastInteractedTabComponent(): BossTabsComponent? {
        return findPanel(_activePanelId.value)?.tabsComponent
    }
    
    fun getActiveTabsComponent(): BossTabsComponent? {
        return findPanel(_activePanelId.value)?.tabsComponent
    }
    
    companion object {
        private val BROWSER_FILE_EXTENSIONS = setOf(
            // Images
            "png", "jpg", "jpeg", "gif", "svg", "bmp", "ico", "webp",
            // Documents
            "pdf",
            // Video
            "mp4", "webm", "mov", "avi", "mkv",
            // Audio
            "mp3", "wav", "flac", "aac", "m4a", "ogg"
        )

        fun shouldOpenInBrowser(fileName: String): Boolean {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return ext in BROWSER_FILE_EXTENSIONS
        }

        fun toFileUrl(filePath: String): String = java.io.File(filePath).toURI().toString()
    }

    fun openFileInActivePanel(filePath: String, fileName: String) {
        // Route browser-renderable files (images, PDFs) to the browser tab
        if (shouldOpenInBrowser(fileName)) {
            openUrlInActivePanel(toFileUrl(filePath), fileName)
            return
        }

        // Route .ipynb to the notebook editor — but only when the jupyter-notebook
        // plugin is actually registered. If it isn't, fall through to the code editor
        // rather than creating an unrenderable/blank notebook tab.
        if (fileName.substringAfterLast('.', "").equals("ipynb", ignoreCase = true) &&
            tabRegistry.isRegistered(JupyterTabInfo.TYPE_ID)
        ) {
            openNotebookTab(filePath, fileName)
            return
        }

        openFileInEditorTab(filePath, fileName)
    }

    /**
     * Open a `.ipynb` file as a Jupyter notebook tab. Mirrors
     * [openFileInEditorTab]'s dedupe-then-add behavior, but creates a
     * [JupyterTabInfo] (rendered by the jupyter-notebook plugin).
     */
    fun openNotebookTab(filePath: String, fileName: String) {
        val activeComponent = getActiveTabsComponent() ?: return

        findPanelWithNotebookTab(filePath)?.let { (panelId, component, tabIndex) ->
            component.selectTab(tabIndex)
            setActivePanel(panelId)
            return
        }

        val notebookTab = JupyterTabInfo.create(filePath, title = fileName)
        activeComponent.addTab(notebookTab).takeIf { it >= 0 }?.let {
            activeComponent.selectTab(it)
        }
    }

    private fun findPanelWithNotebookTab(filePath: String): PanelTabMatch? =
        findPanelWithTabMatching { tab -> tab is JupyterTabInfo && tab.filePath == filePath }

    /** Find the first panel containing a tab that satisfies [predicate]. */
    private fun findPanelWithTabMatching(predicate: (TabInfo) -> Boolean): PanelTabMatch? {
        getAllPanels().forEach { panel ->
            val tabIndex = panel.tabsComponent.tabsState.value.tabs.indexOfFirst(predicate)
            if (tabIndex >= 0) {
                return PanelTabMatch(panel.id, panel.tabsComponent, tabIndex)
            }
        }
        return null
    }

    /**
     * Force-open a file in the code editor, bypassing smart file routing.
     * Used by "Open With > Editor" context menu action.
     */
    fun openFileInEditorTab(filePath: String, fileName: String) {
        val activeComponent = getActiveTabsComponent() ?: return

        // Check if file is already open in an editor tab in any panel
        findPanelWithEditorTab(filePath)?.let { (panelId, component, tabIndex) ->
            component.selectTab(tabIndex)
            setActivePanel(panelId)
            return
        }

        // File not open, create new tab in active panel
        val fileIconInfo = FileIcons.forFile(fileName)
        val editorTab = EditorTabInfo(
            id = "editor-${Random.nextLong()}",
            typeId = ai.rever.boss.components.registery.TabTypeId("editor"),
            title = fileName,
            icon = fileIconInfo.icon,
            tabIcon = ai.rever.boss.plugin.api.TabIcon.Vector(fileIconInfo.icon, fileIconInfo.color),
            filePath = filePath
        )
        activeComponent.addTab(editorTab).takeIf { it >= 0 }?.let {
            activeComponent.selectTab(it)
        }
    }

    /**
     * Open a URL in the active panel
     *
     * If the URL is already open in any panel, switches to that tab.
     * Otherwise, creates a new Fluck browser tab in the active panel.
     * If no active panel exists (app just started), uses the first available panel.
     *
     * @param url The URL to open
     * @param title Initial title for the tab
     */
    fun openUrlInActivePanel(url: String, title: String, forceNewTab: Boolean = false) {
        val activeComponent = getActiveTabsComponent()

        // If no active component, this is likely the first URL on app startup
        // Find any available panel to add the tab to
        if (activeComponent == null) {
            // Try to get first available panel
            val firstPanel = getAllPanels().firstOrNull()
            if (firstPanel == null) {
                splitViewLogger.error(LogCategory.UI, "No panels available to create tab")
                return
            }

            val component = firstPanel.tabsComponent

            // Create tab in first available panel
            val fluckTab = FluckTabInfo(
                id = "fluck-${Random.nextLong()}",
                typeId = TabTypeId("fluck"),
                _title = title,
                url = url
            )

            val tabIndex = component.addTab(fluckTab)
            if (tabIndex >= 0) {
                component.selectTab(tabIndex)
                setActivePanel(firstPanel.id)
            } else {
                splitViewLogger.error(LogCategory.UI, "Failed to add tab to panel")
            }
            return
        }

        // Check if URL is already open in any panel (skip if forceNewTab is true)
        if (!forceNewTab) {
            findPanelWithUrl(url)?.let { (panelId, component) ->
                component.tabsState.value.tabs
                    .indexOfFirst { tab ->
                        tab is FluckTabInfo &&
                        tab.currentUrl == url  // Only check current URL to avoid focusing tabs that navigated away
                    }
                    .takeIf { it >= 0 }
                    ?.let { tabIndex ->
                        component.selectTab(tabIndex)
                        setActivePanel(panelId)
                    }
                return
            }
        }

        // URL not open, create new Fluck tab in active panel
        val fluckTab = FluckTabInfo(
            id = "fluck-${Random.nextLong()}",
            typeId = TabTypeId("fluck"),
            _title = title,
            url = url
        )
        activeComponent.addTab(fluckTab).takeIf { it >= 0 }?.let {
            activeComponent.selectTab(it)
        }
    }

    /**
     * Open a terminal tab in the active panel
     *
     * Creates a new terminal tab in the active panel.
     * If no active panel exists (app just started), uses the first available panel.
     *
     * @param command Optional initial command to run in the terminal
     * @param workingDirectory Optional working directory for the terminal (overrides project path)
     */
    fun openTerminalInActivePanel(command: String? = null, workingDirectory: String? = null) {
        val activeComponent = getActiveTabsComponent()

        // Use provided working directory, or fall back to project path
        val projectPath = WindowProjectStateRegistry.get(windowId)?.selectedProject?.value?.path ?: ""
        val terminalWorkingDir = workingDirectory ?: projectPath.ifEmpty { null }

        // If no active component, this is likely the first terminal on app startup
        // Find any available panel to add the tab to
        if (activeComponent == null) {
            // Try to get first available panel
            val firstPanel = getAllPanels().firstOrNull()
            if (firstPanel == null) {
                splitViewLogger.error(LogCategory.UI, "No panels available to create terminal tab")
                return
            }

            val component = firstPanel.tabsComponent

            // Create terminal tab in first available panel
            val terminalTab = TerminalTabInfo(
                id = "terminal-${System.currentTimeMillis()}",
                typeId = TabTypeId("terminal"),
                title = if (command != null) "Terminal: $command" else "Terminal",
                initialCommand = command,
                workingDirectory = terminalWorkingDir
            )

            val tabIndex = component.addTab(terminalTab)
            if (tabIndex >= 0) {
                component.selectTab(tabIndex)
                setActivePanel(firstPanel.id)
                splitViewLogger.debug(LogCategory.UI, "Terminal tab created in first panel", if (command != null) mapOf("command" to command) else emptyMap())
            } else {
                splitViewLogger.error(LogCategory.UI, "Failed to add terminal tab to panel")
            }
            return
        }

        // Create new terminal tab in active panel
        val terminalTab = TerminalTabInfo(
            id = "terminal-${System.currentTimeMillis()}",
            typeId = TabTypeId("terminal"),
            title = if (command != null) "Terminal: $command" else "Terminal",
            initialCommand = command,
            workingDirectory = terminalWorkingDir
        )

        val tabIndex = activeComponent.addTab(terminalTab)
        if (tabIndex >= 0) {
            activeComponent.selectTab(tabIndex)
            splitViewLogger.debug(LogCategory.UI, "Terminal tab created", if (command != null) mapOf("command" to command) else emptyMap())
        } else {
            splitViewLogger.error(LogCategory.UI, "Failed to create terminal tab")
        }
    }

    /**
     * Finds the panel containing a tab with the given ID by recursively searching the split tree.
     * Returns null if no panel contains a tab with that ID.
     */
    private fun findPanelContainingTab(tabId: String): SplitNode.Panel? {
        fun searchNode(node: SplitNode): SplitNode.Panel? = when (node) {
            is SplitNode.Panel -> {
                if (node.tabsComponent.tabsState.value.tabs.any { it.id == tabId }) {
                    node
                } else {
                    null
                }
            }
            is SplitNode.VerticalSplit -> {
                searchNode(node.left) ?: searchNode(node.right)
            }
            is SplitNode.HorizontalSplit -> {
                searchNode(node.top) ?: searchNode(node.bottom)
            }
        }
        return searchNode(_rootNode.value)
    }

    fun splitPanel(
        panelId: String,
        orientation: SplitOrientation,
        tabToMove: TabInfo? = null,
        detachedTab: BossTabsComponent.DetachedTab? = null
    ): String {
        // Mutually exclusive by contract: passing both would adopt the live component AND
        // add a copy of the same tab, duplicating it across the two panels.
        require(tabToMove == null || detachedTab == null) {
            "splitPanel: pass either tabToMove (copy) or detachedTab (move), not both"
        }
        if (findPanel(panelId) == null) {
            // A detached tab is already out of its source panel: silently returning would
            // lose it from the UI while its live component (e.g. a Chromium process) keeps
            // running — the leak this mechanism exists to eliminate. Rescue it into the
            // first available panel instead ("main" always exists).
            detachedTab?.let { detached ->
                splitViewLogger.warn(LogCategory.UI, "splitPanel target panel missing; rescuing detached tab", mapOf(
                    "targetPanelId" to panelId,
                    "tabId" to detached.config.id
                ))
                val rescue = getAllPanels().firstOrNull()
                if (rescue != null) {
                    val index = rescue.tabsComponent.adoptTab(detached)
                    if (index >= 0) rescue.tabsComponent.selectTab(index)
                    setActivePanel(rescue.id)
                } else {
                    detached.destroy()
                }
            }
            return panelId
        }

        // Create new panel with copied tab
        val newPanelId = "split-${Random.nextLong()}"
        val newComponent = BossTabsComponent(createBossAppContext, tabRegistry, windowId)

        // A tab detached from another panel (cross-panel edge drag) transfers its live
        // component instance — no copy, no reload, and nothing left behind to leak.
        detachedTab?.let { detached ->
            val newIndex = newComponent.adoptTab(detached)
            if (newIndex >= 0) newComponent.selectTab(newIndex)
        }

        // Copy tab if specified
        tabToMove?.let { tab ->
            // For FluckTabInfo, get fresh instance from source panel to get latest navigation state
            // This ensures we copy the current URL, not the stale URL from when drag started
            val freshTab = if (tab is FluckTabInfo) {
                // Find the panel containing this tab
                val sourcePanel = findPanelContainingTab(tab.id)
                val foundTab = sourcePanel?.tabsComponent?.tabsState?.value?.tabs?.find { it.id == tab.id } as? FluckTabInfo
                foundTab ?: tab  // Fallback to provided tab if not found
            } else {
                tab
            }

            val copiedTab = when (freshTab) {
                is EditorTabInfo ->
                    freshTab.copy(id = "editor-${Random.nextLong()}")
                is FluckTabInfo ->
                    freshTab.copy(
                        id = "fluck-${Random.nextLong()}",
                        url = freshTab.currentUrl, // Set initial URL to current URL (Issue #406)
                        _currentUrl = freshTab.currentUrl, // Preserve the current URL
                        navigationHistory = freshTab.navigationHistory.toMutableList() // Deep copy the history
                    )
                is TerminalTabInfo ->
                    freshTab.copy(id = "terminal-${Random.nextLong()}")
                is JupyterTabInfo ->
                    freshTab.copy(id = JupyterTabInfo.newId())
                // PanelHostTabInfo deliberately falls through uncopied: its id is fixed
                // ("panel-tab:<panelId>") and it renders the panel's single cached component
                // instance, so a copy would compose that instance in two tabs at once.
                // Splitting MOVES it instead — see below.
                else -> freshTab
            }

            val newIndex = newComponent.addTab(copiedTab)
            if (newIndex >= 0) newComponent.selectTab(newIndex)

            // Move semantics for panel-host tabs: remove the original (still present for
            // context-menu Split Right/Down and same-panel edge drops; the cross-panel drag
            // handler removed it before calling splitPanel) only AFTER the new hosting tab
            // was created, so the hosted-as-tab count never transiently drops to zero and
            // unhides the sidebar panel. newComponent isn't in the split tree yet, so the
            // search below can only ever find the original.
            if (copiedTab is PanelHostTabInfo && newIndex >= 0) {
                findPanelContainingTab(copiedTab.id)?.tabsComponent?.removeTabById(copiedTab.id)
            }
        }
        
        // Create new panel node
        val newPanelNode = SplitNode.Panel(newPanelId, newComponent)
        
        // Replace the panel node with a split node
        _rootNode.value = replacePanelWithSplit(
            _rootNode.value,
            panelId,
            orientation,
            newPanelNode
        )
        
        return newPanelId
    }
    
    private fun replacePanelWithSplit(
        node: SplitNode,
        targetPanelId: String,
        orientation: SplitOrientation,
        newPanel: SplitNode.Panel
    ): SplitNode {
        return when (node) {
            is SplitNode.Panel -> {
                if (node.id == targetPanelId) {
                    // Replace this panel with a split
                    when (orientation) {
                        SplitOrientation.VERTICAL -> SplitNode.VerticalSplit(
                            left = node,  // Original panel keeps all tabs
                            right = newPanel
                        )
                        SplitOrientation.HORIZONTAL -> SplitNode.HorizontalSplit(
                            top = node,   // Original panel keeps all tabs
                            bottom = newPanel
                        )
                    }
                } else {
                    node
                }
            }
            is SplitNode.VerticalSplit -> {
                SplitNode.VerticalSplit(
                    left = replacePanelWithSplit(node.left, targetPanelId, orientation, newPanel),
                    right = replacePanelWithSplit(node.right, targetPanelId, orientation, newPanel)
                )
            }
            is SplitNode.HorizontalSplit -> {
                SplitNode.HorizontalSplit(
                    top = replacePanelWithSplit(node.top, targetPanelId, orientation, newPanel),
                    bottom = replacePanelWithSplit(node.bottom, targetPanelId, orientation, newPanel)
                )
            }
        }
    }

    fun closePanel(panelId: String) {
        // Don't close the main panel if it's the only one
        if (panelId == "main" && getAllPanels().size == 1) return

        // First, dispose all tabs in the panel being closed
        findPanel(panelId)?.let { panel ->
            panel.tabsComponent.clearAllTabs()
        }

        _rootNode.value = removePanel(_rootNode.value, panelId)

        // Clean up activation history to prevent accumulation of deleted panel IDs
        _panelActivationHistory.remove(panelId)

        // If active panel was closed, switch to first available
        if (_activePanelId.value == panelId) {
            getAllPanels().firstOrNull()?.let {
                _activePanelId.value = it.id
            }
        }
    }
    
    private fun removePanel(node: SplitNode, targetPanelId: String): SplitNode {
        return when (node) {
            is SplitNode.Panel -> {
                // If this is the panel to remove, return a marker that it should be removed
                if (node.id == targetPanelId) {
                    // Return a special marker - we'll handle this in the parent
                    node // For now, return the node and let parent handle it
                } else {
                    node
                }
            }
            is SplitNode.VerticalSplit -> {
                // Check if the target panel is in left subtree
                if (node.left is SplitNode.Panel && node.left.id == targetPanelId) {
                    // Left panel should be removed, return right
                    node.right
                } else if (node.right is SplitNode.Panel && node.right.id == targetPanelId) {
                    // Right panel should be removed, return left
                    node.left
                } else {
                    // Recursively check deeper in the tree
                    val newLeft = if (isPanelInNode(node.left, targetPanelId)) {
                        removePanel(node.left, targetPanelId)
                    } else {
                        node.left
                    }
                    val newRight = if (isPanelInNode(node.right, targetPanelId)) {
                        removePanel(node.right, targetPanelId)
                    } else {
                        node.right
                    }
                    
                    // If either side is now empty, promote the other side
                    when {
                        newLeft === node.left && newRight === node.right -> node // No change
                        else -> SplitNode.VerticalSplit(newLeft, newRight)
                    }
                }
            }
            is SplitNode.HorizontalSplit -> {
                // Check if the target panel is in top subtree
                if (node.top is SplitNode.Panel && node.top.id == targetPanelId) {
                    // Top panel should be removed, return bottom
                    node.bottom
                } else if (node.bottom is SplitNode.Panel && node.bottom.id == targetPanelId) {
                    // Bottom panel should be removed, return top
                    node.top
                } else {
                    // Recursively check deeper in the tree
                    val newTop = if (isPanelInNode(node.top, targetPanelId)) {
                        removePanel(node.top, targetPanelId)
                    } else {
                        node.top
                    }
                    val newBottom = if (isPanelInNode(node.bottom, targetPanelId)) {
                        removePanel(node.bottom, targetPanelId)
                    } else {
                        node.bottom
                    }
                    
                    // If either side is now empty, promote the other side
                    when {
                        newTop === node.top && newBottom === node.bottom -> node // No change
                        else -> SplitNode.HorizontalSplit(newTop, newBottom)
                    }
                }
            }
        }
    }
    
    private fun isPanelInNode(node: SplitNode, panelId: String): Boolean {
        return when (node) {
            is SplitNode.Panel -> node.id == panelId
            is SplitNode.VerticalSplit -> isPanelInNode(node.left, panelId) || isPanelInNode(node.right, panelId)
            is SplitNode.HorizontalSplit -> isPanelInNode(node.top, panelId) || isPanelInNode(node.bottom, panelId)
        }
    }
    
    /**
     * Find a panel by its ID.
     * Returns null if no panel with the given ID exists.
     */
    internal fun findPanel(panelId: String): SplitNode.Panel? {
        return findPanelInNode(_rootNode.value, panelId)
    }
    
    private fun findPanelInNode(node: SplitNode, panelId: String): SplitNode.Panel? {
        return when (node) {
            is SplitNode.Panel -> if (node.id == panelId) node else null
            is SplitNode.VerticalSplit -> 
                findPanelInNode(node.left, panelId) ?: findPanelInNode(node.right, panelId)
            is SplitNode.HorizontalSplit -> 
                findPanelInNode(node.top, panelId) ?: findPanelInNode(node.bottom, panelId)
        }
    }
    
    private fun findPanelWithFile(filePath: String): Pair<String, BossTabsComponent>? {
        val fileUrl = toFileUrl(filePath)
        getAllPanels().forEach { panel ->
            if (panel.tabsComponent.tabsState.value.tabs.any { tab ->
                (tab is EditorTabInfo && tab.filePath == filePath) ||
                (tab is FluckTabInfo && tab.currentUrl == fileUrl)
            }) {
                return panel.id to panel.tabsComponent
            }
        }
        return null
    }

    private data class PanelTabMatch(
        val panelId: String,
        val component: BossTabsComponent,
        val tabIndex: Int
    )

    /**
     * Find the panel that contains an editor tab for the given file path.
     * Unlike findPanelWithFile, this only matches EditorTabInfo (not browser tabs).
     */
    private fun findPanelWithEditorTab(filePath: String): PanelTabMatch? =
        findPanelWithTabMatching { tab -> tab is EditorTabInfo && tab.filePath == filePath }

    /**
     * Find the panel that contains a tab with the given URL
     *
     * @param url The URL to search for
     * @return Pair of panel ID and BossTabsComponent if found, null otherwise
     */
    private fun findPanelWithUrl(url: String): Pair<String, BossTabsComponent>? {
        getAllPanels().forEach { panel ->
            if (panel.tabsComponent.tabsState.value.tabs.any { tab ->
                tab is FluckTabInfo &&
                tab.currentUrl == url  // Only check current URL to avoid focusing tabs that navigated away
            }) {
                return panel.id to panel.tabsComponent
            }
        }
        return null
    }

    fun getAllPanels(): List<SplitNode.Panel> {
        return getAllPanelsInNode(_rootNode.value)
    }

    private fun getAllPanelsInNode(node: SplitNode): List<SplitNode.Panel> {
        return when (node) {
            is SplitNode.Panel -> listOf(node)
            is SplitNode.VerticalSplit ->
                getAllPanelsInNode(node.left) + getAllPanelsInNode(node.right)
            is SplitNode.HorizontalSplit ->
                getAllPanelsInNode(node.top) + getAllPanelsInNode(node.bottom)
        }
    }

    /**
     * Synchronously dispose all browser tabs in all panels.
     * Called when the window is closing to ensure JxBrowser instances
     * are fully closed before AWT window destruction.
     *
     * This is critical for preventing crashes when closing windows:
     * - JxBrowser browsers must be disposed BEFORE the AWT window is destroyed
     * - The dispose is synchronous (blocking) to ensure completion before window destruction
     * - Prevents EXC_BAD_ACCESS crashes in getWindowHandle native code
     */
    fun disposeAllBrowsersBlocking() {
        getAllPanels().forEach { panel ->
            panel.tabsComponent.disposeAllTabsBlocking()
        }
        disposePluginBrowsers(windowId)
    }

    /**
     * Check if any splits exist (more than one panel).
     */
    fun hasSplits(): Boolean = getAllPanels().size > 1

    /**
     * Check if any tabs exist in any panel.
     */
    fun hasTabs(): Boolean = getAllPanels().any { panel ->
        panel.tabsComponent.tabsState.value.tabs.isNotEmpty()
    }

    /**
     * Get the first panel that is not the currently active panel.
     * Useful for opening content in an existing split.
     */
    fun getOtherPanel(): SplitNode.Panel? {
        val allPanels = getAllPanels()
        return allPanels.firstOrNull { it.id != activePanelId }
    }


    /**
     * Get the most recently active panel that is not the specified panel.
     * Uses panel activation history to prefer panels the user recently interacted with.
     * Useful for opening content in a split other than where the action originated.
     *
     * @param excludePanelId The panel ID to exclude from the search
     * @return The most recently active panel with a different ID, or null if only one panel exists
     */
    fun getOtherPanelExcluding(excludePanelId: String): SplitNode.Panel? {
        val allPanels = getAllPanels()
        val allPanelIds = allPanels.map { it.id }.toSet()

        // Find the most recently activated panel (excluding the specified one) that still exists
        for (panelId in _panelActivationHistory) {
            if (panelId != excludePanelId && panelId in allPanelIds) {
                return allPanels.firstOrNull { it.id == panelId }
            }
        }

        // Fallback: return the first available panel that isn't the excluded one
        return allPanels.firstOrNull { it.id != excludePanelId }
    }

    /**
     * Get the first panel that is not the specified panel (FIRST_AVAILABLE mode).
     * Unlike getOtherPanelExcluding which uses activation history, this simply
     * returns the first panel in the tree traversal order.
     *
     * @param excludePanelId The panel ID to exclude from the search
     * @return The first available panel with a different ID, or null if only one panel exists
     */
    fun getFirstOtherPanelExcluding(excludePanelId: String): SplitNode.Panel? {
        return getAllPanels().firstOrNull { it.id != excludePanelId }
    }

    /**
     * Find the panel that contains a tab with the given ID.
     * Issue #347: Used for runner terminal management.
     *
     * @param tabId The tab ID to search for
     * @return The panel containing the tab, or null if not found
     */
    fun findPanelWithTab(tabId: String): SplitNode.Panel? {
        return getAllPanels().find { panel ->
            panel.tabsComponent.tabsState.value.tabs.any { it.id == tabId }
        }
    }

    // Spatial Navigation Methods

    /**
     * Find the best panel to navigate to in the given direction from the active panel.
     * Returns null if no suitable panel exists in that direction.
     */
    fun findPanelInDirection(direction: NavigationDirection): SplitNode.Panel? {
        val currentBounds = getPanelBounds(activePanelId) ?: return null
        val allPanels = getAllPanels().filter { it.id != activePanelId }

        return when (direction) {
            NavigationDirection.LEFT -> findClosestPanelToLeft(currentBounds, allPanels)
            NavigationDirection.RIGHT -> findClosestPanelToRight(currentBounds, allPanels)
            NavigationDirection.UP -> findClosestPanelAbove(currentBounds, allPanels)
            NavigationDirection.DOWN -> findClosestPanelBelow(currentBounds, allPanels)
        }
    }

    /**
     * Find the closest panel to the left of the current bounds.
     * Prioritizes panels with maximum vertical overlap.
     */
    private fun findClosestPanelToLeft(
        currentBounds: PanelBounds,
        allPanels: List<SplitNode.Panel>
    ): SplitNode.Panel? {
        data class Candidate(
            val panel: SplitNode.Panel,
            val bounds: PanelBounds,
            val overlap: Float,
            val distance: Float
        )

        val candidates = allPanels.mapNotNull { panel ->
            val bounds = getPanelBounds(panel.id) ?: return@mapNotNull null

            // Panel must be to the left (right edge <= current left edge, with small tolerance)
            if (bounds.right > currentBounds.left + 1f) return@mapNotNull null

            // Calculate vertical overlap
            val overlapTop = maxOf(currentBounds.top, bounds.top)
            val overlapBottom = minOf(currentBounds.bottom, bounds.bottom)
            val overlap = maxOf(0f, overlapBottom - overlapTop)

            // Must have some vertical overlap to be reachable
            if (overlap <= 0f) return@mapNotNull null

            // Calculate horizontal distance (gap between panels)
            val distance = currentBounds.left - bounds.right

            Candidate(panel, bounds, overlap, distance)
        }

        if (candidates.isEmpty()) return null

        // Sort by overlap (descending), then by distance (ascending)
        val best = candidates.maxByOrNull { candidate ->
            candidate.overlap * 1000f - candidate.distance
        }!!

        return best.panel
    }

    /**
     * Find the closest panel to the right of the current bounds.
     */
    private fun findClosestPanelToRight(
        currentBounds: PanelBounds,
        allPanels: List<SplitNode.Panel>
    ): SplitNode.Panel? {
        data class Candidate(
            val panel: SplitNode.Panel,
            val bounds: PanelBounds,
            val overlap: Float,
            val distance: Float
        )

        val candidates = allPanels.mapNotNull { panel ->
            val bounds = getPanelBounds(panel.id) ?: return@mapNotNull null

            // Panel must be to the right (left edge >= current right edge)
            if (bounds.left < currentBounds.right - 1f) return@mapNotNull null

            // Calculate vertical overlap
            val overlapTop = maxOf(currentBounds.top, bounds.top)
            val overlapBottom = minOf(currentBounds.bottom, bounds.bottom)
            val overlap = maxOf(0f, overlapBottom - overlapTop)

            if (overlap <= 0f) return@mapNotNull null

            // Calculate horizontal distance
            val distance = bounds.left - currentBounds.right

            Candidate(panel, bounds, overlap, distance)
        }

        if (candidates.isEmpty()) return null

        val best = candidates.maxByOrNull { candidate ->
            candidate.overlap * 1000f - candidate.distance
        }!!

        return best.panel
    }

    /**
     * Find the closest panel above the current bounds.
     * Prioritizes panels with maximum horizontal overlap.
     */
    private fun findClosestPanelAbove(
        currentBounds: PanelBounds,
        allPanels: List<SplitNode.Panel>
    ): SplitNode.Panel? {
        data class Candidate(
            val panel: SplitNode.Panel,
            val bounds: PanelBounds,
            val overlap: Float,
            val distance: Float
        )

        val candidates = allPanels.mapNotNull { panel ->
            val bounds = getPanelBounds(panel.id) ?: return@mapNotNull null

            // Panel must be above (bottom edge <= current top edge)
            if (bounds.bottom > currentBounds.top + 1f) return@mapNotNull null

            // Calculate horizontal overlap
            val overlapLeft = maxOf(currentBounds.left, bounds.left)
            val overlapRight = minOf(currentBounds.right, bounds.right)
            val overlap = maxOf(0f, overlapRight - overlapLeft)

            if (overlap <= 0f) return@mapNotNull null

            // Calculate vertical distance
            val distance = currentBounds.top - bounds.bottom

            Candidate(panel, bounds, overlap, distance)
        }

        if (candidates.isEmpty()) return null

        val best = candidates.maxByOrNull { candidate ->
            candidate.overlap * 1000f - candidate.distance
        }!!

        return best.panel
    }

    /**
     * Find the closest panel below the current bounds.
     */
    private fun findClosestPanelBelow(
        currentBounds: PanelBounds,
        allPanels: List<SplitNode.Panel>
    ): SplitNode.Panel? {
        data class Candidate(
            val panel: SplitNode.Panel,
            val bounds: PanelBounds,
            val overlap: Float,
            val distance: Float
        )

        val candidates = allPanels.mapNotNull { panel ->
            val bounds = getPanelBounds(panel.id) ?: return@mapNotNull null

            // Panel must be below (top edge >= current bottom edge)
            if (bounds.top < currentBounds.bottom - 1f) return@mapNotNull null

            // Calculate horizontal overlap
            val overlapLeft = maxOf(currentBounds.left, bounds.left)
            val overlapRight = minOf(currentBounds.right, bounds.right)
            val overlap = maxOf(0f, overlapRight - overlapLeft)

            if (overlap <= 0f) return@mapNotNull null

            // Calculate vertical distance
            val distance = bounds.top - currentBounds.bottom

            Candidate(panel, bounds, overlap, distance)
        }

        if (candidates.isEmpty()) return null

        val best = candidates.maxByOrNull { candidate ->
            candidate.overlap * 1000f - candidate.distance
        }!!

        return best.panel
    }

    fun checkAndCloseEmptyPanels() {
        // First, count how many panels we have in total
        val allPanels = getAllPanels()
        
        // If we only have one panel, don't close it regardless of tabs
        if (allPanels.size <= 1) return
        
        // Find all empty panels
        val emptyPanels = allPanels.filter { panel ->
            panel.tabsComponent.tabsState.value.tabs.isEmpty()
        }
        
        // If all panels are empty, keep the main one
        if (emptyPanels.size == allPanels.size) {
            emptyPanels.filter { it.id != "main" }.forEach { panel ->
                closePanel(panel.id)
            }
        } else {
            // Close all empty panels
            emptyPanels.forEach { panel ->
                closePanel(panel.id)
            }
        }
    }
    
    fun clearAllPanels() {
        // Reset to single main panel
        val mainComponent = BossTabsComponent(createBossAppContext, tabRegistry, windowId)
        _rootNode.value = SplitNode.Panel(
            id = "main",
            tabsComponent = mainComponent
        )
        _activePanelId.value = "main"
    }
    
    fun preserveCurrentState(workspaceId: String, workspaceName: String = "") {
        // Save current state before switching
        _currentWorkspaceId?.let { currentId ->
            preservedWorkspaceStates[currentId] = PreservedWorkspaceState(
                rootNode = _rootNode.value,
                activePanelId = _activePanelId.value,
                workspaceName = workspaceName
            )
        }
        _currentWorkspaceId = workspaceId
    }
    
    fun restorePreservedState(workspaceId: String): Boolean {
        // Check if we have a preserved state for this workspace
        val preservedState = preservedWorkspaceStates[workspaceId]
        return if (preservedState != null) {
            // Restore the preserved state
            _rootNode.value = preservedState.rootNode
            _activePanelId.value = preservedState.activePanelId
            _currentWorkspaceId = workspaceId
            true
        } else {
            _currentWorkspaceId = workspaceId
            false
        }
    }
    
    fun getPanelTabsComponent(panelId: String): BossTabsComponent? {
        return findPanel(panelId)?.tabsComponent
    }

    /**
     * Get a panel by its ID.
     */
    fun getPanel(panelId: String): SplitNode.Panel? {
        return findPanel(panelId)
    }
    
    fun selectTabInPanel(tabId: String, panelId: String) {
        val panel = findPanel(panelId)
        if (panel != null) {
            // Set the panel as active
            setActivePanel(panelId)
            
            // Find the tab index and select it
            val tabsComponent = panel.tabsComponent
            val tabs = tabsComponent.tabsState.value.tabs
            val tabIndex = tabs.indexOfFirst { it.id == tabId }
            
            if (tabIndex >= 0) {
                tabsComponent.selectTab(tabIndex)
            }
        }
    }
    
    fun collectAllActiveFluckTabs(windowId: String = "unknown"): List<ActiveTab> {
        val result = mutableListOf<ActiveTab>()
        val seenTabIds = mutableSetOf<String>()

        // Collect from current state
        _currentWorkspaceId?.let { workspaceId ->
            // Get the actual workspace name from preserved states or use a default
            val workspaceName = preservedWorkspaceStates[workspaceId]?.workspaceName
                ?: when (workspaceId) {
                    "last-session" -> "Last Session"
                    else -> "Current Workspace"
                }

            getAllPanels().forEach { panel ->
                panel.tabsComponent.tabsState.value.tabs.forEach { tab ->
                    if (!seenTabIds.contains(tab.id) && (tab is FluckTabInfo || tab.typeId.typeId == "fluck")) {
                        result.add(
                            ActiveTab(
                                tabInfo = tab,
                                workspaceId = workspaceId,
                                workspaceName = workspaceName,
                                panelId = panel.id,
                                windowId = windowId
                            )
                        )
                        seenTabIds.add(tab.id)
                    }
                }
            }
        }

        // Collect from preserved states (only if not already in current state)
        preservedWorkspaceStates.forEach { (workspaceId, state) ->
            if (workspaceId != _currentWorkspaceId) {
                collectFluckTabsFromNode(state.rootNode, workspaceId, state.workspaceName, windowId, result, seenTabIds)
            }
        }

        return result
    }
    
    /**
     * Cleanup preserved state for a deleted workspace
     */
    fun cleanupDeletedWorkspace(workspaceId: String) {
        preservedWorkspaceStates.remove(workspaceId)
    }
    
    /**
     * Cleanup preserved states for workspaces that no longer exist
     */
    fun cleanupDeletedWorkspaces(existingWorkspaceIds: Set<String>) {
        val idsToRemove = preservedWorkspaceStates.keys.filter { workspaceId ->
            // Keep special workspaces like "last-session" and only remove user workspaces
            !existingWorkspaceIds.contains(workspaceId) && workspaceId != "last-session"
        }
        
        idsToRemove.forEach { workspaceId ->
            preservedWorkspaceStates.remove(workspaceId)
        }
    }
    
    fun collectAllActiveTabs(workspaceManager: ai.rever.boss.components.workspaces.WorkspaceManager? = null, windowId: String = "unknown"): List<ActiveTab> {
        val result = mutableListOf<ActiveTab>()
        val seenTabIds = mutableSetOf<String>()
        val seenConfigIds = mutableSetOf<String>()
        
        // Helper function to get proper workspace name
        fun getWorkspaceName(workspaceId: String): String {
            return workspaceManager?.workspaces?.value?.find { it.id == workspaceId }?.name
                ?: preservedWorkspaceStates[workspaceId]?.workspaceName
                ?: when (workspaceId) {
                    "last-session" -> "Last Session"
                    else -> "Workspace $workspaceId"
                }
        }
        
        // Collect from current state (only if it has tabs)
        _currentWorkspaceId?.let { workspaceId ->
            val currentTabs = mutableListOf<ActiveTab>()
            
            getAllPanels().forEach { panel ->
                panel.tabsComponent.tabsState.value.tabs.forEach { tab ->
                    if (!seenTabIds.contains(tab.id)) {
                        currentTabs.add(
                            ActiveTab(
                                tabInfo = tab,
                                workspaceId = workspaceId,
                                workspaceName = getWorkspaceName(workspaceId),
                                panelId = panel.id,
                                windowId = windowId
                            )
                        )
                        seenTabIds.add(tab.id)
                    }
                }
            }
            
            // Only add current workspace if it has tabs
            if (currentTabs.isNotEmpty()) {
                result.addAll(currentTabs)
                seenConfigIds.add(workspaceId)
            }
        }
        
        // Collect from preserved states (only if not already added)
        preservedWorkspaceStates.forEach { (workspaceId, state) ->
            if (!seenConfigIds.contains(workspaceId)) {
                collectAllTabsFromNode(state.rootNode, workspaceId, getWorkspaceName(workspaceId), windowId, result, seenTabIds)
                if (result.any { it.workspaceId == workspaceId }) {
                    seenConfigIds.add(workspaceId)
                }
            }
        }
        
        return result
    }
    
    private fun collectFluckTabsFromNode(
        node: SplitNode,
        workspaceId: String,
        workspaceName: String,
        windowId: String,
        result: MutableList<ActiveTab>,
        seenTabIds: MutableSet<String>
    ) {
        when (node) {
            is SplitNode.Panel -> {
                node.tabsComponent.tabsState.value.tabs.forEach { tab ->
                    if (!seenTabIds.contains(tab.id) && (tab is FluckTabInfo || tab.typeId.typeId == "fluck")) {
                        result.add(
                            ActiveTab(
                                tabInfo = tab,
                                workspaceId = workspaceId,
                                workspaceName = workspaceName,
                                panelId = node.id,
                                windowId = windowId
                            )
                        )
                        seenTabIds.add(tab.id)
                    }
                }
            }
            is SplitNode.VerticalSplit -> {
                collectFluckTabsFromNode(node.left, workspaceId, workspaceName, windowId, result, seenTabIds)
                collectFluckTabsFromNode(node.right, workspaceId, workspaceName, windowId, result, seenTabIds)
            }
            is SplitNode.HorizontalSplit -> {
                collectFluckTabsFromNode(node.top, workspaceId, workspaceName, windowId, result, seenTabIds)
                collectFluckTabsFromNode(node.bottom, workspaceId, workspaceName, windowId, result, seenTabIds)
            }
        }
    }
    
    private fun collectAllTabsFromNode(
        node: SplitNode,
        workspaceId: String,
        workspaceName: String,
        windowId: String,
        result: MutableList<ActiveTab>,
        seenTabIds: MutableSet<String>
    ) {
        when (node) {
            is SplitNode.Panel -> {
                node.tabsComponent.tabsState.value.tabs.forEach { tab ->
                    if (!seenTabIds.contains(tab.id)) {
                        result.add(
                            ActiveTab(
                                tabInfo = tab,
                                workspaceId = workspaceId,
                                workspaceName = workspaceName,
                                panelId = node.id,
                                windowId = windowId
                            )
                        )
                        seenTabIds.add(tab.id)
                    }
                }
            }
            is SplitNode.VerticalSplit -> {
                collectAllTabsFromNode(node.left, workspaceId, workspaceName, windowId, result, seenTabIds)
                collectAllTabsFromNode(node.right, workspaceId, workspaceName, windowId, result, seenTabIds)
            }
            is SplitNode.HorizontalSplit -> {
                collectAllTabsFromNode(node.top, workspaceId, workspaceName, windowId, result, seenTabIds)
                collectAllTabsFromNode(node.bottom, workspaceId, workspaceName, windowId, result, seenTabIds)
            }
        }
    }
}

@Composable
fun rememberSplitViewState(
    tabRegistry: TabRegistry,
    windowId: String,
    initialTabsComponent: BossTabsComponent? = null
): SplitViewState {
    return remember { SplitViewState(tabRegistry, windowId, initialTabsComponent) }
}

@Composable
fun SplitViewPanel(
    splitViewState: SplitViewState,
    modifier: Modifier = Modifier,
    tabDragComponent: TabDraggableComponent? = null,
    onTabDropResult: (TabDropResult) -> Unit = {},
    onShowSettings: (() -> Unit)? = null,
    onOpenProjectDialog: (() -> Unit)? = null,
    onNewProject: (() -> Unit)? = null
) {
    Box(modifier = modifier.fillMaxSize()) {
        RenderSplitNode(
            node = splitViewState.rootNode,
            splitViewState = splitViewState,
            tabDragComponent = tabDragComponent,
            onTabDropResult = onTabDropResult,
            onShowSettings = onShowSettings,
            onOpenProjectDialog = onOpenProjectDialog,
            onNewProject = onNewProject
        )
    }
}

@Composable
private fun RenderSplitNode(
    node: SplitNode,
    splitViewState: SplitViewState,
    tabDragComponent: TabDraggableComponent? = null,
    onTabDropResult: (TabDropResult) -> Unit = {},
    onShowSettings: (() -> Unit)? = null,
    onOpenProjectDialog: (() -> Unit)? = null,
    onNewProject: (() -> Unit)? = null
) {
    when (node) {
        is SplitNode.Panel -> {
            // key() preserves panel composition identity when split tree restructures
            key(node.id) {
                // Cleanup panel bounds when panel is removed from composition
                // This prevents memory leaks in tabDragComponent's bound maps
                DisposableEffect(node.id, tabDragComponent) {
                    onDispose {
                        splitViewState.clearPanelBounds(node.id)
                        tabDragComponent?.unregisterPanel(node.id)
                    }
                }

                // Monitor this specific panel's tab count
                val tabsState = node.tabsComponent.tabsState.subscribeAsState()
                LaunchedEffect(node.id, tabsState.value.tabs.size) {
                    if (tabsState.value.tabs.isEmpty()) {
                        // Small delay to ensure state is fully updated
                        delay(50)
                        splitViewState.checkAndCloseEmptyPanels()
                    }
                }

                // Track drop target for panel drop zone highlights
                val dropTarget = tabDragComponent?.dropTarget
                val isDragging = tabDragComponent?.isDragging == true
                val draggingTab = tabDragComponent?.draggingTab

                // Capture panel position for spatial navigation and drop zones
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { coordinates ->
                            val bounds = coordinates.boundsInRoot()
                            splitViewState.updatePanelBounds(
                                panelId = node.id,
                                bounds = PanelBounds(
                                    x = bounds.left,
                                    y = bounds.top,
                                    width = bounds.width,
                                    height = bounds.height
                                )
                            )
                            // Register panel drop zones for drag system
                            if (tabDragComponent != null) {
                                val windowBounds = coordinates.boundsInWindow()
                                tabDragComponent.registerPanelDropZones(node.id, windowBounds)
                            }
                        }
                ) {
                    node.tabsComponent.BossMainPanel(
                        splitViewState = splitViewState,
                        currentPanelId = node.id,
                        tabDragComponent = tabDragComponent,
                        onTabDropResult = onTabDropResult,
                        onShowSettings = onShowSettings,
                        onOpenProjectDialog = onOpenProjectDialog,
                        onNewProject = onNewProject
                    )

                    // Show drop zone highlights when dragging over this panel
                    if (isDragging && draggingTab != null && draggingTab.sourcePanelId != node.id) {
                        PanelDropZoneOverlay(
                            panelId = node.id,
                            dropTarget = dropTarget
                        )
                    }
                }
            }
        }
        is SplitNode.VerticalSplit -> {
            BossResizablePanel(
                modifier = Modifier.fillMaxSize(),
                panel = Panel.right,
                isPanelVisible = true,
                isMainVisible = true,
                isRelative = true,
                defaultWeight = 1f,
                mainContent = {
                    RenderSplitNode(
                        node = node.left,
                        splitViewState = splitViewState,
                        tabDragComponent = tabDragComponent,
                        onTabDropResult = onTabDropResult,
                        onShowSettings = onShowSettings,
                        onOpenProjectDialog = onOpenProjectDialog,
                        onNewProject = onNewProject
                    )
                },
                sideContent = {
                    RenderSplitNode(
                        node = node.right,
                        splitViewState = splitViewState,
                        tabDragComponent = tabDragComponent,
                        onTabDropResult = onTabDropResult,
                        onShowSettings = onShowSettings,
                        onOpenProjectDialog = onOpenProjectDialog,
                        onNewProject = onNewProject
                    )
                }
            )
        }
        is SplitNode.HorizontalSplit -> {
            BossResizablePanel(
                modifier = Modifier.fillMaxSize(),
                panel = Panel.bottom,
                isPanelVisible = true,
                isMainVisible = true,
                isRelative = true,
                defaultWeight = 1f,
                mainContent = {
                    RenderSplitNode(
                        node = node.top,
                        splitViewState = splitViewState,
                        tabDragComponent = tabDragComponent,
                        onTabDropResult = onTabDropResult,
                        onShowSettings = onShowSettings,
                        onOpenProjectDialog = onOpenProjectDialog,
                        onNewProject = onNewProject
                    )
                },
                sideContent = {
                    RenderSplitNode(
                        node = node.bottom,
                        splitViewState = splitViewState,
                        tabDragComponent = tabDragComponent,
                        onTabDropResult = onTabDropResult,
                        onShowSettings = onShowSettings,
                        onOpenProjectDialog = onOpenProjectDialog,
                        onNewProject = onNewProject
                    )
                }
            )
        }
    }
}

/**
 * Overlay that shows drop zone highlights on panel edges during drag operations.
 */
@Composable
private fun PanelDropZoneOverlay(
    panelId: String,
    dropTarget: TabDropTarget?
) {
    // Check which zone is highlighted
    val isLeftHighlighted = dropTarget is TabDropTarget.SplitPanel &&
        dropTarget.panelId == panelId &&
        dropTarget.orientation == SplitOrientation.VERTICAL

    val isRightHighlighted = isLeftHighlighted // Same condition for vertical split

    val isTopHighlighted = dropTarget is TabDropTarget.SplitPanel &&
        dropTarget.panelId == panelId &&
        dropTarget.orientation == SplitOrientation.HORIZONTAL

    val isBottomHighlighted = isTopHighlighted // Same condition for horizontal split

    val isCenterHighlighted = dropTarget is TabDropTarget.ExistingPanel &&
        dropTarget.panelId == panelId

    Box(modifier = Modifier.fillMaxSize()) {
        // Left edge highlight
        if (isLeftHighlighted) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(60.dp)
                    .fillMaxHeight()
                    .alpha(0.3f)
                    .background(BossTheme.colors.signal)
            )
        }

        // Right edge highlight
        if (isRightHighlighted) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(60.dp)
                    .fillMaxHeight()
                    .alpha(0.3f)
                    .background(BossTheme.colors.signal)
            )
        }

        // Top edge highlight
        if (isTopHighlighted) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(60.dp)
                    .alpha(0.3f)
                    .background(BossTheme.colors.signal)
            )
        }

        // Bottom edge highlight
        if (isBottomHighlighted) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(60.dp)
                    .alpha(0.3f)
                    .background(BossTheme.colors.signal)
            )
        }

        // Center highlight (add to existing panel)
        if (isCenterHighlighted) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.15f)
                    .background(BossTheme.colors.signal)
            )
        }
    }
}
