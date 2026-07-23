package ai.rever.boss.window

import BossDarkSurface
import ai.rever.boss.BossAppWithAuth
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.components.dialogs.CLIInstallationDialog
import ai.rever.boss.components.window_panel.components.main_window_panels.createBossAppContext
import ai.rever.boss.utils.CLIInstaller
import ai.rever.boss.utils.DisplayUtils
import ai.rever.boss.utils.WindowFocusManager
import ai.rever.boss.keymap.KeymapSettingsManager
import ai.rever.boss.keymap.menu.MenuShortcutBridge
import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.focusmode.FocusModeSettingsManager
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.window.WindowType
import ai.rever.boss.updater.UpdateManager
import ai.rever.boss.plugin.browser.FluckEngine
import ai.rever.boss.plugin.browser.LocalAwtWindow
import ai.rever.boss.plugin.browser.ScreenCaptureNotifier
import ai.rever.boss.plugin.browser.ScreenCapturePickerDialog
import ai.rever.boss.services.terminal.TerminalAPIAccess
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import org.jetbrains.compose.resources.painterResource
import boss_kotlin.composeapp.generated.resources.Res
import boss_kotlin.composeapp.generated.resources.boss_icon
import androidx.compose.ui.window.*
import java.awt.Color
import java.awt.Frame

/** Floor for a programmatic window fit ("Fit host to my screen"), in dp. Keeps a
 *  fit from collapsing the window below a usable size if a remote viewer reports a
 *  tiny grid; the host can still be resized smaller by hand afterwards. */
internal const val MIN_FIT_WIDTH_DP = 480f
internal const val MIN_FIT_HEIGHT_DP = 320f

/**
 * Pure math for a programmatic window fit ("Fit host to my screen"): grow/shrink
 * [current] (dp) by a **physical-pixel** delta, converting to dp via the window's
 * display scale ([scaleX]/[scaleY], non-positive or unknown → treated as 1×), then
 * clamping into the usable screen area ([maxWidthDp] × [maxHeightDp], dp). The lower
 * bound is `min(floor, screen)` so a usable area below the min-fit floor yields the
 * screen size instead of inverting the range (which would make `coerceIn` throw).
 *
 * Extracted from the BossWindow collector so the scale / clamp / floor branches are
 * unit-testable without a live window — see WindowFitMathTest.
 */
internal fun computeFitSize(
    current: DpSize,
    deltaWidthPx: Float,
    deltaHeightPx: Float,
    scaleX: Float,
    scaleY: Float,
    maxWidthDp: Float,
    maxHeightDp: Float,
    minWidthDp: Float = MIN_FIT_WIDTH_DP,
    minHeightDp: Float = MIN_FIT_HEIGHT_DP,
): DpSize {
    val sx = scaleX.takeIf { it > 0f } ?: 1f
    val sy = scaleY.takeIf { it > 0f } ?: 1f
    val newW = (current.width.value + deltaWidthPx / sx)
        .coerceAtMost(maxWidthDp)
        .coerceAtLeast(minOf(minWidthDp, maxWidthDp))
    val newH = (current.height.value + deltaHeightPx / sy)
        .coerceAtMost(maxHeightDp)
        .coerceAtLeast(minOf(minHeightDp, maxHeightDp))
    return DpSize(newW.dp, newH.dp)
}

/**
 * Individual BOSS window composable
 *
 * Creates a single window instance with its own independent state, tabs, and context.
 * Each window has its own BossAppWithAuth instance, allowing multiple independent
 * workspaces to coexist.
 *
 * Window size is calculated adaptively based on windowType and screen dimensions
 * using DisplayUtils to provide optimal sizing across different display resolutions.
 *
 * @param windowState The state for this window (position, windowType, etc.)
 * @param onCloseRequest Callback when the window should be closed
 */
