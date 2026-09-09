package ai.rever.boss.window

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

/**
 * Handler for menu actions that need to be processed by BossApp.
 *
 * This provides a communication bridge between the MenuBar (in BossWindow)
 * and the app logic (in BossApp) which are at different levels in the
 * composition tree.
 *
 * Similar to WindowOperations, this uses a flow-based event system to
 * decouple the menu UI from the business logic.
 */
object MenuActionsHandler {
    private val logger = BossLogger.forComponent("MenuActionsHandler")

    private val _newTabEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val newTabEvents: SharedFlow<String> = _newTabEvents.asSharedFlow()

    private val _closeTabEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val closeTabEvents: SharedFlow<String> = _closeTabEvents.asSharedFlow()

    /** [triggerSelectTabByIndex] sentinel meaning "whatever the last tab is". */
    const val LAST_TAB_INDEX = -1

    /**
     * Tab-cycling actions. These are intentionally carried on a SINGLE flow (below) rather
     * than one flow per action: a "next/previous" step and the "commit" that follows it must
     * be observed in emission order, which is only guaranteed within one ordered stream.
     */
    enum class TabSwitchAction {
        NEXT,
        PREVIOUS,
        COMMIT,

        /**
         * Step in tab-bar order regardless of the configured TabSwitchMode, and start no MRU
         * cycle. Cmd+Opt+Arrow / Cmd+Shift+Bracket are browser chords with no modifier-release
         * gesture to commit on, so an MRU cycle armed by them would never be committed.
         */
        NEXT_POSITIONAL,
        PREVIOUS_POSITIONAL,
    }

    // Single ordered stream for Ctrl+Tab so a step is always delivered before its commit.
    private val _tabSwitchEvents = MutableSharedFlow<Pair<String, TabSwitchAction>>(extraBufferCapacity = 10)
    val tabSwitchEvents: SharedFlow<Pair<String, TabSwitchAction>> = _tabSwitchEvents.asSharedFlow()

    /**
     * Select a tab by position: (windowId, index), where index -1 means "the last tab"
     * (Cmd+9's browser meaning). Out-of-range positions are ignored by the collector.
     */
    private val _selectTabIndexEvents = MutableSharedFlow<Pair<String, Int>>(extraBufferCapacity = 10)
    val selectTabIndexEvents: SharedFlow<Pair<String, Int>> = _selectTabIndexEvents.asSharedFlow()

    private val _reopenClosedTabEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val reopenClosedTabEvents: SharedFlow<String> = _reopenClosedTabEvents.asSharedFlow()

    private val _browserBackEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val browserBackEvents: SharedFlow<String> = _browserBackEvents.asSharedFlow()

    private val _browserForwardEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val browserForwardEvents: SharedFlow<String> = _browserForwardEvents.asSharedFlow()

    private val _browserDevToolsEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val browserDevToolsEvents: SharedFlow<String> = _browserDevToolsEvents.asSharedFlow()

    private val _zoomInEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val zoomInEvents: SharedFlow<String> = _zoomInEvents.asSharedFlow()

    private val _zoomOutEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val zoomOutEvents: SharedFlow<String> = _zoomOutEvents.asSharedFlow()

    private val _actualSizeEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val actualSizeEvents: SharedFlow<String> = _actualSizeEvents.asSharedFlow()

    private val _openProjectEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val openProjectEvents: SharedFlow<String> = _openProjectEvents.asSharedFlow()

    private val _openFileEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val openFileEvents: SharedFlow<String> = _openFileEvents.asSharedFlow()

    private val _newTerminalEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val newTerminalEvents: SharedFlow<String> = _newTerminalEvents.asSharedFlow()

    private val _selectWorkspaceEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val selectWorkspaceEvents: SharedFlow<String> = _selectWorkspaceEvents.asSharedFlow()

    private val _openSettingsEvents = MutableSharedFlow<Pair<String, String?>>(extraBufferCapacity = 10)
    val openSettingsEvents: SharedFlow<Pair<String, String?>> = _openSettingsEvents.asSharedFlow()

    private val _toggleFocusModeEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val toggleFocusModeEvents: SharedFlow<String> = _toggleFocusModeEvents.asSharedFlow()

    private val _splitVerticallyEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val splitVerticallyEvents: SharedFlow<String> = _splitVerticallyEvents.asSharedFlow()

