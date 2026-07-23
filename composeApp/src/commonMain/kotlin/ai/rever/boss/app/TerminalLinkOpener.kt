package ai.rever.boss.app

import ai.rever.boss.components.dialogs.openUrlInBrowser
import ai.rever.boss.components.events.NavigationTargetBus
import ai.rever.boss.components.events.RunnerTerminalOpenEvent
import ai.rever.boss.components.events.parseFileReference
import ai.rever.boss.components.events.stripFilePrefix
import ai.rever.boss.components.events.validateFilePath
import ai.rever.boss.components.registery.TabTypeId
import ai.rever.boss.components.window_panel.SplitOrientation
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.platform.openFileWithSystemDefault
import ai.rever.boss.plugin.events.FileValidationResult
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.terminal.ExistingSplitTargetMode
import ai.rever.boss.terminal.TerminalLinkOpenMode
import ai.rever.boss.terminal.TerminalLinkSettingsManager
import ai.rever.boss.utils.extractFileName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Helper function to open a runner terminal in the main panel.
 * Creates a terminal tab with the run command and adds it to the active panel.
 */
internal fun openRunnerInMainPanel(
    event: RunnerTerminalOpenEvent,
    splitViewState: SplitViewState
) {
    // Create terminal tab in active panel
    val terminalTab = TerminalTabInfo(
        id = event.terminalId,
        typeId = TabTypeId("terminal"),
        title = "Run: ${event.configName}",
        initialCommand = event.command,
        workingDirectory = event.workingDirectory
    )

    // Find existing tab or create new one
    val existingPanel = splitViewState.findPanelWithTab(event.terminalId)
    if (existingPanel != null && event.isRerun) {
        // Re-run: Update existing tab with new command
        existingPanel.tabsComponent.removeTabById(event.terminalId)
    }

    // Add to active panel (or first available)
    val activeComponent = splitViewState.getActiveTabsComponent()
        ?: splitViewState.getAllPanels().firstOrNull()?.tabsComponent

    if (activeComponent != null) {
        val tabIndex = activeComponent.addTab(terminalTab)
        if (tabIndex >= 0) {
            activeComponent.selectTab(tabIndex)
        }
    }
}

/**
 * Helper function to open a terminal link based on user's selected mode.
 * Handles creating browser tabs (for HTTP) or editor tabs (for file:) and splitting panels.
 *
 * Issue #346: Terminal link click prompt with remember preference
 * Issue #506: Added windowId for multi-window navigation filtering
 *
 * @param url The URL to open (HTTP or file: URL)
 * @param mode How to open the link (split or new tab)
 * @param splitViewState The split view state for panel operations
 * @param sourceTerminalId Optional terminal tab ID where the link was clicked (for finding source panel)
 * @param scope CoroutineScope for launching navigation events (structured concurrency)
 * @param windowId The window ID for multi-window filtering (Issue #506)
 */
internal fun openTerminalLink(
    url: String,
    mode: TerminalLinkOpenMode,
    splitViewState: SplitViewState,
    sourceTerminalId: String? = null,
    scope: CoroutineScope,
    windowId: String? = null
) {
    // Find the source panel (where the terminal is) to correctly identify "the other" panel
    // This is important because cmd+click doesn't change focus, so activePanelId may not be the terminal panel
    val sourcePanelId = sourceTerminalId?.let { terminalId ->
        splitViewState.findPanelWithTab(terminalId)?.id
    } ?: splitViewState.activePanelId

    // Defensive check: verify source panel still exists (could be closed between link click and handling)
    // Fall back to active panel if source panel no longer exists
    val validSourcePanelId = if (splitViewState.findPanel(sourcePanelId) != null) {
        sourcePanelId
    } else {
        splitViewState.activePanelId
    }

    // Determine if this is a file URL - file links open in editor, HTTP links open in browser
    val isFile = isFileUrl(url)

    // For file URLs, perform defensive validation (primary validation happens in DesktopTerminalContent)
    // This protects against race conditions or direct calls to this function
    if (isFile) {
        // Parse file reference to extract line:column (e.g., file:/path/file.kt:123:45)
        val rawPath = stripFilePrefix(url)
        val parsed = parseFileReference(rawPath)

        when (val result = validateFilePath(parsed.path)) {
            is FileValidationResult.Invalid -> {
                return
            }
            is FileValidationResult.Valid -> {
                // Continue with validated path - use canonical path for consistency
                // TOCTOU note: There's a small window between validation and opening where
                // the file could be deleted. This is acceptable as the editor handles missing
                // files gracefully, and fully preventing this race is impractical.
                openTerminalLinkInternal(
                    url = "file:${result.canonicalPath}",
                    mode = mode,
                    splitViewState = splitViewState,
                    validSourcePanelId = validSourcePanelId,
                    isFile = true,
                    fileLine = parsed.line,
                    fileColumn = parsed.column,
                    scope = scope,
                    windowId = windowId
                )
            }
        }
    } else {
        // HTTP URLs don't need validation
        openTerminalLinkInternal(
            url = url,
            mode = mode,
            splitViewState = splitViewState,
            validSourcePanelId = validSourcePanelId,
            isFile = false,
            scope = scope,
            windowId = windowId
        )
    }
}