@Composable
fun ApplicationScope.BossWindow(
    windowState: BossWindowState,
    onCloseRequest: () -> Unit
) {
    // Calculate adaptive window size based on window type and screen dimensions
    val windowSize = when (windowState.windowType) {
        WindowType.MAIN -> DisplayUtils.calculateMainWindowSize()
        WindowType.AUTH -> DisplayUtils.calculateAuthWindowSize()
        WindowType.SETTINGS -> DisplayUtils.calculateSettingsWindowSize()
    }

    // Remember window state for Compose Window
    // Main window starts maximized, other windows use calculated size
    val composeWindowState = rememberWindowState(
        position = windowState.position ?: WindowPosition.Aligned(Alignment.Center),
        size = windowSize,
        placement = if (windowState.windowType == WindowType.MAIN) WindowPlacement.Maximized else WindowPlacement.Floating
    )

    // Track full screen state for reactive menu text
    var isMaximized by remember { mutableStateOf(false) }
    val isFullScreen = composeWindowState.placement == WindowPlacement.Fullscreen

    // Test crash state - when true, simulates a main window crash (Issue #543)
    var shouldTriggerTestCrash by remember { mutableStateOf(false) }

    Window(
        onCloseRequest = onCloseRequest,
        title = windowState.title,
        state = composeWindowState,
        icon = painterResource(Res.drawable.boss_icon)
    ) {
        // Apply programmatic resize requests (BossTerm "Fit host to my screen").
        // Lives inside Window {} so it can read this window's `window` (AWT
        // ComposeWindow) for the display scale + per-monitor bounds, and runs on
        // the composition thread, so it owns the live window size + placement —
        // no cross-thread window-state access, and successive fits stay cumulative.
        // Keyed on the stable window id (not the mutable BossWindowState data-class
        // instance) so the collector never restarts on recomposition — a restart
        // would, via sizeRequests' replay = 1, re-apply the last fit and re-capture
        // preFit from the already-fitted size.
        LaunchedEffect(windowState.id) {
            // Size + placement captured on the first fit, so Restore returns a
            // Maximized MAIN window to Maximized (not leaving it Floating).
            var preFit: Pair<DpSize, WindowPlacement>? = null
            windowState.sizeRequests.collect { req ->
                when (req) {
                    is WindowSizeRequest.FitByDelta -> {
                        if (preFit == null) {
                            preFit = composeWindowState.size to composeWindowState.placement
                        }
                        // BossTerm sends a physical-px delta; convert to dp via this
                        // window's display scale and clamp to its screen. defaultTransform
                        // is per-GraphicsConfiguration, so this is correct on multi-
                        // monitor / mixed-DPI setups. Math lives in computeFitSize so it
                        // is unit-tested.
                        val gc = window.graphicsConfiguration
                        val tf = gc?.defaultTransform
                        // Usable area of THIS window's screen, in logical points
                        // (same units as WindowState.size dp).
                        val usable = runCatching {
                            val b = gc!!.bounds
                            val insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc)
                            (b.width - insets.left - insets.right).toFloat() to
                                (b.height - insets.top - insets.bottom).toFloat()
                        }.getOrNull()
                        val target = computeFitSize(
                            current = composeWindowState.size,
                            deltaWidthPx = req.deltaWidthPx,
                            deltaHeightPx = req.deltaHeightPx,
                            scaleX = tf?.scaleX?.toFloat() ?: 1f,
                            scaleY = tf?.scaleY?.toFloat() ?: 1f,
                            maxWidthDp = usable?.first ?: 100_000f,
                            maxHeightDp = usable?.second ?: 100_000f,
                        )
                        // Maximized ignores an explicit size — drop to Floating first.
                        if (composeWindowState.placement != WindowPlacement.Floating) {
                            composeWindowState.placement = WindowPlacement.Floating
                        }
                        composeWindowState.size = target
                    }
                    WindowSizeRequest.Restore -> {
                        preFit?.let { (size, placement) ->
                            // Restore size first (so a re-maximized window also gets
                            // the right un-maximize size back), then placement.
                            composeWindowState.size = size
                            composeWindowState.placement = placement
                            preFit = null
                        }
                    }
                }
            }
        }

        // Test crash handler (Issue #543) - must be inside Window block to access `window`
        if (shouldTriggerTestCrash) {
            shouldTriggerTestCrash = false
            val testException = RuntimeException("Test crash - main window crash simulation (Issue #543)")
            val currentWindow = window
            javax.swing.SwingUtilities.invokeLater {
                // First trigger crash handler - this shows dialog in separate window
                // The dialog must be shown BEFORE we dispose the main window,
                // otherwise the app may exit before the dialog appears
                Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(
                    Thread.currentThread(),
                    testException
                )
                // Main window will be closed by terminateAfterCrash() after user dismisses dialog
            }
        }

        // Set window appearance - using native OS title bar
        window.background = Color(BossDarkSurface.value.toInt())

        // Enable native macOS fullscreen support and extend content into title bar
        // This allows the green traffic light button to enter proper native fullscreen,
        // extends app content into the title bar area, and makes the title bar transparent
        // to ensure clicks reach the Compose UI layer instead of being intercepted by macOS
        val isMacOS = System.getProperty("os.name").lowercase().contains("mac")
        if (isMacOS) {
            window.rootPane.putClientProperty("apple.awt.fullscreenable", true)
            window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
            window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
            window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
        }

        // Register window for focus management (deep links, etc.) and keyboard interception
        DisposableEffect(windowState.id, window) {
            WindowFocusManager.registerWindow(windowState.id, window)
            AWTKeyboardInterceptor.registerWindow(window, windowState.id)
            onDispose {
                WindowFocusManager.unregisterWindow(windowState.id)
                AWTKeyboardInterceptor.unregisterWindow(window)
                MenuActionsHandler.cleanupWindow(windowState.id)
            }
        }

        val keymapSettings by KeymapSettingsManager.currentSettings.collectAsState()

        // Create shortcut bridge for menu items
        val shortcutBridge = remember(keymapSettings) {
            MenuShortcutBridge.from(keymapSettings)
        }

        // State for CLI installation dialog
        var showCLIInstallDialog by remember { mutableStateOf(false) }
        var isCliInstalled by remember { mutableStateOf<Boolean>(CLIInstaller.isInstalled()) }

        // State for Reset Browser dialog
        var showResetBrowserDialog by remember { mutableStateOf(false) }
        var resetBrowserResult by remember { mutableStateOf<Boolean?>(null) }

        // State for Reset Terminal dialog
        var showResetTerminalDialog by remember { mutableStateOf(false) }
        var resetTerminalResult by remember { mutableStateOf<Boolean?>(null) }

        // State for Welcome Wizard dialog
        var showWelcomeWizard by remember { mutableStateOf(false) }

        // Sync isMaximized state with actual window state (handles OS maximize controls)
        DisposableEffect(window) {
            // Initialize state from current window state
            isMaximized = window.extendedState == Frame.MAXIMIZED_BOTH

            val listener = object : java.awt.event.WindowAdapter() {
                override fun windowStateChanged(e: java.awt.event.WindowEvent) {
                    isMaximized = window.extendedState == Frame.MAXIMIZED_BOTH
                }
            }
            window.addWindowStateListener(listener)
            onDispose {
                window.removeWindowStateListener(listener)
            }
        }

        // Get focus mode state for menu item text
        val focusModeSettings by FocusModeSettingsManager.currentSettings.collectAsState()
        val isFocusModeEnabled = focusModeSettings.enabled

        // Get workspace list for workspace submenu
        val workspaceManager = remember { ai.rever.boss.components.workspaces.WorkspaceManager() }
        val workspaces by workspaceManager.workspaces.collectAsState()
        val currentWorkspace by workspaceManager.currentWorkspace.collectAsState()

        // Get split enabled state (whether there are tabs to split)
        val splitEnabledMap by MenuActionsHandler.splitEnabledState.collectAsState()
        val isSplitEnabled = splitEnabledMap[windowState.id] ?: false

        // Get panel count state (for enabling/disabling panel navigation)
        val panelCountMap by MenuActionsHandler.panelCountState.collectAsState()
        val panelCount = panelCountMap[windowState.id] ?: 1
        val isPanelNavigationEnabled = panelCount > 1

        // Get registered plugins for Plugin menu
        val panelRegistry = remember { PanelRegistry() }
        var registeredPluginsVersion by remember { mutableStateOf(0) }
        val registeredPlugins = remember(registeredPluginsVersion) {
            panelRegistry.getAllPanels()
        }

        // Coroutine scope for menu actions (like checking for updates)
        val menuScope = rememberCoroutineScope()

        // Listen for panel registry changes to update the menu
        DisposableEffect(panelRegistry) {
            val listener = {
                registeredPluginsVersion++
                Unit
            }
            panelRegistry.addChangeListener(listener)
            onDispose {
                panelRegistry.removeChangeListener(listener)
            }
        }

        // macOS MenuBar - provides native menu integration
        // Keyboard shortcuts are handled at OS level via native menu accelerators
        // This is the single source of truth for all keyboard shortcuts (BossTerm pattern)
        MenuBar {
            // File Menu
            Menu("File") {
                Item(
                    "New Tab",
                    shortcut = shortcutBridge.getKeyShortcut(KeymapActions.TAB_NEW),
                    onClick = {
                        MenuActionsHandler.triggerNewTab(windowState.id)
                    }
                )
                Item(
                    "New Window",
                    shortcut = shortcutBridge.getKeyShortcut(KeymapActions.WINDOW_NEW),
                    onClick = { WindowOperations.createNewWindow() }
                )
                Item(
                    "Open Project...",
                    onClick = {
                        MenuActionsHandler.triggerOpenProject(windowState.id)
                    }
                )
                Item(
                    "Open File...",
                    onClick = {
                        MenuActionsHandler.triggerOpenFile(windowState.id)
                    }
                )
                Item(
                    "New Terminal Tab",
                    onClick = {
                        MenuActionsHandler.triggerNewTerminal(windowState.id)
                    }
                )
                Item(
                    "Close Tab",
                    shortcut = shortcutBridge.getKeyShortcut(KeymapActions.TAB_CLOSE),
                    onClick = {
                        MenuActionsHandler.triggerCloseTab(windowState.id)
                    }
                )

                Separator()

                // Workspace submenu
                Menu("Select Workspace") {
                    workspaces.forEach { workspace ->
                        Item(
                            text = workspace.name,
                            onClick = {
                                MenuActionsHandler.triggerApplyWorkspace(windowState.id, workspace)
                            },
                            enabled = currentWorkspace?.id != workspace.id  // Disable current workspace
                        )
                    }

                    if (workspaces.isEmpty()) {
                        Item(
                            text = "(No workspaces available)",
                            onClick = { },
                            enabled = false
                        )
                    }

                    Separator()

                    // Access TopOfMindDialog for workspace switching and quick navigation
                    Item(
                        "Top of the Mind",
                        shortcut = shortcutBridge.getKeyShortcut(KeymapActions.QUICK_SWITCHER_OPEN),
                        onClick = {
                            MenuActionsHandler.triggerSelectWorkspace(windowState.id)
                        }
                    )
                }

                Separator()

                Item(
                    "Save Workspace",
                    shortcut = shortcutBridge.getKeyShortcut(KeymapActions.WORKSPACE_SAVE),
                    onClick = {
                        MenuActionsHandler.triggerSaveWorkspace(windowState.id)
                    }
                )

                Item(
                    "Open Codebase",
                    shortcut = shortcutBridge.getKeyShortcut(KeymapActions.CODEBASE_OPEN),
                    onClick = {
                        MenuActionsHandler.triggerOpenCodebase(windowState.id)
                    }
                )

                Separator()

                // Process Mode toggle
                val isKernelMode = remember {
                    val mode = System.getenv("BOSS_MODE")
                        ?: ai.rever.boss.config.ConfigLoader.getConfig("BOSS_MODE")
                    mode == "KERNEL"
                }
                CheckboxItem(
                    "Microkernel Mode",
                    checked = isKernelMode,
                    onCheckedChange = {
                        // Toggle in env_vars file; requires restart
                        menuScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val envFile = ai.rever.boss.plugin.pathutils.BossDirectories.resolve("env_vars")
                            envFile.parentFile?.mkdirs()
                            if (!envFile.exists()) {
                                envFile.writeText(if (it) "BOSS_MODE=KERNEL\n" else "# BOSS_MODE=KERNEL\n", Charsets.UTF_8)
                            } else {
                                val lines = envFile.readLines(Charsets.UTF_8).toMutableList()
                                val idx = lines.indexOfFirst { l -> l.trimStart('#', ' ').startsWith("BOSS_MODE") }
                                val newLine = if (it) "BOSS_MODE=KERNEL" else "# BOSS_MODE=KERNEL"
                                if (idx >= 0) lines[idx] = newLine
                                else { lines.add(""); lines.add(newLine) }
                                envFile.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
                            }
                        }
                    }
                )

                Separator()

                Item(
                    "Settings",
                    shortcut = shortcutBridge.getKeyShortcut(KeymapActions.SETTINGS_OPEN),
                    onClick = {
                        MenuActionsHandler.triggerOpenSettings(windowState.id)
                    }
                )
                Item(
                    "Quit BOSS",
                    onClick = { exitApplication() }
                )
            }

            // Edit Menu
            Menu("Edit") {
                Item(
                    "Cut",
                    onClick = {
                        ClipboardHelper.cut()
                    }
                )
                Item(
                    "Copy",
                    onClick = {
                        ClipboardHelper.copy()
                    }
                )
                Item(
                    "Paste",
                    onClick = {
                        ClipboardHelper.paste()
                    }
                )

                Separator()

                Item(
                    "Select All",
                    onClick = {
                        ClipboardHelper.selectAll()
                    }
                )
            }

            // Refactor Menu
            Menu("Refactor") {
                Item(
                    "Rename Symbol",
                    onClick = {
                        MenuActionsHandler.triggerRefactorRename(windowState.id)
                    }
                )
                
                Separator()
                
                Item(
                    "Extract Variable",
                    onClick = {
                        MenuActionsHandler.triggerRefactorExtractVariable(windowState.id)
                    }
                )
                Item(
                    "Extract Method",
                    onClick = {
                        MenuActionsHandler.triggerRefactorExtractMethod(windowState.id)
                    }
                )
                Item(
                    "Extract Constant",
                    onClick = {
                        MenuActionsHandler.triggerRefactorExtractConstant(windowState.id)
                    }
                )
                
                Separator()
                
                Item(
                    "Inline",
                    onClick = {
                        MenuActionsHandler.triggerRefactorInline(windowState.id)
                    }
                )
                
                Separator()
                
                Item(
                    "Change Signature",
                    onClick = {
                        MenuActionsHandler.triggerRefactorChangeSignature(windowState.id)
                    }
                )
                Item(
                    "Safe Delete",
                    onClick = {
                        MenuActionsHandler.triggerRefactorSafeDelete(windowState.id)
                    }
                )
            }

            // View Menu
            Menu("View") {
                Item(
                    text = if (isFocusModeEnabled) "Focus Mode (On)" else "Focus Mode (Off)",
                    shortcut = shortcutBridge.getKeyShortcut(KeymapActions.FOCUS_MODE_TOGGLE),
                    onClick = {
                        MenuActionsHandler.triggerToggleFocusMode(windowState.id)
                    }
                )

                Separator()

                Item(
                    "Split Vertically",
                    shortcut = shortcutBridge.getKeyShortcut(KeymapActions.PANEL_SPLIT_VERTICAL),
                    onClick = {
                        MenuActionsHandler.triggerSplitVertically(windowState.id)
                    },
                    enabled = isSplitEnabled
                )
                Item(
                    "Split Horizontally",
                    shortcut = shortcutBridge.getKeyShortcut(KeymapActions.PANEL_SPLIT_HORIZONTAL),
                    onClick = {
                        MenuActionsHandler.triggerSplitHorizontally(windowState.id)
                    },
                    enabled = isSplitEnabled
                )

                Separator()

                Item(
                    "Actual Size",
                    shortcut = shortcutBridge.getKeyShortcut(KeymapActions.BROWSER_ZOOM_RESET),
                    onClick = {
                        MenuActionsHandler.triggerActualSize(windowState.id)
                    }
                )
                Item(
                    "Zoom In",
                    shortcut = shortcutBridge.getKeyShortcut(KeymapActions.BROWSER_ZOOM_IN),
                    onClick = {
                        MenuActionsHandler.triggerZoomIn(windowState.id)
                    }
                )
                Item(
                    "Zoom Out",
                    shortcut = shortcutBridge.getKeyShortcut(KeymapActions.BROWSER_ZOOM_OUT),
                    onClick = {
                        MenuActionsHandler.triggerZoomOut(windowState.id)
                    }
                )
                Item(
                    "Reload",
                    shortcut = shortcutBridge.getKeyShortcut(KeymapActions.BROWSER_RELOAD),
                    onClick = {
                        MenuActionsHandler.triggerReloadBrowser(windowState.id)
                    }
                )

                Separator()

                // Panel Navigation
                Item(
                    "Navigate Left",
                    shortcut = shortcutBridge.getKeyShortcut(KeymapActions.PANEL_NAVIGATE_LEFT),
                    onClick = {
                        MenuActionsHandler.triggerNavigatePanelLeft(windowState.id)
                    },
                    enabled = isPanelNavigationEnabled
                )
                Item(
                    "Navigate Right",
                    shortcut = shortcutBridge.getKeyShortcut(KeymapActions.PANEL_NAVIGATE_RIGHT),
                    onClick = {
                        MenuActionsHandler.triggerNavigatePanelRight(windowState.id)
                    },
                    enabled = isPanelNavigationEnabled
                )
                Item(
                    "Navigate Up",
                    shortcut = shortcutBridge.getKeyShortcut(KeymapActions.PANEL_NAVIGATE_UP),
                    onClick = {
                        MenuActionsHandler.triggerNavigatePanelUp(windowState.id)
                    },
                    enabled = isPanelNavigationEnabled
                )
                Item(
                    "Navigate Down",
                    shortcut = shortcutBridge.getKeyShortcut(KeymapActions.PANEL_NAVIGATE_DOWN),
                    onClick = {
                        MenuActionsHandler.triggerNavigatePanelDown(windowState.id)
                    },
                    enabled = isPanelNavigationEnabled
                )

                Separator()

                Item(
                    "Customize Sidebar…",
                    onClick = {
                        MenuActionsHandler.triggerCustomizeSidebar(windowState.id)
                    }
                )

                Separator()

                Item(
                    if (isFullScreen) "Exit Full Screen" else "Enter Full Screen",
                    onClick = {
                        // Toggle between Fullscreen and Floating window placements
                        composeWindowState.placement = if (composeWindowState.placement == WindowPlacement.Fullscreen) {
                            WindowPlacement.Floating
                        } else {
                            WindowPlacement.Fullscreen
                        }
                    }
                )
            }

            // Plugin Menu - Dynamically populated from PanelRegistry
            Menu("Toolbox") {
                registeredPlugins.forEachIndexed { index, panelInfo ->
                    Item(
                        text = panelInfo.displayName,
                        onClick = {
                            MenuActionsHandler.triggerRevealPlugin(
                                windowId = windowState.id,
                                pluginId = panelInfo.id.panelId
                            )
                        }
                    )

                    // Add separator after every 3rd item for visual grouping
                    if ((index + 1) % 3 == 0 && index < registeredPlugins.size - 1) {
                        Separator()
                    }
                }

                // Fallback if no plugins registered
                if (registeredPlugins.isEmpty()) {
                    Item(
                        text = "(No tools available)",
                        onClick = { },
                        enabled = false
                    )
                }
            }

            // Tools Menu
            Menu("Tools") {
                Item(
                    text = if (isCliInstalled) "Reinstall BOSS CLI" else "Install BOSS CLI",
                    onClick = {
                        showCLIInstallDialog = true
                    }
                )
            }

            // Window Menu
            Menu("Window") {
                Item(
                    "Close Window",
                    shortcut = shortcutBridge.getKeyShortcut(KeymapActions.WINDOW_CLOSE),
                    onClick = { WindowOperations.closeWindow(windowState.id) }
                )

                Separator()

                Item(
                    "Minimize",
                    onClick = {
                        window.extendedState = Frame.ICONIFIED
                    }
                )
                Item(
                    "Zoom",
                    onClick = {
                        window.extendedState = if (window.extendedState == Frame.MAXIMIZED_BOTH) {
                            Frame.NORMAL
                        } else {
                            Frame.MAXIMIZED_BOTH
                        }
                    }
                )

                Separator()

                Item(
                    "Bring All to Front",
                    onClick = {
                        // Get all AWT windows and bring BOSS windows to front
                        val allWindows = java.awt.Window.getWindows()
                        allWindows.forEach { awtWindow ->
                            if (awtWindow.isShowing) {
                                awtWindow.toFront()
                            }
                        }
                    }
                )

                // Dynamic window list
                if (WindowManager.windows.size > 1) {
                    Separator()
                    WindowManager.windows.forEachIndexed { index, win ->
                        Item(
                            text = "Window ${index + 1}",
                            onClick = {
                                // Focus would be handled here if we had window focus API
                                // For now, this shows the window in the list
                            },
                            enabled = win.id != windowState.id  // Disable current window
                        )
                    }
                }
            }

            // Help Menu
            Menu("Help") {
                Item(
                    "Keyboard Shortcuts",
                    shortcut = shortcutBridge.getKeyShortcut(KeymapActions.HELP_SHORTCUTS),
                    onClick = {
                        MenuActionsHandler.triggerShowShortcutHelp(windowState.id)
                    }
                )

                Separator()

                Item(
                    "Welcome Wizard...",
                    onClick = {
                        showWelcomeWizard = true
                    }
                )

                Item(
                    "Toolbox Setup Wizard...",
                    onClick = {
                        MenuActionsHandler.triggerShowPluginWizard(windowState.id)
                    }
                )

                Separator()

                Item(
                    "Check for Updates...",
                    onClick = {
                        menuScope.launch {
                            // Manual check: bypass per-version dismissal
                            UpdateManager.instance.checkForUpdates(force = true)
                        }
                    }
                )

                Separator()

                Item(
                    "Reset Browser...",
                    onClick = {
                        showResetBrowserDialog = true
                    }
                )

                Item(
                    "Reset Terminal...",
                    onClick = {
                        showResetTerminalDialog = true
                    }
                )

                Separator()

                Item(
                    "Reload All Tools",
                    onClick = {
                        menuScope.launch {
                            MenuActionsHandler.triggerReloadAllPlugins(windowState.id)
                        }
                    }
                )

                // Debug: Test crash reporter (Issue #543)
                // This crashes during Compose composition to properly test the separate window crash dialog
                Separator()
                Item(
                    "Trigger Test Crash...",
                    onClick = {
                        shouldTriggerTestCrash = true
                    }
                )
            }
        }

        // Provide the AWT window via LocalAwtWindow for multi-window support
        // This ensures JxBrowser instances get the correct window handle for their containing window
        CompositionLocalProvider(LocalAwtWindow provides window) {
            // Create independent component context for this window
            // Each window gets its own Decompose context tree
            with(createBossAppContext) {
                // Only the first window should load "Last Session" workspace (Issue #129)
                val isFirstWindow = WindowManager.windowCount == 1
                BossAppWithAuth(
                    windowId = windowState.id,
                    isFirstWindow = isFirstWindow,
                    panelRegistry = panelRegistry,
                    onToggleMaximize = {
                        // Capture state before EDT dispatch to avoid race condition with rapid double-clicks
                        val shouldMaximize = window.extendedState != Frame.MAXIMIZED_BOTH
                        java.awt.EventQueue.invokeLater {
                            window.extendedState = if (shouldMaximize) Frame.MAXIMIZED_BOTH else Frame.NORMAL
                            // isMaximized will be updated by WindowStateListener
                        }
                    }
                )
            }
        }

        // CLI Installation Dialog
        if (showCLIInstallDialog) {
            CLIInstallationDialog(
                onDismiss = {
                    showCLIInstallDialog = false
                    // Refresh CLI installation status after dialog closes
                    isCliInstalled = CLIInstaller.isInstalled()
                }
            )
        }

        // Reset Browser Confirmation Dialog
        if (showResetBrowserDialog) {
            var isResetting by remember { mutableStateOf(false) }

            AlertDialog(
                onDismissRequest = {
                    if (!isResetting) {
                        showResetBrowserDialog = false
                        resetBrowserResult = null
                    }
                },
                title = {
                    Text("Reset Browser")
                },
                text = {
                    Column {
                        when {
                            isResetting -> {
                                Text("Resetting browser profile...")
                                Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
                                Text("Please wait, this may take a moment.")
                            }
                            resetBrowserResult == null -> {
                                Text("This will reset the browser to fix persistent issues.")
                                Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
                                Text("The following will be cleared:")
                                Text("• Browser cache and cookies")
                                Text("• Saved login sessions")
                                Text("• Browser history")
                                Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
                                Text("All browser tabs will be closed. Continue?")
                            }
                            resetBrowserResult == true -> {
                                Text("✅ Browser reset successful!")
                                Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
                                Text("Please close any browser tabs and reopen them.")
                            }
                            else -> {
                                Text("❌ Browser reset failed.")
                                Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
                                Text("Please try restarting BOSS manually.")
                            }
                        }
                    }
                },
                confirmButton = {
                    when {
                        isResetting -> {
                            // No button while resetting
                        }
                        resetBrowserResult == null -> {
                            Button(
                                onClick = {
                                    isResetting = true
                                    menuScope.launch {
                                        val result = FluckEngine.resetBrowserProfile()
                                        resetBrowserResult = result.success
                                        isResetting = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = BossTheme.colors.alert
                                )
                            ) {
                                Text("Reset Browser", color = BossTheme.colors.onSignal)
                            }
                        }
                        else -> {
                            Button(
                                onClick = {
                                    showResetBrowserDialog = false
                                    resetBrowserResult = null
                                }
                            ) {
                                Text("Close")
                            }
                        }
                    }
                },
                dismissButton = {
                    if (resetBrowserResult == null && !isResetting) {
                        TextButton(
                            onClick = {
                                showResetBrowserDialog = false
                            }
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            )
        }

        // Reset Terminal Confirmation Dialog
        if (showResetTerminalDialog) {
            var isResetting by remember { mutableStateOf(false) }

            AlertDialog(
                onDismissRequest = {
                    if (!isResetting) {
                        showResetTerminalDialog = false
                        resetTerminalResult = null
                    }
                },
                title = {
                    Text("Reset Terminal")
                },
                text = {
                    Column {
                        when {
                            isResetting -> {
                                Text("Resetting terminal sessions...")
                                Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
                                Text("Please wait, this may take a moment.")
                            }
                            resetTerminalResult == null -> {
                                Text("This will reset all terminals to fix persistent issues.")
                                Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
                                Text("The following will be cleared:")
                                Text("• All terminal sessions")
                                Text("• Terminal history in current session")
                                Text("• Running processes")
                                Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
                                Text("All terminals will be reset with fresh sessions. Continue?")
                            }
                            resetTerminalResult == true -> {
                                Text("✅ Terminal reset successful!")
                                Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
                                Text("Terminal tabs will refresh with new sessions automatically.")
                            }
                            else -> {
                                Text("❌ Terminal reset failed.")
                                Spacer(modifier = androidx.compose.ui.Modifier.height(8.dp))
                                Text("Please try restarting BOSS manually.")
                            }
                        }
                    }
                },
                confirmButton = {
                    when {
                        isResetting -> {
                            // No button while resetting
                        }
                        resetTerminalResult == null -> {
                            Button(
                                onClick = {
                                    isResetting = true
                                    menuScope.launch {
                                        try {
                                            // Use IO dispatcher for resource disposal per CLAUDE.md threading guidelines
                                            withContext(Dispatchers.IO) {
                                                TerminalAPIAccess.resetAllTerminals()
                                            }
                                            resetTerminalResult = true
                                        } catch (e: Exception) {
                                            resetTerminalResult = false
                                        }
                                        isResetting = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = BossTheme.colors.alert
                                )
                            ) {
                                Text("Reset Terminal", color = BossTheme.colors.onSignal)
                            }
                        }
                        else -> {
                            Button(
                                onClick = {
                                    showResetTerminalDialog = false
                                    resetTerminalResult = null
                                }
                            ) {
                                Text("Close")
                            }
                        }
                    }
                },
                dismissButton = {
                    if (resetTerminalResult == null && !isResetting) {
                        TextButton(
                            onClick = {
                                showResetTerminalDialog = false
                            }
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            )
        }

        // Welcome Wizard Dialog
        if (showWelcomeWizard) {
            TerminalAPIAccess.TerminalOnboardingWizard(
                onDismiss = { showWelcomeWizard = false },
                onComplete = { showWelcomeWizard = false }
            )
        }

        // Screen Capture Picker Dialog
        val captureRequest by ScreenCaptureNotifier.captureRequest.collectAsState()
        captureRequest?.let { request ->
            ScreenCapturePickerDialog(
                screens = request.screens,
                windows = request.windows,
                browsers = request.browsers,
                onDismiss = { ScreenCaptureNotifier.cancel(request.requestId) },
                onSelect = { source, audioMode ->
                    ScreenCaptureNotifier.selectSource(request.requestId, source, audioMode)
                }
            )
        }

        // Screen Recording permission rationale — explains why, before the macOS prompt.
        val permissionRationale by ScreenCaptureNotifier.permissionRationale.collectAsState()
        if (permissionRationale != null) {
            AlertDialog(
                onDismissRequest = { ScreenCaptureNotifier.resolvePermissionRationale(false) },
                title = { Text("Allow screen sharing?") },
                text = {
                    Text(
                        "To share a screen, window, or browser tab, BOSS needs macOS " +
                            "Screen Recording permission. macOS will now ask you to allow \u201CBOSS\u201D. " +
                            "You can change this anytime in System Settings \u203A Privacy & Security \u203A Screen Recording."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { ScreenCaptureNotifier.resolvePermissionRationale(true) }) { Text("Continue") }
                },
                dismissButton = {
                    TextButton(onClick = { ScreenCaptureNotifier.resolvePermissionRationale(false) }) { Text("Not now") }
                }
            )
        }
    }
}

/**
 * Update window title
 *
 * Allows dynamic window title updates based on content
 *
 * @param newTitle The new title for the window
 */
fun BossWindowState.updateTitle(newTitle: String) {
    this.title = newTitle
}