    private val _splitHorizontallyEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val splitHorizontallyEvents: SharedFlow<String> = _splitHorizontallyEvents.asSharedFlow()

    private val _revealPluginEvents = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 10)
    val revealPluginEvents: SharedFlow<Pair<String, String>> = _revealPluginEvents.asSharedFlow()

    private val _reloadBrowserEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val reloadBrowserEvents: SharedFlow<String> = _reloadBrowserEvents.asSharedFlow()

    private val _browserFindEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val browserFindEvents: SharedFlow<String> = _browserFindEvents.asSharedFlow()

    private val _saveWorkspaceEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val saveWorkspaceEvents: SharedFlow<String> = _saveWorkspaceEvents.asSharedFlow()

    private val _openCodebaseEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val openCodebaseEvents: SharedFlow<String> = _openCodebaseEvents.asSharedFlow()

    private val _openGlobalSearchEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val openGlobalSearchEvents: SharedFlow<String> = _openGlobalSearchEvents.asSharedFlow()

    private val _navigatePanelLeftEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val navigatePanelLeftEvents: SharedFlow<String> = _navigatePanelLeftEvents.asSharedFlow()

    private val _navigatePanelRightEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val navigatePanelRightEvents: SharedFlow<String> = _navigatePanelRightEvents.asSharedFlow()

    private val _navigatePanelUpEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val navigatePanelUpEvents: SharedFlow<String> = _navigatePanelUpEvents.asSharedFlow()

    private val _navigatePanelDownEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val navigatePanelDownEvents: SharedFlow<String> = _navigatePanelDownEvents.asSharedFlow()

    // Customize-Sidebar pending triggers, keyed by windowId. We use a
    // StateFlow rather than a SharedFlow so the request survives the brief
    // window between "menu item clicked" and "sidebar (and the
    // SidebarCustomizeMenu inside it) finishes composing" — relevant in
    // focus mode where the sidebar is hidden until BossApp force-reveals
    // it on receipt of the same trigger.
    //
    // The map is keyed by windowId (rather than a single Pair) so a click
    // in window A can't overwrite a still-pending request in window B —
    // each window has its own slot.
    //
    // The Long value is a monotonic request id (see [_customizeRequestId]);
    // StateFlow collapses identical values, so without a unique payload a
    // re-trigger of the same windowId between clears wouldn't re-fire.
    // Subscribers don't compare ids — presence in the map is what they act
    // on, and [clearCustomizeSidebarTrigger] removes the entry once
    // handled.
    private val _customizeSidebarTriggers = MutableStateFlow<Map<String, Long>>(emptyMap())
    val customizeSidebarTriggers: StateFlow<Map<String, Long>> = _customizeSidebarTriggers.asStateFlow()

    // Monotonic counter for customize-sidebar request ids. Using a
    // counter (atomic via updateAndGet) instead of wall-clock millis
    // removes the (vanishingly rare but real) collision risk of two
    // triggers fired in the same millisecond producing the same value
    // and being collapsed by StateFlow equality.
    private val _customizeRequestId = MutableStateFlow(0L)

    // State for enabling/disabling split menu items per window (windowId -> hasActiveTabs)
    private val _splitEnabledState = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val splitEnabledState: StateFlow<Map<String, Boolean>> = _splitEnabledState.asStateFlow()

    // State for tracking panel count per window (windowId -> panelCount)
    private val _panelCountState = MutableStateFlow<Map<String, Int>>(emptyMap())
    val panelCountState: StateFlow<Map<String, Int>> = _panelCountState.asStateFlow()

    private val _activePanelTabCountState = MutableStateFlow<Map<String, Int>>(emptyMap())

    /**
     * How many tabs the ACTIVE panel holds, per window.
     *
     * Drives the enabled flag on View > Next Tab / Previous Tab, which must grey out with one
     * tab rather than no-op: their accelerator is a MenuBar accelerator, so an always-enabled
     * item consumes its chord window-wide even when stepping cannot do anything. Under BOSS
     * Default that eats Cmd+Opt+Arrow; under the VS Code and IntelliJ presets the primary falls
     * back to Cmd+Shift+Bracket, so a single-tab editor would lose those instead.
     */
    val activePanelTabCountState: StateFlow<Map<String, Int>> = _activePanelTabCountState.asStateFlow()

    /**
     * Update whether split is enabled for a window.
     * Split should be enabled when there are tabs in the active panel.
     *
     * @param windowId The window ID
     * @param enabled Whether split should be enabled
     */
    fun updateSplitEnabled(
        windowId: String,
        enabled: Boolean,
    ) {
        _splitEnabledState.value = _splitEnabledState.value + (windowId to enabled)
    }

    /**
     * Check if split is enabled for a window.
     *
     * @param windowId The window ID
     * @return True if split is enabled (has active tabs), false otherwise
     */
    fun isSplitEnabled(windowId: String): Boolean = _splitEnabledState.value[windowId] ?: false

    /** Publish the active panel's tab count for [activePanelTabCountState]. */
    fun updateActivePanelTabCount(
        windowId: String,
        count: Int,
    ) {
        _activePanelTabCountState.update { it + (windowId to count) }
    }

    /**
     * How many tabs the active panel of [windowId] holds, 0 before the first panel composes.
     *
     * Drives the interceptor's Cmd+1..Cmd+9 gate as well as [canStepTabs].
     *
     * The 0 default means those chords PROPAGATE for the frames between the interceptor going
     * live and the publishing LaunchedEffect landing, while [panelCountState] defaults to 1 and
     * so lets panel navigation through in the same window. Opposite defaults, deliberately: each
     * one fails toward "do not claim a chord we cannot serve", which for a count of tabs is 0 and
     * for a count of panels is 1.
     */
    fun activePanelTabCount(windowId: String): Int = _activePanelTabCountState.value[windowId] ?: 0

    /** Whether tab stepping can do anything in [windowId] right now. */
    fun canStepTabs(windowId: String): Boolean = activePanelTabCount(windowId) > 1

    /**
     * Update the panel count for a window.
     * Panel navigation should be enabled when there are multiple panels.
     *
     * @param windowId The window ID
     * @param count The number of panels in the window
     */
    fun updatePanelCount(
        windowId: String,
        count: Int,
    ) {
        _panelCountState.value = _panelCountState.value + (windowId to count)
    }

    /**
     * Clean up state for a closed window to prevent memory leaks.
     * Should be called from window's DisposableEffect onDispose.
     *
     * Note: SharedFlow event buffers are not cleared per-window because:
     * - Events naturally expire as new events push old ones out (buffer size 10)
     * - Events only contain small String windowIds (~36 bytes each)
     * - Subscribers filter by windowId, ignoring events for closed windows
     *
     * @param windowId The window ID to clean up
     */
    fun cleanupWindow(windowId: String) {
        _splitEnabledState.update { it - windowId }
        _panelCountState.update { it - windowId }
        _activePanelTabCountState.update { it - windowId }
        // Co-located with the other per-window state rather than left to the window-close path
        // alone: this one holds up to 25 TabInfos per window, so a future close path that
        // forgot it would strand them for the life of the process.
        ClosedTabHistory.clear(windowId)
        // If the window was closed before the customize-sidebar request
        // it triggered was handled, drop the orphaned entry so it doesn't
        // accumulate in the map across the session.
        _customizeSidebarTriggers.update { it - windowId }
    }

    /**
     * Trigger a "New Tab" action for the specified window.
     *
     * @param windowId The ID of the window where the action was triggered
     */
    fun triggerNewTab(windowId: String) {
        _newTabEvents.tryEmit(windowId)
    }

    /**
     * Trigger a "Close Tab" action for the specified window.
     *
     * @param windowId The ID of the window where the action was triggered
     */
    fun triggerCloseTab(windowId: String) {
        _closeTabEvents.tryEmit(windowId)
    }

    /**
     * Trigger a "Next Tab" action for the specified window (Ctrl+Tab).
     *
     * @param windowId The ID of the window where the action was triggered
     */
    fun triggerNextTab(windowId: String) {
        _tabSwitchEvents.tryEmit(windowId to TabSwitchAction.NEXT)
    }

    /**
     * Trigger a "Previous Tab" action for the specified window (Ctrl+Shift+Tab).
     *
     * @param windowId The ID of the window where the action was triggered
     */
    fun triggerPreviousTab(windowId: String) {
        _tabSwitchEvents.tryEmit(windowId to TabSwitchAction.PREVIOUS)
    }

    /**
     * Trigger a "Next Tab" in tab-bar order (Cmd+Opt+Right), ignoring the MRU setting.
     *
     * @param windowId The ID of the window where the action was triggered
     */
    fun triggerNextTabPositional(windowId: String) {
        _tabSwitchEvents.tryEmit(windowId to TabSwitchAction.NEXT_POSITIONAL)
    }

    /**
     * Trigger a "Previous Tab" in tab-bar order (Cmd+Opt+Left), ignoring the MRU setting.
     *
     * @param windowId The ID of the window where the action was triggered
     */
    fun triggerPreviousTabPositional(windowId: String) {
        _tabSwitchEvents.tryEmit(windowId to TabSwitchAction.PREVIOUS_POSITIONAL)
    }

    /**
     * Select the tab at [index] in the active panel (Cmd+1..Cmd+8, zero-based).
     */
    fun triggerSelectTabByIndex(
        windowId: String,
        index: Int,
    ) {
        _selectTabIndexEvents.tryEmit(windowId to index)
    }

    /** Select the last tab in the active panel (Cmd+9). */
    fun triggerSelectLastTab(windowId: String) {
        _selectTabIndexEvents.tryEmit(windowId to LAST_TAB_INDEX)
    }

    /** Reopen the most recently closed tab in this window (Cmd+Shift+T). */
    fun triggerReopenClosedTab(windowId: String) {
        _reopenClosedTabEvents.tryEmit(windowId)
    }

    /** Browser history back for the window's active browser (Cmd+[). */
    fun triggerBrowserBack(windowId: String) {
        _browserBackEvents.tryEmit(windowId)
    }

    /** Browser history forward for the window's active browser (Cmd+]). */
    fun triggerBrowserForward(windowId: String) {
        _browserForwardEvents.tryEmit(windowId)
    }

    /** Open DevTools on the window's active browser (Cmd+Opt+I). */
    fun triggerBrowserDevTools(windowId: String) {
        _browserDevToolsEvents.tryEmit(windowId)
    }

    /**
     * Commit an in-progress MRU tab cycle for the specified window, fired when the
     * cycling modifier (e.g. Ctrl) is released. No-op in positional mode.
     *
     * @param windowId The ID of the window where the action was triggered
     */
    fun triggerCommitTabCycle(windowId: String) {
        _tabSwitchEvents.tryEmit(windowId to TabSwitchAction.COMMIT)
    }

    /**
     * Trigger a "Zoom In" action for the specified window.
     *
     * @param windowId The ID of the window where the action was triggered
     */
    fun triggerZoomIn(windowId: String) {
        _zoomInEvents.tryEmit(windowId)
    }

    /**
     * Trigger a "Zoom Out" action for the specified window.
     *
     * @param windowId The ID of the window where the action was triggered
     */
    fun triggerZoomOut(windowId: String) {
        _zoomOutEvents.tryEmit(windowId)
    }

    /**
     * Trigger an "Actual Size" action for the specified window.
     *
     * @param windowId The ID of the window where the action was triggered
     */
    fun triggerActualSize(windowId: String) {
        _actualSizeEvents.tryEmit(windowId)
    }

    /**
     * Trigger an "Open Project" action for the specified window.
     *
     * @param windowId The ID of the window where the action was triggered
     */
    fun triggerOpenProject(windowId: String) {
        _openProjectEvents.tryEmit(windowId)
    }

    /**
     * Trigger an "Open File" action for the specified window.
     *
     * @param windowId The ID of the window where the action was triggered
     */
    fun triggerOpenFile(windowId: String) {
        _openFileEvents.tryEmit(windowId)
    }

    /**
     * Trigger a "New Terminal" action for the specified window.
     *
     * @param windowId The ID of the window where the action was triggered
     */
    fun triggerNewTerminal(windowId: String) {
        _newTerminalEvents.tryEmit(windowId)
    }

    /**
     * Trigger a "Select Workspace" action for the specified window.
     *
     * @param windowId The ID of the window where the action was triggered
     */
    fun triggerSelectWorkspace(windowId: String) {
        _selectWorkspaceEvents.tryEmit(windowId)
    }

    /**
     * Trigger an "Open Settings" action for the specified window.
     *
     * @param windowId The ID of the window where the action was triggered
     * @param section Optional section name to navigate to (e.g., "TERMINAL", "KEYMAP")
     */
    fun triggerOpenSettings(
        windowId: String,
        section: String? = null,
    ) {
        _openSettingsEvents.tryEmit(windowId to section)
    }

    /**
     * Trigger a "Toggle Focus Mode" action for the specified window.
     *
     * @param windowId The ID of the window where the action was triggered
     */
    fun triggerToggleFocusMode(windowId: String) {
        _toggleFocusModeEvents.tryEmit(windowId)
    }

    /**
     * Trigger a "Split Vertically" action for the specified window.
     *
     * @param windowId The ID of the window where the action was triggered
     */
    fun triggerSplitVertically(windowId: String) {
        _splitVerticallyEvents.tryEmit(windowId)
    }

    /**
     * Trigger a "Split Horizontally" action for the specified window.
     *
     * @param windowId The ID of the window where the action was triggered
     */
    fun triggerSplitHorizontally(windowId: String) {
        _splitHorizontallyEvents.tryEmit(windowId)
    }

    private val _applyWorkspaceEvents =
        MutableSharedFlow<Pair<String, ai.rever.boss.components.workspaces.LayoutWorkspace>>(extraBufferCapacity = 10)
    val applyWorkspaceEvents: SharedFlow<Pair<String, ai.rever.boss.components.workspaces.LayoutWorkspace>> =
        _applyWorkspaceEvents
            .asSharedFlow()

    /**
     * Trigger an "Apply Workspace" action for the specified window.
     *
     * @param windowId The ID of the window where the action was triggered
     * @param workspace The workspace to apply
     */
    fun triggerApplyWorkspace(
        windowId: String,
        workspace: ai.rever.boss.components.workspaces.LayoutWorkspace,
    ) {
        _applyWorkspaceEvents.tryEmit(Pair(windowId, workspace))
    }

    /**
     * Trigger a "Reveal Plugin" action for the specified window and panel.
     *
     * @param windowId The ID of the window where the action was triggered
     * @param panelId The PANEL id to reveal - `PanelId.panelId`, e.g. "bookmarks". Not the
     *   plugin id: the handler resolves it through `BossDraggableComponent.activatePlugin`, which
     *   matches panel ids and returns silently when nothing matches.
     */
    fun triggerRevealPlugin(
        windowId: String,
        panelId: String,
    ) {
        _revealPluginEvents.tryEmit(Pair(windowId, panelId))
    }

    /**
     * Trigger a "Reload Browser" action for the specified window.
     *
     * @param windowId The ID of the window where the action was triggered
     */
    fun triggerReloadBrowser(windowId: String) {
        _reloadBrowserEvents.tryEmit(windowId)
    }

    /**
     * Trigger a "Browser Find" action for the specified window.
     *
     * @param windowId The ID of the window where the action was triggered
     */
    fun triggerBrowserFind(windowId: String) {
        _browserFindEvents.tryEmit(windowId)
    }

    /**
     * Trigger a "Save Workspace" action for the specified window.
     *
     * @param windowId The ID of the window where the action was triggered
     */
    fun triggerSaveWorkspace(windowId: String) {
        _saveWorkspaceEvents.tryEmit(windowId)
    }

    /**
     * Trigger an "Open Codebase" action for the specified window.
     *
     * @param windowId The ID of the window where the action was triggered
     */
    fun triggerOpenCodebase(windowId: String) {
        _openCodebaseEvents.tryEmit(windowId)
    }

    /**
     * Trigger an "Open Global Search" action for the specified window.
     *
     * @param windowId The ID of the window where the action was triggered
     */
    fun triggerOpenGlobalSearch(windowId: String) {
        _openGlobalSearchEvents.tryEmit(windowId)
    }

    /**
     * Trigger a "Navigate Panel Left" action for the specified window.
     *
     * @param windowId The ID of the window where the action was triggered
     */
    fun triggerNavigatePanelLeft(windowId: String) {
        _navigatePanelLeftEvents.tryEmit(windowId)
    }

    /**
     * Trigger a "Navigate Panel Right" action for the specified window.
     *
     * @param windowId The ID of the window where the action was triggered
     */
    fun triggerNavigatePanelRight(windowId: String) {
        _navigatePanelRightEvents.tryEmit(windowId)
    }

    /**
     * Trigger a "Navigate Panel Up" action for the specified window.
     *
     * @param windowId The ID of the window where the action was triggered
     */
    fun triggerNavigatePanelUp(windowId: String) {
        _navigatePanelUpEvents.tryEmit(windowId)
    }

    /**
     * Trigger a "Navigate Panel Down" action for the specified window.
     *
     * @param windowId The ID of the window where the action was triggered
     */
    fun triggerNavigatePanelDown(windowId: String) {
        _navigatePanelDownEvents.tryEmit(windowId)
    }

    /**
     * Trigger the "Customize Sidebar" action for the specified window.
     * Opens the in-app customize popup anchored to the three-dot button.
     *
     * @param windowId The ID of the window where the action was triggered
     */
    fun triggerCustomizeSidebar(windowId: String) {
        val id = _customizeRequestId.updateAndGet { it + 1 }
        _customizeSidebarTriggers.update { it + (windowId to id) }
        logger.debug(
            LogCategory.UI,
            "Customize-sidebar trigger fired",
            mapOf(
                "windowId" to windowId,
                "requestId" to id.toString(),
            ),
        )
    }

    /**
     * Acknowledge that the customize-sidebar request for [windowId] has
     * been handled. Called by SidebarCustomizeMenu after opening its popup
     * so that subsequent recompositions (e.g. sidebar hide/reveal in focus
     * mode) don't re-open the menu. BossApp's force-reveal subscriber does
     * not clear — it only ever reveals sidebars and is idempotent.
     */
    fun clearCustomizeSidebarTrigger(windowId: String) {
        _customizeSidebarTriggers.update { it - windowId }
    }

    private val _showShortcutHelpEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val showShortcutHelpEvents: SharedFlow<String> = _showShortcutHelpEvents.asSharedFlow()

    /**
     * Trigger a "Show Shortcut Help" action for the specified window.
     *
     * @param windowId The ID of the window where the action was triggered
     */
    fun triggerShowShortcutHelp(windowId: String) {
        _showShortcutHelpEvents.tryEmit(windowId)
    }

    // ========== Refactoring Events ==========

    private val _refactorRenameEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val refactorRenameEvents: SharedFlow<String> = _refactorRenameEvents.asSharedFlow()

    private val _refactorExtractVariableEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val refactorExtractVariableEvents: SharedFlow<String> = _refactorExtractVariableEvents.asSharedFlow()

    private val _refactorExtractMethodEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val refactorExtractMethodEvents: SharedFlow<String> = _refactorExtractMethodEvents.asSharedFlow()

    private val _refactorExtractConstantEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val refactorExtractConstantEvents: SharedFlow<String> = _refactorExtractConstantEvents.asSharedFlow()

    private val _refactorInlineEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val refactorInlineEvents: SharedFlow<String> = _refactorInlineEvents.asSharedFlow()

    private val _refactorChangeSignatureEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val refactorChangeSignatureEvents: SharedFlow<String> = _refactorChangeSignatureEvents.asSharedFlow()

    private val _refactorSafeDeleteEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val refactorSafeDeleteEvents: SharedFlow<String> = _refactorSafeDeleteEvents.asSharedFlow()

    /**
     * Trigger a "Rename" refactoring action for the specified window.
     */
    fun triggerRefactorRename(windowId: String) {
        _refactorRenameEvents.tryEmit(windowId)
    }

    /**
     * Trigger an "Extract Variable" refactoring action for the specified window.
     */
    fun triggerRefactorExtractVariable(windowId: String) {
        _refactorExtractVariableEvents.tryEmit(windowId)
    }

    /**
     * Trigger an "Extract Method" refactoring action for the specified window.
     */
    fun triggerRefactorExtractMethod(windowId: String) {
        _refactorExtractMethodEvents.tryEmit(windowId)
    }

    /**
     * Trigger an "Extract Constant" refactoring action for the specified window.
     */
    fun triggerRefactorExtractConstant(windowId: String) {
        _refactorExtractConstantEvents.tryEmit(windowId)
    }

    /**
     * Trigger an "Inline" refactoring action for the specified window.
     */
    fun triggerRefactorInline(windowId: String) {
        _refactorInlineEvents.tryEmit(windowId)
    }

    /**
     * Trigger a "Change Signature" refactoring action for the specified window.
     */
    fun triggerRefactorChangeSignature(windowId: String) {
        _refactorChangeSignatureEvents.tryEmit(windowId)
    }

    /**
     * Trigger a "Safe Delete" refactoring action for the specified window.
     */
    fun triggerRefactorSafeDelete(windowId: String) {
        _refactorSafeDeleteEvents.tryEmit(windowId)
    }

    // ========== Plugin Reload Events ==========

    private val _reloadAllPluginsEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val reloadAllPluginsEvents: SharedFlow<String> = _reloadAllPluginsEvents.asSharedFlow()

    private val _reloadPluginEvents = MutableSharedFlow<Pair<String, ai.rever.boss.plugin.api.PanelId>>(extraBufferCapacity = 10)
    val reloadPluginEvents: SharedFlow<Pair<String, ai.rever.boss.plugin.api.PanelId>> = _reloadPluginEvents.asSharedFlow()

    private val _checkPluginUpdatesEvents = MutableSharedFlow<Pair<String, ai.rever.boss.plugin.api.PanelId>>(extraBufferCapacity = 10)
    val checkPluginUpdatesEvents: SharedFlow<Pair<String, ai.rever.boss.plugin.api.PanelId>> = _checkPluginUpdatesEvents.asSharedFlow()

    private val _installStoreVersionEvents =
        MutableSharedFlow<Pair<String, ai.rever.boss.plugin.api.PanelId>>(extraBufferCapacity = 10)
    val installStoreVersionEvents: SharedFlow<Pair<String, ai.rever.boss.plugin.api.PanelId>> =
        _installStoreVersionEvents.asSharedFlow()

    private val _uninstallPluginEvents =
        MutableSharedFlow<Pair<String, ai.rever.boss.plugin.api.PanelId>>(extraBufferCapacity = 10)
    val uninstallPluginEvents: SharedFlow<Pair<String, ai.rever.boss.plugin.api.PanelId>> =
        _uninstallPluginEvents.asSharedFlow()

    /**
     * Trigger a "Reload All Plugins" action for the specified window.
     *
     * @param windowId The ID of the window where the action was triggered
     */
    fun triggerReloadAllPlugins(windowId: String) {
        _reloadAllPluginsEvents.tryEmit(windowId)
    }

    /**
     * Trigger the "Reload Panel" action for a specific panel in the specified window. Despite the
     * menu's wording this reloads the owning plugin's jar and resets its slots in every window.
     *
     * @param windowId The ID of the window where the action was triggered
     * @param panelId The PanelId used to look up the owning plugin
     */
    fun triggerReloadPlugin(
        windowId: String,
        panelId: ai.rever.boss.plugin.api.PanelId,
    ) {
        _reloadPluginEvents.tryEmit(Pair(windowId, panelId))
    }

    /**
     * Trigger a "Check for Updates" action for the plugin owning a specific panel.
     *
     * @param windowId The ID of the window where the action was triggered
     * @param panelId The PanelId used to look up the owning plugin
     */
    fun triggerCheckPluginUpdates(
        windowId: String,
        panelId: ai.rever.boss.plugin.api.PanelId,
    ) {
        _checkPluginUpdatesEvents.tryEmit(Pair(windowId, panelId))
    }

    /**
     * Trigger "install the store version" for the plugin owning a specific panel, from its build tag
     * or the version row in its overflow menu.
     *
     * @param windowId The ID of the window where the action was triggered
     * @param panelId The PanelId used to look up the owning plugin
     */
    fun triggerInstallStoreVersion(
        windowId: String,
        panelId: ai.rever.boss.plugin.api.PanelId,
    ) {
        _installStoreVersionEvents.tryEmit(Pair(windowId, panelId))
    }

    /**
     * Trigger "Uninstall Plugin" for the plugin owning a specific panel. Raises a confirmation
     * prompt; nothing is removed until it is confirmed.
     *
     * @param windowId The ID of the window where the action was triggered
     * @param panelId The PanelId used to look up the owning plugin
     */
    fun triggerUninstallPlugin(
        windowId: String,
        panelId: ai.rever.boss.plugin.api.PanelId,
    ) {
        _uninstallPluginEvents.tryEmit(Pair(windowId, panelId))
    }

    // ========== Plugin Wizard Events ==========

    private val _showPluginWizardEvents = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val showPluginWizardEvents: SharedFlow<String> = _showPluginWizardEvents.asSharedFlow()

    /**
     * Trigger a "Show Plugin Wizard" action for the specified window.
     *
     * @param windowId The ID of the window where the action was triggered
     */
    fun triggerShowPluginWizard(windowId: String) {
        _showPluginWizardEvents.tryEmit(windowId)
    }
}
