package ai.rever.boss.window

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.Project
import ai.rever.boss.plugin.api.TabInfo
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Window type enum for determining appropriate size calculation
 */
enum class WindowType {
    MAIN,      // Main application window (70-75% of screen)
    AUTH,      // Authentication windows (40-45% of screen)
    SETTINGS   // Settings window (60-65% of screen)
}

/**
 * Central manager for all BOSS windows
 *
 * Manages the lifecycle of multiple application windows, including creation,
 * removal, and tab transfer between windows. Follows macOS-style lifecycle
 * where the app stays running even when all windows are closed.
 *
 * Window sizes are calculated adaptively based on screen dimensions using
 * DisplayUtils to provide optimal sizing across different display resolutions.
 */
object WindowManager {
    private val logger = BossLogger.forComponent("WindowManager")

    /**
     * List of all open windows
     * Using SnapshotStateList for reactive Compose updates
     */
    private val _windows = mutableStateListOf<BossWindowState>()

    /**
     * Map of pending initial tabs for new windows.
     * When a window is created with an initial tab, the tab is stored here
     * and consumed by BossApp when the window initializes.
     * Key: windowId, Value: TabInfo to open
     */
    private val pendingInitialTabs = ConcurrentHashMap<String, TabInfo>()

    /**
     * Map of pending initial projects for new windows.
     * When a window is created with an initial project, the project is stored here
     * and consumed by BossApp when the window initializes.
     * Key: windowId, Value: Project to open
     */
    private val pendingInitialProjects = ConcurrentHashMap<String, Project>()

    /**
     * Read-only access to the list of windows
     */
    val windows: List<BossWindowState>
        get() = _windows

    /**
     * Create a new window
     *
     * @param position Window position (null for default cascade)
     * @param windowType Type of window (determines adaptive sizing)
     * @return The newly created window state
     */
    fun createNewWindow(
        position: WindowPosition? = null,
        windowType: WindowType = WindowType.MAIN
    ): BossWindowState {
        val windowId = UUID.randomUUID().toString()

        // Calculate cascade position if not specified
        val windowPosition = position ?: calculateCascadePosition()

        val windowState = BossWindowState(
            id = windowId,
            title = "BOSS - Business Operating System + Simulation",
            position = windowPosition,
            windowType = windowType
        )

        _windows.add(windowState)
        logger.debug(LogCategory.UI, "Created new window", mapOf("windowId" to windowId, "type" to windowType.toString(), "totalWindows" to _windows.size))

        return windowState
    }

    /**
     * Create a new window with an initial tab
     *
     * @param initialTab The tab to open in the new window
     * @param position Window position (null for default cascade)
     * @param windowType Type of window (determines adaptive sizing)
     * @return The newly created window state
     */
    fun createNewWindowWithTab(
        initialTab: TabInfo,
        position: WindowPosition? = null,
        windowType: WindowType = WindowType.MAIN
    ): BossWindowState {
        val windowState = createNewWindow(position, windowType)
        // Store the pending tab for this window
        pendingInitialTabs[windowState.id] = initialTab
        logger.debug(LogCategory.UI, "Stored pending tab for new window", mapOf("tab" to initialTab.title, "windowId" to windowState.id))
        return windowState
    }

    /**
     * Get and consume the pending initial tab for a window
     *
     * @param windowId The window ID to get the pending tab for
     * @return The pending TabInfo, or null if none
     */
    fun consumePendingTab(windowId: String): TabInfo? {
        return pendingInitialTabs.remove(windowId)?.also {
            logger.debug(LogCategory.UI, "Consumed pending tab", mapOf("tab" to it.title, "windowId" to windowId))
        }
    }

