package ai.rever.boss.components.window_panel

import ai.rever.boss.components.dividers.VDivider
import ai.rever.boss.components.model.PanelDropZones
import ai.rever.boss.components.model.TabDraggableComponent
import ai.rever.boss.components.model.TabDropResult
import ai.rever.boss.components.model.TabDropTarget
import ai.rever.boss.components.overlays.OverlayCorner
import ai.rever.boss.components.plugin.TabTypeAvailability
import ai.rever.boss.components.plugin.disposePluginBrowsers
import ai.rever.boss.components.plugin.tab_types.PanelHostTabInfo
import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.components.window_panel.components.BossResizablePanel
import ai.rever.boss.components.window_panel.components.main_window_panels.BossMainPanel
import ai.rever.boss.components.window_panel.components.main_window_panels.BossTabsComponent
import ai.rever.boss.components.window_panel.components.main_window_panels.TabBarLayout
import ai.rever.boss.components.window_panel.components.main_window_panels.TabBarRevealState
import ai.rever.boss.components.window_panel.components.main_window_panels.VerticalTabBarResizeHandle
import ai.rever.boss.components.window_panel.components.main_window_panels.WindowRevealedTabBarDrawer
import ai.rever.boss.components.window_panel.components.main_window_panels.WindowVerticalTabBar
import ai.rever.boss.components.window_panel.components.main_window_panels.createBossAppContext
import ai.rever.boss.components.window_panel.components.main_window_panels.overlayRegionInWindow
import ai.rever.boss.components.window_panel.components.main_window_panels.rememberPinDrawerAction
import ai.rever.boss.components.window_panel.components.main_window_panels.rememberTabBarLayout
import ai.rever.boss.components.window_panel.components.main_window_panels.rememberTabBarRevealState
import ai.rever.boss.components.window_panel.components.main_window_panels.rememberTabGroupExpansion
import ai.rever.boss.components.window_panel.components.main_window_panels.rememberToggleCollapseAction
import ai.rever.boss.components.window_panel.components.main_window_panels.rememberWindowTabGroups
import ai.rever.boss.icons.FileIcons
import ai.rever.boss.platform.bossFileDropTarget
import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.events.DiffOpenEvent
import ai.rever.boss.plugin.tab.codeeditor.CodeEditorTabType
import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import ai.rever.boss.plugin.tab.diff.DiffTabInfo
import ai.rever.boss.plugin.tab.diff.DiffTabType
import ai.rever.boss.plugin.tab.fluck.FluckTabType
import ai.rever.boss.plugin.tab.jupyter.JupyterTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.project.DefaultWorkingDirectory
import ai.rever.boss.topofmind.ActiveTab
import ai.rever.boss.utils.extractFileName
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.WindowAppearanceSettingsManager
import ai.rever.boss.window.WindowProjectStateRegistry
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloseFullscreen
import androidx.compose.material.icons.outlined.Code
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

// Sealed class representing the split tree structure
sealed class SplitNode {
    data class Panel(
        val id: String,
        val tabsComponent: BossTabsComponent,
    ) : SplitNode()

    data class VerticalSplit(
        val left: SplitNode,
        val right: SplitNode,
    ) : SplitNode()

    data class HorizontalSplit(
        val top: SplitNode,
        val bottom: SplitNode,
    ) : SplitNode()
}

/** A split that has been asked for and is waiting on the tab that will fill it. */
data class PendingSplit(
    val panelId: String,
    val direction: SplitDirection,
)

enum class SplitOrientation {
    HORIZONTAL, // Split top/bottom
    VERTICAL, // Split left/right
}

/**
 * Which side of a pane the NEW pane goes on.
 *
 * [SplitOrientation] says only which way the divider runs. For a long time that was the whole
 * answer, because a new pane always went second - to the right, or below - and the two menu
 * entries that could ask for one were worded to match ("Split Right", "Split Down").
 *
 * The split map's four regions ask for the other two, so the side is now part of the request
 * rather than implied by it. [placeBefore] is the whole difference: the new pane takes the
 * left or top half and the original moves over.
 */
enum class SplitDirection(
    val orientation: SplitOrientation,
    val placeBefore: Boolean,
) {
    LEFT(SplitOrientation.VERTICAL, placeBefore = true),
    RIGHT(SplitOrientation.VERTICAL, placeBefore = false),
    UP(SplitOrientation.HORIZONTAL, placeBefore = true),
    DOWN(SplitOrientation.HORIZONTAL, placeBefore = false),
    ;

    /** How this direction is worded to the user. */
    val displayName: String
        get() =
            when (this) {
                LEFT -> "Left"
                RIGHT -> "Right"
                UP -> "Above"
                DOWN -> "Below"
            }
}

/**
 * Represents the screen bounds of a panel in global coordinates.
 */