/**
 * Internal implementation of openTerminalLink after validation.
 * This is separated to avoid code duplication after the file validation branch.
 *
 * @param url The URL to open (HTTP or file: URL with canonical path)
 * @param mode How to open the link (split or new tab)
 * @param splitViewState The split view state for panel operations
 * @param validSourcePanelId The validated source panel ID
 * @param isFile Whether this is a file URL (vs HTTP)
 * @param fileLine 1-based line number for file navigation (0 = no navigation)
 * @param fileColumn 1-based column number for file navigation (0 = no navigation)
 * @param scope CoroutineScope for launching navigation events (structured concurrency)
 * @param windowId The window ID for multi-window filtering (Issue #506)
 */
private fun openTerminalLinkInternal(
    url: String,
    mode: TerminalLinkOpenMode,
    splitViewState: SplitViewState,
    validSourcePanelId: String,
    isFile: Boolean,
    fileLine: Int = 0,
    fileColumn: Int = 0,
    scope: CoroutineScope,
    windowId: String? = null
) {
    // Helper to create the appropriate tab type
    fun createTab() = if (isFile) createEditorTab(url) else createBrowserTab(url)

    // Helper to trigger navigation after opening a file with line:column
    // Uses structured concurrency - coroutine is cancelled if the composable is disposed
    // Issue #506: Pass windowId for multi-window filtering
    fun navigateToLineIfNeeded() {
        if (isFile && fileLine > 0 && windowId != null) {
            val cleanPath = stripFilePrefix(url)
            scope.launch(Dispatchers.Main) {
                NavigationTargetBus.navigateTo(cleanPath, fileLine, fileColumn, sourceWindowId = windowId)
            }
        }
    }

    when (mode) {
        TerminalLinkOpenMode.EXISTING_SPLIT -> {
            // Open in existing split panel (not the source panel where terminal is)
            // Use the target mode setting to determine which panel to use
            val targetMode = TerminalLinkSettingsManager.currentSettings.value.existingSplitTarget
            val targetPanel = when (targetMode) {
                ExistingSplitTargetMode.MOST_RECENT_ACTIVE ->
                    splitViewState.getOtherPanelExcluding(validSourcePanelId)
                ExistingSplitTargetMode.FIRST_AVAILABLE ->
                    splitViewState.getFirstOtherPanelExcluding(validSourcePanelId)
            }
            if (targetPanel != null) {
                val tab = createTab()
                val tabIndex = targetPanel.tabsComponent.addTab(tab)
                if (tabIndex >= 0) {
                    targetPanel.tabsComponent.selectTab(tabIndex)
                    splitViewState.setActivePanel(targetPanel.id)
                    navigateToLineIfNeeded()
                }
            } else {
                // IMPORTANT: Fallback when user saved EXISTING_SPLIT preference but later closed all splits.
                // Creates a new vertical split instead of failing silently.
                splitViewState.splitPanel(
                    panelId = validSourcePanelId,
                    orientation = SplitOrientation.VERTICAL,
                    tabToMove = createTab()
                )
                navigateToLineIfNeeded()
            }
        }
        TerminalLinkOpenMode.VERTICAL_SPLIT, TerminalLinkOpenMode.HORIZONTAL_SPLIT -> {
            val orientation = if (mode == TerminalLinkOpenMode.VERTICAL_SPLIT) {
                SplitOrientation.VERTICAL
            } else {
                SplitOrientation.HORIZONTAL
            }
            // Create split from the source panel (where terminal is), not from active panel
            splitViewState.splitPanel(
                panelId = validSourcePanelId,
                orientation = orientation,
                tabToMove = createTab()
            )
            navigateToLineIfNeeded()
        }
        TerminalLinkOpenMode.NEW_TAB, TerminalLinkOpenMode.ALWAYS_ASK -> {
            // NEW_TAB opens in current panel; ALWAYS_ASK shouldn't reach here but handle gracefully
            if (isFile) {
                // For file URLs, use openFileInActivePanel for consistent behavior
                val cleanPath = stripFilePrefix(url)
                val fileName = cleanPath.extractFileName().ifEmpty { "untitled" }
                splitViewState.openFileInActivePanel(cleanPath, fileName)
                navigateToLineIfNeeded()
            } else {
                splitViewState.openUrlInActivePanel(url, "Loading...")
            }
        }
        TerminalLinkOpenMode.SYSTEM_DEFAULT -> {
            // Open outside BOSS with the OS default handler (browser or file app).
            // Line:column navigation doesn't apply to external apps.
            if (isFile) {
                openFileWithSystemDefault(stripFilePrefix(url))
            } else {
                openUrlInBrowser(url)
            }
        }
    }
}