    /**
     * Create a new window with an initial project
     *
     * @param project The project to open in the new window
     * @param position Window position (null for default cascade)
     * @param windowType Type of window (determines adaptive sizing)
     * @return The newly created window state
     */
    fun createNewWindowWithProject(
        project: Project,
        position: WindowPosition? = null,
        windowType: WindowType = WindowType.MAIN
    ): BossWindowState {
        val windowState = createNewWindow(position, windowType)
        // Store the pending project for this window
        pendingInitialProjects[windowState.id] = project
        logger.debug(LogCategory.UI, "Stored pending project for new window", mapOf("project" to project.name, "windowId" to windowState.id))
        return windowState
    }

    /**
     * Get and consume the pending initial project for a window
     *
     * @param windowId The window ID to get the pending project for
     * @return The pending Project, or null if none
     */
    fun consumePendingProject(windowId: String): Project? {
        return pendingInitialProjects.remove(windowId)?.also {
            logger.debug(LogCategory.UI, "Consumed pending project", mapOf("project" to it.name, "windowId" to windowId))
        }
    }

    /**
     * Close a window by ID
     *
     * @param windowId The ID of the window to close
     */
    fun closeWindow(windowId: String) {
        val window = _windows.find { it.id == windowId }
        if (window != null) {
            _windows.remove(window)
            logger.debug(LogCategory.UI, "Closed window", mapOf("windowId" to windowId, "remainingWindows" to _windows.size))
        }
    }

    /**
     * Close window if it has no tabs
     *
     * Note: Tabs are now managed by BossApp/SplitViewState, not WindowManager.
     * This method is kept for backward compatibility but no longer checks tabs.
     * Windows should be closed explicitly via closeWindow() or by user action.
     *
     * @param windowId The window ID to potentially close
     */
    @Deprecated("Tabs are managed by BossApp/SplitViewState, not WindowManager")
    fun closeWindowIfEmpty(windowId: String) {
        // No-op: tab management moved to BossApp/SplitViewState
        // Windows are closed explicitly or by user action
    }

    /**
     * Move a tab to a new window
     *
     * Note: Tab management moved to BossApp/SplitViewState.
     * This method now just creates an empty window.
     * The caller should handle moving the tab through BossApp/SplitViewState.
     *
     * @param sourceWindowId The window the tab is currently in (for logging)
     * @return The newly created window
     */
    @Deprecated("Tab management moved to BossApp/SplitViewState")
    fun moveTabToNewWindow(
        sourceWindowId: String
    ): BossWindowState {
        logger.debug(LogCategory.UI, "Creating new window (tab will be moved by caller)")

        // Create new window - tab will be moved by caller through BossApp
        val position = calculateCascadePosition()
        return createNewWindow(position = position)
    }

    /**
     * Check if the app should quit
     *
     * For macOS-style behavior, always return false to keep app running
     * even when all windows are closed.
     *
     * @return false to prevent app quit
     */
    fun shouldQuitApp(): Boolean {
        return false // Keep app running (macOS style)
    }

    /**
     * Get window by ID
     *
     * @param windowId The window ID to search for
     * @return The window state, or null if not found
     */
    fun getWindow(windowId: String): BossWindowState? {
        return _windows.find { it.id == windowId }
    }

    /**
     * Calculate cascade position for new windows
     *
     * Each new window is offset by 30dp from the previous window position.
     *
     * @return Window position for cascade effect
     */
    private fun calculateCascadePosition(): WindowPosition {
        val cascadeOffset = _windows.size * 30
        return WindowPosition(
            x = (100 + cascadeOffset).dp,
            y = (100 + cascadeOffset).dp
        )
    }

    /**
     * Get total number of open windows
     */
    val windowCount: Int
        get() = _windows.size