data class PanelBounds(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {
    val left: Float get() = x
    val right: Float get() = x + width
    val top: Float get() = y
    val bottom: Float get() = y + height

    val centerX: Float get() = x + width / 2
    val centerY: Float get() = y + height / 2

    /** Check if this bounds overlaps with another vertically */
    fun hasVerticalOverlapWith(other: PanelBounds): Boolean = !(bottom <= other.top || top >= other.bottom)

    /** Check if this bounds overlaps with another horizontally */
    fun hasHorizontalOverlapWith(other: PanelBounds): Boolean = !(right <= other.left || left >= other.right)
}

/**
 * Navigation direction for spatial panel navigation.
 */
enum class NavigationDirection {
    LEFT,
    RIGHT,
    UP,
    DOWN,
}

private val splitViewLogger = BossLogger.forComponent("SplitView")

@Stable
class SplitViewState(
    internal val tabRegistry: TabRegistry,
    private val windowId: String,
    initialTabsComponent: BossTabsComponent? = null,
) {
    /**
     * Scope for the deferred opens in [requireTabTypeThen].
     *
     * Window-lived and cancelled by [dispose], which `BossAppStartupEffects`
     * calls from a `DisposableEffect` keyed on this state alone. What it holds is
     * a wait of up to five minutes on a person answering a dialog, so closing the
     * window has to abandon that rather than leave a coroutine holding a
     * reference to a disposed window's panels.
     *
     * `Dispatchers.Main`: every continuation ends in an `addTab`, which touches
     * Compose state.
     */
    private val openScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Cancels the deferred opens. Called when this state leaves the composition.
     *
     * Deliberately NOT called from `SplitViewStateRegistry.unregister`, which was
     * the first attempt: that runs from a `DisposableEffect` keyed on seven
     * values, only one of which is this state, so a change to any of the other
     * six would cancel a live window's scope and leave every later deferred open
     * silently doing nothing.
     */
    internal fun dispose() {
        openScope.cancel()
    }

    /**
     * Runs [open] once [typeId]'s plugin is available, asking the user to install
     * or enable it if it is not.
     *
     * Every open below goes through this, because the alternative is what shipped:
     * `addTab` logs "Dropped tab - no factory registered for its type", returns
     * -1, and every caller ignores it - so with the browser plugin absent the OS
     * could hand BOSS a link and nothing whatsoever appeared.
     *
     * The fast path is synchronous in effect: [TabTypeAvailability.require]
     * returns immediately when the type is registered, which is the normal case,
     * and `launch` on `Dispatchers.Main` from the UI thread runs the continuation
     * without yielding to another frame. The suspension only happens when there
     * is genuinely something to wait for.
     *
     * @param purpose what the user was trying to do, for the dialog's copy.
     */
    private fun requireTabTypeThen(
        typeId: TabTypeId,
        purpose: String,
        open: () -> Unit,
    ) {
        if (tabRegistry.isRegistered(typeId)) {
            open()
            return
        }
        openScope.launch {
            if (!TabTypeAvailability.require(tabRegistry, typeId, purpose)) return@launch
            // The wait can be five minutes long, because what it waits for is a
            // person answering a dialog. Cancelling the scope is the primary
            // guard; this is the one that survives a window teardown that did not
            // reach it, rather than adding a tab to a panel tree nothing renders.
            if (SplitViewStateRegistry.getState(windowId) !== this@SplitViewState) {
                splitViewLogger.debug(
                    LogCategory.UI,
                    "Dropping a deferred open; its window is gone",
                    mapOf("typeId" to typeId.typeId),
                )
                return@launch
            }
            open()
        }
    }

    // Root node of the split tree
    private var _rootNode =
        mutableStateOf<SplitNode>(
            SplitNode.Panel(
                id = "main",
                tabsComponent = initialTabsComponent ?: BossTabsComponent(createBossAppContext, tabRegistry, windowId),
            ),
        )
    val rootNode: SplitNode get() = _rootNode.value

    // One FocusRequester per panel, handed out rather than remembered by whoever needs one.
    //
    // A panel attaches its own to the Box it draws, and a tab bar uses it to give focus back to
    // that panel after closing a tab from a menu. With a bar per panel the two were the same
    // composition and a local `remember` sufficed; the window-level vertical bar is outside every
    // panel, so the requester has to live somewhere both can reach.
    private val panelFocusRequesters = mutableMapOf<String, FocusRequester>()

    /**
     * The pane shown alone, filling the split area, or null when the split is laid out normally.
     *
     * Zoom hides panes; it does not close them. The split tree is untouched, so exiting restores
     * the arrangement exactly - and every pane's tabs go on existing while zoomed, which is what
     * lets the window bar keep listing them.
     *
     * It follows the active pane rather than pinning one: activating another pane while zoomed
     * shows that one. Otherwise clicking a tab in another pane's group would appear to do
     * nothing, which is the same complaint the single bar was built to answer.
     */
    var zoomedPanelId by mutableStateOf<String?>(null)
        private set

    /**
     * Names the user has given panes, by id.
     *
     * A pane has no name of its own: what the map and the group headers show is derived from
     * where it sits - "Left", "Top right", "Pane 3" - which is true but says nothing about what
     * is in it. This overrides that for panes someone has bothered to name.
     *
     * Held in the window rather than in the split tree, and so NOT persisted: PanelConfig is a
     * published serialized type and adding a field to it is an ABI change for every plugin that
     * holds one. A name therefore lasts as long as the window does. See renamePanel.
     */
    private val panelNames = mutableStateMapOf<String, String>()

    /** The name someone gave this pane, or null to fall back to its position. */
    fun panelName(panelId: String): String? = panelNames[panelId]

    /** Name a pane, or clear the name with a blank string. */
    fun renamePanel(
        panelId: String,
        name: String,
    ) {
        if (name.isBlank()) panelNames.remove(panelId) else panelNames[panelId] = name.trim()
    }

    /**
     * A split waiting on the tab that will fill it.
     *
     * Splitting a pane cannot make an EMPTY one: `checkAndCloseEmptyPanels` closes a panel with no
     * tabs about 50ms later, so the split would appear to do nothing at all. So the pane menu asks
     * for a tab FIRST and the split happens with that tab in hand, through
     * `splitPanel(tabToMove = ...)` - the new pane is never empty for even a frame.
     *
     * Held here rather than in BossAppState because both ends already have the split state: the
     * map that requests it and the dialog that fulfils it.
     */
    var pendingSplit by mutableStateOf<PendingSplit?>(null)
        private set

    /** Ask for a tab, then split [panelId] and put that tab in the new pane. */
    fun requestSplitWithNewTab(
        panelId: String,
        direction: SplitDirection,
    ) {
        pendingSplit = PendingSplit(panelId, direction)
    }

    /**
     * Take the pending split, if there is one. Returns null when the next tab is an ordinary one.
     *
     * Consumed rather than read, so a dialog dismissed and reopened later does not split on a tab
     * nobody asked to be split.
     */
    fun consumePendingSplit(): PendingSplit? = pendingSplit.also { pendingSplit = null }

    /** Forget a split that was asked for and then abandoned. */
    fun cancelPendingSplit() {
        pendingSplit = null
    }

    /** Show this pane alone. */
    fun zoomPanel(panelId: String) {
        setActivePanel(panelId)
        zoomedPanelId = panelId
    }

    /** Put the split back. */
    fun exitZoom() {
        zoomedPanelId = null
    }

    // Track active panel for file operations
    private var _activePanelId = mutableStateOf("main")
    val activePanelId: String get() = _activePanelId.value
    val activePanelIdState: State<String> get() = _activePanelId

    // Track last interacted tab ID
    private var _lastInteractedTabId: String? = null

    // Track panel activation history for MOST_RECENT_ACTIVE mode in terminal link handling
    // Maintains order of recently activated panels (most recent first, limited to last 10)
    private val _panelActivationHistory = mutableListOf("main")

    // Track preserved workspace states.
    //
    // A state MAP rather than a plain one: these keys are what `liveWorkspaceIds` reports, and
    // that is read from composition. A plain map would leave the workspace menu marking whatever
    // was true when it last happened to recompose.
    private val preservedWorkspaceStates = mutableStateMapOf<String, PreservedWorkspaceState>()
    private var _currentWorkspaceId by mutableStateOf<String?>(null)
    val currentWorkspaceId: String? get() = _currentWorkspaceId

    /**
     * Every workspace this window is actually running, displayed or not.
     *
     * Switching workspaces does not tear the old one down: `preserveCurrentState` keeps its whole
     * split tree, and those are live BossTabsComponents with live tabs - which is why
     * `collectAllTabs` walks the preserved states too. So one window runs several workspaces at
     * once and shows one of them, and "which workspaces are running" is this set, not the single
     * id of the one on screen.
     */
    val liveWorkspaceIds: Set<String>
        get() = preservedWorkspaceStates.keys + setOfNotNull(_currentWorkspaceId)

    // Data class to hold preserved state
    data class PreservedWorkspaceState(
        val rootNode: SplitNode,
        val activePanelId: String,
        val workspaceName: String = "",
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
    fun updatePanelBounds(
        panelId: String,
        bounds: PanelBounds,
    ) {
        _panelBounds[panelId] = bounds
    }

    /**
     * Get the current bounds for a panel, or null if not yet positioned.
     */
    fun getPanelBounds(panelId: String): PanelBounds? = _panelBounds[panelId]

    /**
     * Clear bounds for a specific panel (e.g., when removed).
     */
    fun clearPanelBounds(panelId: String) {
        _panelBounds.remove(panelId)
    }

    // Debounce active panel changes to prevent rapid oscillation from spurious focus events.
    // 50ms chosen based on observed oscillation intervals (~8ms) - provides enough filtering
    // while remaining responsive to genuine user interactions.
    private val lastActivePanelChangeTime =
        java.util.concurrent.atomic
            .AtomicLong(0L)
    private val activePanelDebounceMs = 50L

    fun setActivePanel(panelId: String) {
        // Zoom follows. See zoomedPanelId for why it is not a pinned pane.
        if (zoomedPanelId != null) zoomedPanelId = panelId
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

    fun trackTabInteraction(
        panelId: String,
        tabId: String,
    ) {
        _lastInteractedTabId = tabId
        setActivePanel(panelId) // Now handles both active and lastInteracted
    }

    fun getLastInteractedTabComponent(): BossTabsComponent? = findPanel(_activePanelId.value)?.tabsComponent

    fun getActiveTabsComponent(): BossTabsComponent? = findPanel(_activePanelId.value)?.tabsComponent

    companion object {
        private val BROWSER_FILE_EXTENSIONS =
            setOf(
                // Images
                "png",
                "jpg",
                "jpeg",
                "gif",
                "svg",
                "bmp",
                "ico",
                "webp",
                // Documents
                "pdf",
                // Video
                "mp4",
                "webm",
                "mov",
                "avi",
                "mkv",
                // Audio
                "mp3",
                "wav",
                "flac",
                "aac",
                "m4a",
                "ogg",
            )

        fun shouldOpenInBrowser(fileName: String): Boolean {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return ext in BROWSER_FILE_EXTENSIONS
        }

        fun toFileUrl(filePath: String): String =
            java.io
                .File(filePath)
                .toURI()
                .toString()
    }

    fun openFileInActivePanel(
        filePath: String,
        fileName: String,
    ) {
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
    fun openNotebookTab(
        filePath: String,
        fileName: String,
    ) {
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
        findPanelWithTabMatching { tab ->
            tab is JupyterTabInfo && TabPaths.pathsMatch(tab.filePath, filePath)
        }

    /** Find the first panel containing a tab that satisfies [predicate]. */
    private fun findPanelWithTabMatching(predicate: (TabInfo) -> Boolean): PanelTabMatch? {
        getAllPanels().forEach { panel ->
            val tabIndex =
                panel.tabsComponent.tabsState.value.tabs
                    .indexOfFirst(predicate)
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
    fun openFileInEditorTab(
        filePath: String,
        fileName: String,
    ) {
        requireTabTypeThen(CodeEditorTabType.typeId, "Opening $fileName") {
            openFileInEditorTabNow(filePath, fileName)
        }
    }

    private fun openFileInEditorTabNow(
        filePath: String,
        fileName: String,
    ) {
        val activeComponent = getActiveTabsComponent() ?: return

        // Check if file is already open in an editor tab in any panel
        findPanelWithEditorTab(filePath)?.let { (panelId, component, tabIndex) ->
            component.selectTab(tabIndex)
            setActivePanel(panelId)
            return
        }

        // File not open, create new tab in active panel
        val fileIconInfo = FileIcons.forFile(fileName)
        val editorTab =
            EditorTabInfo(
                id = "editor-${Random.nextLong()}",
                typeId =
                    ai.rever.boss.components.registery
                        .TabTypeId("editor"),
                title = fileName,
                icon = fileIconInfo.icon,
                tabIcon =
                    ai.rever.boss.plugin.api.TabIcon
                        .Vector(fileIconInfo.icon, fileIconInfo.color),
                filePath = filePath,
            )
        activeComponent.addTab(editorTab).takeIf { it >= 0 }?.let {
            activeComponent.selectTab(it)
        }
    }

    /**
     * Open a git diff in the active panel (from a [DiffOpenEvent], i.e. the
     * git data provider's `openDiff` or a deep link). The diff tab type is
     * registered by the editor-tab PLUGIN, not the host, so the gate is real:
     * with that plugin absent or still starting, [requireTabTypeThen] waits for
     * the type (or prompts) rather than dropping the open.
     */
    fun openDiffTabInActivePanel(event: DiffOpenEvent) {
        requireTabTypeThen(DiffTabType.typeId, "Opening diff") {
            openDiffTabNow(event)
        }
    }

    private fun openDiffTabNow(event: DiffOpenEvent) {
        val activeComponent = getActiveTabsComponent() ?: return

        // Reuse an open diff of the same thing, like every other open path.
        // Without this, clicking a changed file added a tab per click.
        findPanelWithDiffTab(event)?.let { (panelId, component, tabIndex) ->
            component.selectTab(tabIndex)
            setActivePanel(panelId)
            return
        }

        val diffTab =
            DiffTabInfo.create(
                filePath = event.filePath,
                staged = event.staged,
                fromRef = event.fromRef,
                toRef = event.toRef,
            )
        activeComponent.addTab(diffTab).takeIf { it >= 0 }?.let {
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
    fun openUrlInActivePanel(
        url: String,
        title: String,
        forceNewTab: Boolean = false,
    ) {
        requireTabTypeThen(FluckTabType.typeId, "Opening $title") {
            openUrlInActivePanelNow(url, title, forceNewTab)
        }
    }

    private fun openUrlInActivePanelNow(
        url: String,
        title: String,
        forceNewTab: Boolean,
    ) {
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
            val fluckTab =
                FluckTabInfo(
                    id = "fluck-${Random.nextLong()}",
                    typeId = TabTypeId("fluck"),
                    _title = title,
                    url = url,
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
                            tab.currentUrl == url // Only check current URL to avoid focusing tabs that navigated away
                    }.takeIf { it >= 0 }
                    ?.let { tabIndex ->
                        component.selectTab(tabIndex)
                        setActivePanel(panelId)
                    }
                return
            }
        }

        // URL not open, create new Fluck tab in active panel
        val fluckTab =
            FluckTabInfo(
                id = "fluck-${Random.nextLong()}",
                typeId = TabTypeId("fluck"),
                _title = title,
                url = url,
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
    fun openTerminalInActivePanel(
        command: String? = null,
        workingDirectory: String? = null,
    ) {
        requireTabTypeThen(TerminalTabType.typeId, "Opening a terminal") {
            openTerminalInActivePanelNow(command, workingDirectory)
        }
    }

    /**
     * Where a terminal opened in this window should start.
     *
     * Extracted from [openTerminalInActivePanelNow] to keep that function under
     * the length limit once the availability gate split it in two; the rule it
     * encodes is worth reading on its own anyway.
     *
     * `selectedOrNull` on the override too, not just `?:`. A run configuration
     * with an unset working directory reaches here as "", which is not null, so
     * it would pass straight through to the terminal service and land in the home
     * directory - the thing this is meant to stop. Blank counts as absent, the
     * same rule DefaultWorkingDirectory applies to a project path.
     */
    private fun terminalWorkingDirectory(workingDirectory: String?): String {
        val projectPath =
            WindowProjectStateRegistry
                .get(windowId)
                ?.selectedProject
                ?.value
                ?.path ?: ""
        return DefaultWorkingDirectory.selectedOrNull(workingDirectory)
            ?: DefaultWorkingDirectory.resolve(projectPath)
    }

    private fun openTerminalInActivePanelNow(
        command: String?,
        workingDirectory: String?,
    ) {
        val activeComponent = getActiveTabsComponent()
        val terminalWorkingDir = terminalWorkingDirectory(workingDirectory)

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
            val terminalTab =
                TerminalTabInfo(
                    id = "terminal-${System.currentTimeMillis()}",
                    typeId = TabTypeId("terminal"),
                    title = if (command != null) "Terminal: $command" else "Terminal",
                    initialCommand = command,
                    workingDirectory = terminalWorkingDir,
                )

            val tabIndex = component.addTab(terminalTab)
            if (tabIndex >= 0) {
                component.selectTab(tabIndex)
                setActivePanel(firstPanel.id)
                splitViewLogger.debug(
                    LogCategory.UI,
                    "Terminal tab created in first panel",
                    if (command !=
                        null
                    ) {
                        mapOf("command" to command)
                    } else {
                        emptyMap()
                    },
                )
            } else {
                splitViewLogger.error(LogCategory.UI, "Failed to add terminal tab to panel")
            }
            return
        }

        // Create new terminal tab in active panel
        val terminalTab =
            TerminalTabInfo(
                id = "terminal-${System.currentTimeMillis()}",
                typeId = TabTypeId("terminal"),
                title = if (command != null) "Terminal: $command" else "Terminal",
                initialCommand = command,
                workingDirectory = terminalWorkingDir,
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
        fun searchNode(node: SplitNode): SplitNode.Panel? =
            when (node) {
                is SplitNode.Panel -> {
                    if (node.tabsComponent.tabsState.value.tabs
                            .any { it.id == tabId }
                    ) {
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
        detachedTab: BossTabsComponent.DetachedTab? = null,
        /**
         * Put the new pane in the left or top half, moving the original over.
         *
         * Defaults to false, which is what every caller did before the split map could ask for
         * a direction: the original keeps its side and the new pane goes second.
         */
        placeBefore: Boolean = false,
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
                splitViewLogger.warn(
                    LogCategory.UI,
                    "splitPanel target panel missing; rescuing detached tab",
                    mapOf(
                        "targetPanelId" to panelId,
                        "tabId" to detached.config.id,
                    ),
                )
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
            val freshTab =
                if (tab is FluckTabInfo) {
                    // Find the panel containing this tab
                    val sourcePanel = findPanelContainingTab(tab.id)
                    val foundTab =
                        sourcePanel
                            ?.tabsComponent
                            ?.tabsState
                            ?.value
                            ?.tabs
                            ?.find { it.id == tab.id } as? FluckTabInfo
                    foundTab ?: tab // Fallback to provided tab if not found
                } else {
                    tab
                }

            val copiedTab =
                when (freshTab) {
                    is EditorTabInfo -> {
                        freshTab.copy(id = "editor-${Random.nextLong()}")
                    }

                    is FluckTabInfo -> {
                        freshTab.copy(
                            id = "fluck-${Random.nextLong()}",
                            url = freshTab.currentUrl, // Set initial URL to current URL (Issue #406)
                            _currentUrl = freshTab.currentUrl, // Preserve the current URL
                            navigationHistory = freshTab.navigationHistory.toMutableList(), // Deep copy the history
                        )
                    }

                    is TerminalTabInfo -> {
                        freshTab.copy(id = "terminal-${Random.nextLong()}")
                    }

                    is JupyterTabInfo -> {
                        freshTab.copy(id = JupyterTabInfo.newId())
                    }

                    // PanelHostTabInfo deliberately falls through uncopied: its id is fixed
                    // ("panel-tab:<panelId>") and it renders the panel's single cached component
                    // instance, so a copy would compose that instance in two tabs at once.
                    // Splitting MOVES it instead — see below.
                    else -> {
                        freshTab
                    }
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
                // recordForReopen = false: this is the second half of a move, and the tab is
                // already live in newComponent by the time it runs.
                findPanelContainingTab(copiedTab.id)
                    ?.tabsComponent
                    ?.removeTabById(copiedTab.id, recordForReopen = false)
            }
        }

        // Create new panel node
        val newPanelNode = SplitNode.Panel(newPanelId, newComponent)

        // Replace the panel node with a split node
        _rootNode.value =
            replacePanelWithSplit(
                _rootNode.value,
                panelId,
                orientation,
                newPanelNode,
                placeBefore,
            )

        return newPanelId
    }

    private fun replacePanelWithSplit(
        node: SplitNode,
        targetPanelId: String,
        orientation: SplitOrientation,
        newPanel: SplitNode.Panel,
        placeBefore: Boolean,
    ): SplitNode =
        when (node) {
            is SplitNode.Panel -> {
                if (node.id == targetPanelId) {
                    // Replace this panel with a split. The original keeps all its tabs either
                    // way; placeBefore only decides which half it ends up in.
                    when (orientation) {
                        SplitOrientation.VERTICAL -> {
                            SplitNode.VerticalSplit(
                                left = if (placeBefore) newPanel else node,
                                right = if (placeBefore) node else newPanel,
                            )
                        }

                        SplitOrientation.HORIZONTAL -> {
                            SplitNode.HorizontalSplit(
                                top = if (placeBefore) newPanel else node,
                                bottom = if (placeBefore) node else newPanel,
                            )
                        }
                    }
                } else {
                    node
                }
            }

            is SplitNode.VerticalSplit -> {
                SplitNode.VerticalSplit(
                    left = replacePanelWithSplit(node.left, targetPanelId, orientation, newPanel, placeBefore),
                    right = replacePanelWithSplit(node.right, targetPanelId, orientation, newPanel, placeBefore),
                )
            }

            is SplitNode.HorizontalSplit -> {
                SplitNode.HorizontalSplit(
                    top = replacePanelWithSplit(node.top, targetPanelId, orientation, newPanel, placeBefore),
                    bottom = replacePanelWithSplit(node.bottom, targetPanelId, orientation, newPanel, placeBefore),
                )
            }
        }

    fun closePanel(panelId: String) {
        // Don't close the main panel if it's the only one
        if (panelId == "main" && getAllPanels().size == 1) return

        // First, dispose all tabs in the panel being closed
        findPanel(panelId)?.let { panel ->
            panel.tabsComponent.clearAllTabs()
        }

        // A zoomed pane that is being closed cannot stay zoomed onto nothing.
        if (zoomedPanelId == panelId) zoomedPanelId = null
        // Ids are not reused, but a name outliving its pane is a leak with a user-visible tail:
        // it would reappear if one ever were.
        panelNames.remove(panelId)

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

    private fun removePanel(
        node: SplitNode,
        targetPanelId: String,
    ): SplitNode =
        when (node) {
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
                    val newLeft =
                        if (isPanelInNode(node.left, targetPanelId)) {
                            removePanel(node.left, targetPanelId)
                        } else {
                            node.left
                        }
                    val newRight =
                        if (isPanelInNode(node.right, targetPanelId)) {
                            removePanel(node.right, targetPanelId)
                        } else {
                            node.right
                        }

                    // If either side is now empty, promote the other side
                    when {
                        newLeft === node.left && newRight === node.right -> node

                        // No change
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
                    val newTop =
                        if (isPanelInNode(node.top, targetPanelId)) {
                            removePanel(node.top, targetPanelId)
                        } else {
                            node.top
                        }
                    val newBottom =
                        if (isPanelInNode(node.bottom, targetPanelId)) {
                            removePanel(node.bottom, targetPanelId)
                        } else {
                            node.bottom
                        }

                    // If either side is now empty, promote the other side
                    when {
                        newTop === node.top && newBottom === node.bottom -> node

                        // No change
                        else -> SplitNode.HorizontalSplit(newTop, newBottom)
                    }
                }
            }
        }

    private fun isPanelInNode(
        node: SplitNode,
        panelId: String,
    ): Boolean =
        when (node) {
            is SplitNode.Panel -> node.id == panelId
            is SplitNode.VerticalSplit -> isPanelInNode(node.left, panelId) || isPanelInNode(node.right, panelId)
            is SplitNode.HorizontalSplit -> isPanelInNode(node.top, panelId) || isPanelInNode(node.bottom, panelId)
        }

    /**
     * Find a panel by its ID.
     * Returns null if no panel with the given ID exists.
     */
    internal fun findPanel(panelId: String): SplitNode.Panel? = findPanelInNode(_rootNode.value, panelId)

    private fun findPanelInNode(
        node: SplitNode,
        panelId: String,
    ): SplitNode.Panel? =
        when (node) {
            is SplitNode.Panel -> {
                if (node.id == panelId) node else null
            }

            is SplitNode.VerticalSplit -> {
                findPanelInNode(node.left, panelId) ?: findPanelInNode(node.right, panelId)
            }

            is SplitNode.HorizontalSplit -> {
                findPanelInNode(node.top, panelId) ?: findPanelInNode(node.bottom, panelId)
            }
        }

    private fun findPanelWithFile(filePath: String): Pair<String, BossTabsComponent>? {
        val fileUrl = toFileUrl(filePath)
        getAllPanels().forEach { panel ->
            if (panel.tabsComponent.tabsState.value.tabs.any { tab ->
                    (tab is EditorTabInfo && tab.filePath == filePath) ||
                        (tab is FluckTabInfo && tab.currentUrl == fileUrl)
                }
            ) {
                return panel.id to panel.tabsComponent
            }
        }
        return null
    }

    private data class PanelTabMatch(
        val panelId: String,
        val component: BossTabsComponent,
        val tabIndex: Int,
    )

    /**
     * An open diff of the same scope: same file, same side of the index, same
     * refs. A staged diff and a working-tree diff of one file are different
     * views and each gets its own tab, as in VS Code.
     */
    private fun findPanelWithDiffTab(event: DiffOpenEvent): PanelTabMatch? =
        findPanelWithTabMatching { tab ->
            tab is DiffTabInfo &&
                diffTabMatches(tab, event.filePath, event.staged, event.fromRef, event.toRef)
        }

    private fun findPanelWithEditorTab(filePath: String): PanelTabMatch? {
        // A blank path never matches: normalize("") is "", so a blank query
        // would focus the first Untitled editor tab.
        if (filePath.isBlank()) return null
        // pathsMatch keeps the canonicalPath syscalls out of the common case
        // (identical spellings), paying for them only on a lexical mismatch.
        return findPanelWithTabMatching { tab ->
            tab is EditorTabInfo && TabPaths.pathsMatch(tab.filePath, filePath)
        }
    }

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
                        tab.currentUrl == url // Only check current URL to avoid focusing tabs that navigated away
                }
            ) {
                return panel.id to panel.tabsComponent
            }
        }
        return null
    }

    /**
     * This panel's focus requester, created on first ask.
     *
     * Deliberately not tied to a composition: the panel and its tab bar ask on different frames
     * and in either order, and both must get the same instance or the bar's `requestFocus` lands
     * on something nothing is attached to.
     */
    fun focusRequesterFor(panelId: String): FocusRequester = panelFocusRequesters.getOrPut(panelId) { FocusRequester() }

    /**
     * Drop a panel's focus requester once that panel is really gone.
     *
     * Guarded by the tree rather than by the caller, because the dispose that calls this also
     * fires when a panel's composition is merely re-keyed - and dropping a live panel's requester
     * would hand out a second one while the first is still the attached one.
     */
    internal fun releaseFocusRequester(panelId: String) {
        if (findPanel(panelId) == null) panelFocusRequesters.remove(panelId)
    }

    fun getAllPanels(): List<SplitNode.Panel> = getAllPanelsInNode(_rootNode.value)

    private fun getAllPanelsInNode(node: SplitNode): List<SplitNode.Panel> =
        when (node) {
            is SplitNode.Panel -> {
                listOf(node)
            }

            is SplitNode.VerticalSplit -> {
                getAllPanelsInNode(node.left) + getAllPanelsInNode(node.right)
            }

            is SplitNode.HorizontalSplit -> {
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
    fun hasTabs(): Boolean =
        getAllPanels().any { panel ->
            panel.tabsComponent.tabsState.value.tabs
                .isNotEmpty()
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
    fun getFirstOtherPanelExcluding(excludePanelId: String): SplitNode.Panel? = getAllPanels().firstOrNull { it.id != excludePanelId }

    /**
     * Find the panel that contains a tab with the given ID.
     * Issue #347: Used for runner terminal management.
     *
     * @param tabId The tab ID to search for
     * @return The panel containing the tab, or null if not found
     */
    fun findPanelWithTab(tabId: String): SplitNode.Panel? =
        getAllPanels().find { panel ->
            panel.tabsComponent.tabsState.value.tabs
                .any { it.id == tabId }
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
        allPanels: List<SplitNode.Panel>,
    ): SplitNode.Panel? {
        data class Candidate(
            val panel: SplitNode.Panel,
            val bounds: PanelBounds,
            val overlap: Float,
            val distance: Float,
        )

        val candidates =
            allPanels.mapNotNull { panel ->
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
        val best =
            candidates.maxByOrNull { candidate ->
                candidate.overlap * 1000f - candidate.distance
            }!!

        return best.panel
    }

    /**
     * Find the closest panel to the right of the current bounds.
     */
    private fun findClosestPanelToRight(
        currentBounds: PanelBounds,
        allPanels: List<SplitNode.Panel>,
    ): SplitNode.Panel? {
        data class Candidate(
            val panel: SplitNode.Panel,
            val bounds: PanelBounds,
            val overlap: Float,
            val distance: Float,
        )

        val candidates =
            allPanels.mapNotNull { panel ->
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

        val best =
            candidates.maxByOrNull { candidate ->
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
        allPanels: List<SplitNode.Panel>,
    ): SplitNode.Panel? {
        data class Candidate(
            val panel: SplitNode.Panel,
            val bounds: PanelBounds,
            val overlap: Float,
            val distance: Float,
        )

        val candidates =
            allPanels.mapNotNull { panel ->
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

        val best =
            candidates.maxByOrNull { candidate ->
                candidate.overlap * 1000f - candidate.distance
            }!!

        return best.panel
    }

    /**
     * Find the closest panel below the current bounds.
     */
    private fun findClosestPanelBelow(
        currentBounds: PanelBounds,
        allPanels: List<SplitNode.Panel>,
    ): SplitNode.Panel? {
        data class Candidate(
            val panel: SplitNode.Panel,
            val bounds: PanelBounds,
            val overlap: Float,
            val distance: Float,
        )

        val candidates =
            allPanels.mapNotNull { panel ->
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

        val best =
            candidates.maxByOrNull { candidate ->
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
        val emptyPanels =
            allPanels.filter { panel ->
                panel.tabsComponent.tabsState.value.tabs
                    .isEmpty()
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
        _rootNode.value =
            SplitNode.Panel(
                id = "main",
                tabsComponent = mainComponent,
            )
        _activePanelId.value = "main"
    }

    fun preserveCurrentState(
        workspaceId: String,
        workspaceName: String = "",
    ) {
        // Save current state before switching
        _currentWorkspaceId?.let { currentId ->
            preservedWorkspaceStates[currentId] =
                PreservedWorkspaceState(
                    rootNode = _rootNode.value,
                    activePanelId = _activePanelId.value,
                    workspaceName = workspaceName,
                )
        }
        _currentWorkspaceId = workspaceId
    }

    /**
     * Stop running the workspace this window is showing, instead of preserving it.
     *
     * The opposite of [preserveCurrentState] and the other half of a switch. Two things have to
     * happen, and neither is implied by simply applying the next workspace over the top:
     *
     * - Its tabs are CLEARED. Applying a workspace replaces the root node, which drops the
     *   reference to the old tree but disposes nothing - so its browsers would keep running with
     *   nothing on screen pointing at them, which is the memory this is meant to free.
     * - Any state preserved for it on an EARLIER visit is dropped. Otherwise closing a workspace
     *   you had previously kept would leave that older copy behind, and switching back would
     *   restore a layout from two visits ago.
     */
    fun closeCurrentWorkspace() {
        val currentId = _currentWorkspaceId ?: return
        preservedWorkspaceStates.remove(currentId)
        getAllPanels().forEach { panel -> panel.tabsComponent.clearAllTabs() }
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

    fun getPanelTabsComponent(panelId: String): BossTabsComponent? = findPanel(panelId)?.tabsComponent

    /**
     * Get a panel by its ID.
     */
    fun getPanel(panelId: String): SplitNode.Panel? = findPanel(panelId)

    fun selectTabInPanel(
        tabId: String,
        panelId: String,
    ) {
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
            val workspaceName =
                preservedWorkspaceStates[workspaceId]?.workspaceName
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
                                windowId = windowId,
                            ),
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
        val idsToRemove =
            preservedWorkspaceStates.keys.filter { workspaceId ->
                // Keep special workspaces like "last-session" and only remove user workspaces
                !existingWorkspaceIds.contains(workspaceId) && workspaceId != "last-session"
            }

        idsToRemove.forEach { workspaceId ->
            preservedWorkspaceStates.remove(workspaceId)
        }
    }

    fun collectAllActiveTabs(
        workspaceManager: ai.rever.boss.components.workspaces.WorkspaceManager? = null,
        windowId: String = "unknown",
    ): List<ActiveTab> {
        val result = mutableListOf<ActiveTab>()
        val seenTabIds = mutableSetOf<String>()
        val seenConfigIds = mutableSetOf<String>()

        // Helper function to get proper workspace name
        fun getWorkspaceName(workspaceId: String): String =
            workspaceManager
                ?.workspaces
                ?.value
                ?.find { it.id == workspaceId }
                ?.name
                ?: preservedWorkspaceStates[workspaceId]?.workspaceName
                ?: when (workspaceId) {
                    "last-session" -> "Last Session"
                    else -> "Workspace $workspaceId"
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
                                windowId = windowId,
                            ),
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
        seenTabIds: MutableSet<String>,
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
                                windowId = windowId,
                            ),
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
        seenTabIds: MutableSet<String>,
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
                                windowId = windowId,
                            ),
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
    initialTabsComponent: BossTabsComponent? = null,
): SplitViewState = remember { SplitViewState(tabRegistry, windowId, initialTabsComponent) }

/**
 * The window's main area: the split tree, and - in LEFT tab bar position - the one vertical tab
 * bar that lists every pane's tabs.
 *
 * The bar is drawn HERE rather than inside each panel, and that is the whole of the single-bar
 * change. A panel still owns its own tabs; what it no longer owns is a bar to draw them in, so
 * splitting adds a group to one bar instead of a second bar beside the first.
 *
 * TOP position is untouched: each panel keeps drawing its own strip, because a horizontal strip
 * has no room to render groups and TOP is the default.
 */
@Composable
fun SplitViewPanel(
    splitViewState: SplitViewState,
    modifier: Modifier = Modifier,
    tabDragComponent: TabDraggableComponent? = null,
    onTabDropResult: (TabDropResult) -> Unit = {},
    /** Window chrome for the foot of the vertical bar. Ignored in TOP position, which has none. */
    verticalBarFooter: @Composable () -> Unit = {},
    /**
     * Window chrome for BELOW the split map, at the very foot of the FULL vertical bar - Settings,
     * Search, Sign Out and the tools launcher when nothing else is left to hold them.
     *
     * Ignored in TOP position, which has no vertical bar: there the same actions go to the top bar
     * if it is up, an open plugin panel's foot if one is open, and a floating cluster otherwise.
     * See `focusQuickActionsPlacement`.
     */
    verticalBarBelowMap: @Composable () -> Unit = {},
    /**
     * The same chrome for when the bar is down to its RAIL, at the very foot of that.
     *
     * A separate slot because the rail and the hover drawer are on screen together, so one slot
     * handed to both drew the actions twice - see `WindowVerticalTabBar.belowTabs`.
     */
    verticalBarRailActions: @Composable () -> Unit = {},
    /**
     * Clearance above the vertical bar.
     *
     * macOS draws its traffic lights over the top-left of the content when the window sets
     * `fullWindowContent`, and with no title row and no left icon strip this bar is what is
     * under them. Only this column is inset - the rest of the window starts at the top, which
     * is the whole point of not reserving a full-width row. See `macTrafficLightInset`.
     */
    verticalBarTopInset: Dp = 0.dp,
    /**
     * Reports whether the hover-revealed bar is on screen.
     *
     * The window needs it because it decides where the host's actions go: a collapsed bar puts
     * them at the foot of its rail, and a revealed drawer IS a full bar, so while it is up they
     * move from the rail's bottom into the bar's foot. Only this composable knows: the reveal
     * state machine lives here. See `verticalBarHost`.
     */
    onDrawerVisibleChange: (Boolean) -> Unit = {},
    /**
     * Reports whether the bar in the layout is the slim rail.
     *
     * Not the same question as the `tabBarCollapsed` preference, which is what the window used to
     * ask: a bar also rails itself when there is no room for a full one, and only this composable
     * has measured the width. The window needs the MEASURED answer because it picks which of the
     * bar's two layouts hosts the host's actions - a row under the split map, or a column at the
     * bottom of the rail - and the preference alone cannot tell a self-railed narrow window from
     * an expanded one. While it believed the preference, a narrow window sent them to a foot that
     * was not being drawn and they rendered nowhere at all.
     */
    onBarRailedChange: (Boolean) -> Unit = {},
) {
    val density = LocalDensity.current

    // The main area's own width, measured rather than taken from a BoxWithConstraints - see
    // BossMainPanel for what a SubcomposeLayout around this tree costs. It is the WINDOW's width
    // now, not a panel's, which is the point: whether there is room for a full bar is a question
    // about the window, and judging it per panel is what collapsed both bars the moment anyone
    // split the window.
    var contentWidthPx by remember { mutableIntStateOf(0) }

    // Which edge, how wide, rail or not. See TabBarLayout.
    val bar = rememberTabBarLayout(contentWidthPx)

    // The rail/drawer state machine and its timing. See TabBarRevealState.
    val reveal =
        rememberTabBarRevealState(
            railShown = bar.railShown,
            narrow = bar.narrow,
            hoverExpand = bar.hoverExpand,
        )
    // This area's rectangle in dp relative to the window's content pane, for the drawer's
    // heavyweight overlay window. Null until measured, and the drawer draws nothing while it is.
    var contentRegion by remember { mutableStateOf<IntRect?>(null) }

    // In an effect, not during composition: the window turns this into a placement decision that
    // feeds back into what this composable is given, and writing it inline would be a state write
    // during composition of the thing that reads it.
    val drawerOpen = bar.vertical && bar.railShown && reveal.drawerVisible
    LaunchedEffect(drawerOpen) { onDrawerVisibleChange(drawerOpen) }

    // Same reasoning, same shape: reported in an effect because the window turns it into a
    // placement decision that feeds back into what this composable is handed.
    val barRailed = bar.vertical && bar.railShown
    LaunchedEffect(barRailed) { onBarRailedChange(barRailed) }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .onSizeChanged { size -> contentWidthPx = size.width }
                .onGloballyPositioned { coordinates ->
                    contentRegion = overlayRegionInWindow(coordinates.boundsInWindow(), density.density)
                }
                // Dropping a file on the main panel opens it, routed by extension exactly as a
                // click in the sidebar would be. Attached here rather than per-panel so the
                // whole main area is a target, and it accepts the plain OS file flavour, so a
                // drag out of Finder works the same as one out of a BOSS sidebar.
                .bossFileDropTarget { paths ->
                    paths.forEach { splitViewState.openFileInActivePanel(it, it.extractFileName()) }
                },
    ) {
        val splitTree = rememberSplitTree(splitViewState, tabDragComponent, onTabDropResult, bar.vertical)

        if (bar.vertical) {
            WindowBarRow(
                splitViewState = splitViewState,
                bar = bar,
                reveal = reveal,
                tabDragComponent = tabDragComponent,
                onTabDropResult = onTabDropResult,
                footer = verticalBarFooter,
                belowMap = verticalBarBelowMap,
                belowTabs = verticalBarRailActions,
                topInset = verticalBarTopInset,
                splitTree = splitTree,
            )
        } else {
            splitTree(Modifier.fillMaxSize())
        }

        if (bar.vertical && bar.railShown) {
            RevealedBar(
                splitViewState = splitViewState,
                bar = bar,
                reveal = reveal,
                contentRegion = contentRegion,
                topInset = verticalBarTopInset,
                footer = verticalBarFooter,
                belowMap = verticalBarBelowMap,
            )
        }
    }
}

/**
 * The window's panes: all of them in their split, or one of them filling the area.
 *
 * A zoomed pane is rendered DIRECTLY rather than through the split tree. The tree is left exactly
 * as it was, so exiting restores the arrangement with nothing having to remember it.
 */
@Composable
private fun SplitOrZoomedPane(
    splitViewState: SplitViewState,
    tabDragComponent: TabDraggableComponent?,
    onTabDropResult: (TabDropResult) -> Unit,
    showPanelTabBar: Boolean,
) {
    val zoomed = splitViewState.zoomedPanelId?.let { splitViewState.getPanel(it) }
    if (zoomed == null) {
        RenderSplitNode(
            node = splitViewState.rootNode,
            splitViewState = splitViewState,
            tabDragComponent = tabDragComponent,
            onTabDropResult = onTabDropResult,
            showPanelTabBar = showPanelTabBar,
        )
        return
    }

    key(zoomed.id) {
        zoomed.tabsComponent.BossMainPanel(
            splitViewState = splitViewState,
            currentPanelId = zoomed.id,
            tabDragComponent = tabDragComponent,
            onTabDropResult = onTabDropResult,
            showTabBar = showPanelTabBar,
        )
    }
}

/**
 * The window bar beside the split tree.
 *
 * The bar's own hoverable wrapper lives here rather than inside the bar, because what it enables
 * is the rail's hover reveal - a decision the reveal state owns and the bar does not.
 */
@Composable
private fun WindowBarRow(
    splitViewState: SplitViewState,
    bar: TabBarLayout,
    reveal: TabBarRevealState,
    tabDragComponent: TabDraggableComponent?,
    onTabDropResult: (TabDropResult) -> Unit,
    footer: @Composable () -> Unit,
    belowMap: @Composable () -> Unit,
    /** The rail's own copy of that chrome. See `WindowVerticalTabBar.belowTabs`. */
    belowTabs: @Composable () -> Unit,
    /** Clearance above the bar, for the macOS traffic lights. See [SplitViewPanel]. */
    topInset: Dp,
    splitTree: @Composable (Modifier) -> Unit,
) {
    val listState = rememberLazyListState()
    val expansion = rememberTabGroupExpansion()
    val groups =
        rememberWindowTabGroups(
            splitViewState = splitViewState,
            listState = listState,
            expansion = expansion,
            tabDragComponent = tabDragComponent,
            onTabDropResult = onTabDropResult,
        )

    // The width being dragged, or null when nobody is dragging. Local for the length of the
    // gesture and written to settings once, on release - see VerticalTabBarResizeHandle for why
    // persisting each frame is the wrong shape.
    var draggedWidth by remember { mutableStateOf<Float?>(null) }
    val barWidthScope = rememberCoroutineScope()
    val barWidth = draggedWidth?.dp ?: bar.width

    Row(modifier = Modifier.fillMaxSize()) {
        // The bar and its resize band share one Box: the band is an OVERLAY on the bar's trailing
        // edge rather than a strip beside it, so it costs no layout width. A strip cost 6dp where
        // the divider had cost 1, which read as a margin down the bar's right edge.
        Box(
            modifier =
                Modifier
                    // PAINTED before it is padded. Padding alone leaves the inset area drawn by
                    // nothing, and nothing is not the background: the raw native window surface
                    // shows through, which is white. Same trap as the bar's resize strip.
                    // `raised` is what VerticalBar fills itself with, so the clearance reads as
                    // the top of the bar rather than as a band above it.
                    .background(BossTheme.colors.raised)
                    .padding(top = topInset)
                    .hoverable(reveal.railHover, enabled = bar.hoverExpand && bar.railShown),
        ) {
            WindowVerticalTabBar(
                groups = groups,
                listState = listState,
                expansion = expansion,
                width = barWidth,
                collapsed = bar.railShown,
                onToggleCollapse = rememberToggleCollapseAction(bar, reveal),
                tabDragComponent = tabDragComponent,
                footer = footer,
                belowMap = belowMap,
                belowTabs = belowTabs,
                zoomed = splitViewState.zoomedPanelId != null,
                onExitZoom = splitViewState::exitZoom,
            )
            VerticalTabBarResizeHandle(
                // Not while the bar is a rail: the rail's width is a different number, and a drag
                // that appeared to work would be moving one nothing on screen was showing.
                enabled = !bar.railShown,
                currentWidth = barWidth.value,
                onPreview = { width -> draggedWidth = width },
                onCommit = { width ->
                    draggedWidth = null
                    barWidthScope.launch {
                        WindowAppearanceSettingsManager.updateSettings(
                            WindowAppearanceSettingsManager.currentSettings.value
                                .copy(tabBarVerticalWidth = width),
                        )
                    }
                },
            )
        }
        VDivider()
        splitTree(Modifier.weight(1f).fillMaxHeight())
    }
}

/**
 * The split tree as a slot, so the two layouts below can each place it where they want it.
 *
 * A `remember`, not a fresh lambda each pass: the vertical layout hands it to `WindowBarRow`,
 * which is not skippable, and a new lambda identity every recomposition would recompose the whole
 * bar alongside the tree it wraps.
 */
@Composable
private fun rememberSplitTree(
    splitViewState: SplitViewState,
    tabDragComponent: TabDraggableComponent?,
    onTabDropResult: (TabDropResult) -> Unit,
    barIsVertical: Boolean,
): @Composable (Modifier) -> Unit =
    remember(splitViewState, tabDragComponent, onTabDropResult, barIsVertical) {
        { treeModifier ->
            Box(modifier = treeModifier) {
                SplitOrZoomedPane(
                    splitViewState = splitViewState,
                    tabDragComponent = tabDragComponent,
                    onTabDropResult = onTabDropResult,
                    // A panel draws its own bar only when this one is not drawing it for them.
                    showPanelTabBar = !barIsVertical,
                )
            }
        }
    }

/**
 * The hover-revealed bar, with everything the pinned one carries.
 *
 * Split out of [SplitViewPanel], which is at detekt's length ceiling. Both slots are passed
 * because while this is open it is the only bar on screen - the in-flow one is down to its rail -
 * so anything missing here is missing outright, not merely missing from a preview.
 */
@Composable
@Suppress("LongParameterList")
private fun BoxScope.RevealedBar(
    splitViewState: SplitViewState,
    bar: TabBarLayout,
    reveal: TabBarRevealState,
    contentRegion: IntRect?,
    topInset: Dp,
    footer: @Composable () -> Unit,
    belowMap: @Composable () -> Unit,
) {
    WindowRevealedTabBarDrawer(
        splitViewState = splitViewState,
        bar = bar,
        reveal = reveal,
        // Started BELOW the traffic-light clearance rather than padded inside it. The drawer is
        // its own always-on-top window, so the lights are behind it whatever it pads - the only
        // way to leave them visible is to not cover them. The region is already in dp.
        contentRegion = contentRegion.below(topInset),
        footer = footer,
        belowMap = belowMap,
        onPin = rememberPinDrawerAction(reveal, bar),
    )
}

/**
 * The region with its top moved down by [inset].
 *
 * Used to start the hover drawer BELOW the macOS traffic lights. The drawer is its own
 * always-on-top window, so the lights are behind it whatever it pads - the only way to leave them
 * visible is to not cover them. The region is already in dp, which is what [inset] is.
 */
private fun IntRect?.below(inset: Dp): IntRect? {
    val region = this ?: return null
    val dp = inset.value.roundToInt()
    return if (dp <= 0) region else region.copy(top = region.top + dp)
}

@Composable
private fun RenderSplitNode(
    node: SplitNode,
    splitViewState: SplitViewState,
    tabDragComponent: TabDraggableComponent? = null,
    onTabDropResult: (TabDropResult) -> Unit = {},
    showPanelTabBar: Boolean = true,
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
                        splitViewState.releaseFocusRequester(node.id)
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
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { coordinates ->
                                val bounds = coordinates.boundsInRoot()
                                splitViewState.updatePanelBounds(
                                    panelId = node.id,
                                    bounds =
                                        PanelBounds(
                                            x = bounds.left,
                                            y = bounds.top,
                                            width = bounds.width,
                                            height = bounds.height,
                                        ),
                                )
                                // Register panel drop zones for drag system
                                if (tabDragComponent != null) {
                                    val windowBounds = coordinates.boundsInWindow()
                                    tabDragComponent.registerPanelDropZones(node.id, windowBounds)
                                }
                            },
                ) {
                    node.tabsComponent.BossMainPanel(
                        splitViewState = splitViewState,
                        currentPanelId = node.id,
                        tabDragComponent = tabDragComponent,
                        onTabDropResult = onTabDropResult,
                        showTabBar = showPanelTabBar,
                    )

                    // Show drop zone highlights when dragging over this panel
                    if (isDragging && draggingTab != null && draggingTab.sourcePanelId != node.id) {
                        PanelDropZoneOverlay(
                            panelId = node.id,
                            dropTarget = dropTarget,
                            // The zones themselves start after a vertical tab bar that covers
                            // this panel's leading edge (see PanelDropZones.fromBounds), so the
                            // highlight has to as well - a left-split band painted over the bar
                            // would advertise a drop that means something else entirely.
                            //
                            // Measured off the ZONES rather than off a bar, which is the only
                            // reading that cannot disagree with them: a window-level bar is
                            // registered against every panel and covers none of them, so a
                            // width taken from it would inset every highlight by a bar that is
                            // nowhere near the panel.
                            leadingInset =
                                tabDragComponent.panelDropZones[node.id]
                                    ?.let { zones ->
                                        with(LocalDensity.current) {
                                            (zones.leftZone.left - zones.panelBounds.left).toDp()
                                        }
                                    }?.coerceAtLeast(0.dp)
                                    ?: 0.dp,
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
                        showPanelTabBar = showPanelTabBar,
                    )
                },
                sideContent = {
                    RenderSplitNode(
                        node = node.right,
                        splitViewState = splitViewState,
                        tabDragComponent = tabDragComponent,
                        onTabDropResult = onTabDropResult,
                        showPanelTabBar = showPanelTabBar,
                    )
                },
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
                        showPanelTabBar = showPanelTabBar,
                    )
                },
                sideContent = {
                    RenderSplitNode(
                        node = node.bottom,
                        splitViewState = splitViewState,
                        tabDragComponent = tabDragComponent,
                        onTabDropResult = onTabDropResult,
                        showPanelTabBar = showPanelTabBar,
                    )
                },
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
    dropTarget: TabDropTarget?,
    leadingInset: Dp = 0.dp,
) {
    // Check which zone is highlighted
    val isLeftHighlighted =
        dropTarget is TabDropTarget.SplitPanel &&
            dropTarget.panelId == panelId &&
            dropTarget.orientation == SplitOrientation.VERTICAL

    val isRightHighlighted = isLeftHighlighted // Same condition for vertical split

    val isTopHighlighted =
        dropTarget is TabDropTarget.SplitPanel &&
            dropTarget.panelId == panelId &&
            dropTarget.orientation == SplitOrientation.HORIZONTAL

    val isBottomHighlighted = isTopHighlighted // Same condition for horizontal split

    val isCenterHighlighted =
        dropTarget is TabDropTarget.ExistingPanel &&
            dropTarget.panelId == panelId

    Box(modifier = Modifier.fillMaxSize().padding(start = leadingInset)) {
        // Left edge highlight
        if (isLeftHighlighted) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.CenterStart)
                        .width(60.dp)
                        .fillMaxHeight()
                        .alpha(0.3f)
                        .background(BossTheme.colors.signal),
            )
        }

        // Right edge highlight
        if (isRightHighlighted) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .width(60.dp)
                        .fillMaxHeight()
                        .alpha(0.3f)
                        .background(BossTheme.colors.signal),
            )
        }

        // Top edge highlight
        if (isTopHighlighted) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(60.dp)
                        .alpha(0.3f)
                        .background(BossTheme.colors.signal),
            )
        }

        // Bottom edge highlight
        if (isBottomHighlighted) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(60.dp)
                        .alpha(0.3f)
                        .background(BossTheme.colors.signal),
            )
        }

        // Center highlight (add to existing panel)
        if (isCenterHighlighted) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .alpha(0.15f)
                        .background(BossTheme.colors.signal),
            )
        }
    }
}