    /**
     * Grow/shrink a window by a **physical-pixel** delta so an embedded terminal
     * pane can be fit to a remote viewer's grid ("Fit host to my screen" in
     * BossTerm session sharing). BossTerm computes the delta as cellPx × grid-delta
     * in physical px; the BossWindow composable divides it by this window's display
     * scale to get the logical dp that Compose's `WindowState.size` expects (so the
     * fit is correct on HiDPI/scaled displays) and stashes the pre-fit size +
     * placement so [restoreWindowSize] can undo it when sharing stops or the host
     * user interacts with the pane.
     *
     * **Contract:** the delta is applied to the window's *then-current* size, so
     * each call is incremental — the caller must compute it against the host's
     * current size (BossTerm does: `(targetCols − currentCols) × cellPx`), not
     * re-derive it from a stale baseline, or repeated calls will overshoot/drift.
     *
     * Reached by the terminal-tab plugin via reflection (it has the window id);
     * a no-op if the window is unknown. Safe to call off the UI thread — it only
     * emits an event the composable applies on the composition thread.
     */
    fun fitWindowByDelta(windowId: String, deltaWidthPx: Float, deltaHeightPx: Float) {
        val window = _windows.toList().find { it.id == windowId } ?: return
        window.sizeRequests.tryEmit(WindowSizeRequest.FitByDelta(deltaWidthPx, deltaHeightPx))
        logger.debug(LogCategory.UI, "Fit window by delta", mapOf(
            "windowId" to windowId, "dWpx" to deltaWidthPx, "dHpx" to deltaHeightPx
        ))
    }

    /**
     * Restore a window to the size and placement captured before the first
     * [fitWindowByDelta], if any. No-op when nothing was fit. Safe to call off the
     * UI thread (emits an event applied on the composition thread).
     */
    fun restoreWindowSize(windowId: String) {
        val window = _windows.toList().find { it.id == windowId } ?: return
        window.sizeRequests.tryEmit(WindowSizeRequest.Restore)
        logger.debug(LogCategory.UI, "Restore window size", mapOf("windowId" to windowId))
    }
}

/**
 * State for a single BOSS window
 *
 * Note: Tabs are managed by each window's BossApp/SplitViewState,
 * not by WindowManager. This class only tracks window-level properties.
 *
 * Window size is calculated adaptively in BossWindow.kt based on windowType
 * and screen dimensions using DisplayUtils.
 *
 * @property id Unique identifier for this window
 * @property title Window title
 * @property position Window position on screen
 * @property windowType Type of window (determines adaptive size calculation)
 */
data class BossWindowState(
    val id: String,
    var title: String,
    val position: WindowPosition?,
    val windowType: WindowType = WindowType.MAIN
) {
    /**
     * Stream of programmatic resize requests (BossTerm "Fit host to my screen").
     * An event stream — not a value — so a fit and a restore (or two fits) can't
     * conflate or clobber each other the way a `MutableStateFlow` would; buffered
     * with [BufferOverflow.DROP_OLDEST] so emitting from a background thread never
     * suspends. Consumed by the BossWindow composable, which owns the live window
     * size, placement, and display scale and applies each request on the
     * composition thread.
     *
     * `replay = 1`: a window is in [_windows] (so addressable by id) slightly
     * before its composable starts collecting, so an early fit would otherwise be
     * dropped. Replaying the last request closes that startup race. The collector
     * is keyed on a stable window state and never restarts, so there is no stale
     * re-delivery on recomposition.
     */
    val sizeRequests: MutableSharedFlow<WindowSizeRequest> = MutableSharedFlow(
        replay = 1,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
}

/**
 * A programmatic window-resize request emitted by [WindowManager] and applied by
 * the BossWindow composable on the composition thread.
 */
sealed interface WindowSizeRequest {
    /**
     * Grow/shrink the window by a **physical-pixel** delta. The composable divides
     * by the window's display scale to get the logical dp Compose expects, so the
     * fit stays correct on HiDPI / scaled displays.
     */
    data class FitByDelta(val deltaWidthPx: Float, val deltaHeightPx: Float) : WindowSizeRequest

    /** Undo the most recent fit, restoring the pre-fit size and placement. */
    data object Restore : WindowSizeRequest
}
