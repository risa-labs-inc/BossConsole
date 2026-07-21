package ai.rever.boss.window

import ai.rever.boss.components.plugin.registries.PluginShortcutRegistryImpl
import ai.rever.boss.keymap.KeymapSettingsManager
import ai.rever.boss.keymap.handler.KeymapMatcher
import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.keymap.model.ShortcutContext
import ai.rever.boss.keymap.model.TabSwitchMode
import ai.rever.boss.utils.SystemUtils
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.KeyEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * AWT-level keyboard interceptor that captures keyboard shortcuts before they reach
 * Swing/AWT components like BossTerm terminals.
 *
 * This solves the issue where BossTerm consumes all keyboard events for terminal emulation,
 * preventing global shortcuts (Cmd+N, Cmd+W, etc.) from working.
 *
 * The interceptor uses KeyboardFocusManager to intercept events at the AWT level,
 * checks if they match registered shortcuts, and dispatches actions through
 * MenuActionsHandler if matched.
 */
object AWTKeyboardInterceptor {

    private var isInstalled = false
    private var dispatcher: KeyEventDispatcher? = null

    /**
     * Map of AWT window to BOSS window ID for routing events to correct window.
     * Uses ConcurrentHashMap for thread-safety (AWT events come from EDT).
     */
    private val windowIdMap = ConcurrentHashMap<Window, String>()

    /**
     * Per-window active context tracking.
     * Updated by Compose layer when the active tab type changes.
     */
    private val windowContextMap = ConcurrentHashMap<String, ShortcutContext>()

    // Double-shift detection for global search (like IntelliJ's Search Everywhere)
    private var lastShiftPressTime: Long = 0
    private var lastShiftReleaseTime: Long = 0
    private var shiftPressCount: Int = 0
    // 500ms threshold follows accessibility guidelines for double-tap gestures (typically 500-800ms)
    private const val DOUBLE_SHIFT_THRESHOLD_MS = 500

    // MRU tab-cycle tracking. Set when Ctrl+Tab starts a cycle in MRU mode, alongside the
    // physical keycode of the modifier sustaining it; the cycle commits only when THAT
    // modifier is released. This avoids (a) emitting a commit on every unrelated Ctrl/Cmd
    // keyup, and (b) committing early when a different modifier is released mid-cycle.
    // Accessed only from the AWT event dispatch thread. Process-global (like the double-
    // shift state above): cycling in one window then focusing another without releasing the
    // modifier is a benign mismatch — the stray release just no-ops downstream.
    private var tabCycleActive = false
    private var tabCycleModifierKeyCode = -1
    // Minimum time shift must be released to count as a clean release (prevents false positives from held shift)
    private const val MIN_SHIFT_RELEASE_MS = 50

    /**
     * Register an AWT window with its BOSS window ID.
     * Call this from BossWindow's DisposableEffect when window is created.
     */
    fun registerWindow(awtWindow: Window, windowId: String) {
        windowIdMap[awtWindow] = windowId
    }

    /**
     * Unregister an AWT window when it's closed.
     * Call this from BossWindow's DisposableEffect onDispose.
     */
    fun unregisterWindow(awtWindow: Window) {
        val windowId = windowIdMap.remove(awtWindow)
        if (windowId != null) {
            windowContextMap.remove(windowId)
        }
    }

    /**
     * Update the active shortcut context for a window.
     * Called from the Compose layer when the active tab type changes.
     *
     * @param windowId The BOSS window ID
     * @param context The shortcut context of the currently active component
     */
    fun updateWindowContext(windowId: String, context: ShortcutContext) {
        windowContextMap[windowId] = context
    }

    /**
     * Clear the active context for a window (reverts to GLOBAL).
     *
     * @param windowId The BOSS window ID
     */
    fun clearWindowContext(windowId: String) {
        windowContextMap.remove(windowId)
    }

    /**
     * Install the global keyboard interceptor.
     * Should be called once at application startup.
     */
    fun install() {
        if (isInstalled) return

        dispatcher = KeyEventDispatcher { event ->
            // Handle double-shift detection for global search
            if (event.keyCode == KeyEvent.VK_SHIFT) {
                val currentTime = System.currentTimeMillis()

                when (event.id) {
                    KeyEvent.KEY_PRESSED -> {
                        // Check if this is a quick second press after a clean release
                        val timeSinceRelease = currentTime - lastShiftReleaseTime
                        if (timeSinceRelease < DOUBLE_SHIFT_THRESHOLD_MS &&
                            timeSinceRelease >= MIN_SHIFT_RELEASE_MS && // Ensure clean release (not held)
                            shiftPressCount == 1) {
                            // Double-shift detected!
                            shiftPressCount = 0
                            lastShiftPressTime = 0
                            lastShiftReleaseTime = 0

                            // Get the focused window's BOSS window ID
                            val focusedWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
                            val windowId = findWindowId(focusedWindow)
                            if (windowId != null) {
                                try {
                                    MenuActionsHandler.triggerOpenGlobalSearch(windowId)
                                    event.consume()
                                    return@KeyEventDispatcher true
                                } catch (e: Exception) {
                                    // Log but don't crash the event dispatcher
                                    System.err.println("Error triggering global search: ${e.message}")
                                }
                            }
                        } else {
                            // First shift press or timeout - start counting
                            shiftPressCount = 1
                            lastShiftPressTime = currentTime
                        }
                    }
                    KeyEvent.KEY_RELEASED -> {
                        // Record release time for detecting second press
                        if (shiftPressCount == 1 && currentTime - lastShiftPressTime < DOUBLE_SHIFT_THRESHOLD_MS) {
                            lastShiftReleaseTime = currentTime
                        } else {
                            // Too slow or wrong sequence - reset
                            shiftPressCount = 0
                        }
                    }
                }
                return@KeyEventDispatcher false // Let shift events propagate
            }

            // Reset double-shift state if any other key is pressed
            if (event.id == KeyEvent.KEY_PRESSED && !isModifierOnlyKey(event.keyCode)) {
                shiftPressCount = 0
                lastShiftPressTime = 0
                lastShiftReleaseTime = 0
            }

            // Commit an in-progress MRU tab cycle when its own cycling modifier is released.
            // Only fires while a cycle is active and only for that specific modifier, so
            // unrelated modifier keyups don't churn the UI thread or commit prematurely.
            // The release itself is not consumed.
            if (event.id == KeyEvent.KEY_RELEASED && tabCycleActive && event.keyCode == tabCycleModifierKeyCode) {
                tabCycleActive = false
                val focusedWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
                findWindowId(focusedWindow)?.let { MenuActionsHandler.triggerCommitTabCycle(it) }
                return@KeyEventDispatcher false
            }

            // Only intercept KEY_PRESSED events for other shortcuts
            if (event.id != KeyEvent.KEY_PRESSED) {
                return@KeyEventDispatcher false
            }

            // Skip if no modifier keys are pressed (most shortcuts require modifiers)
            if (!event.isMetaDown && !event.isControlDown && !event.isAltDown) {
                return@KeyEventDispatcher false
            }

            // Skip modifier-only key presses
            if (isModifierOnlyKey(event.keyCode)) {
                return@KeyEventDispatcher false
            }

            // Get the focused window's BOSS window ID
            val focusedWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
            val windowId = findWindowId(focusedWindow) ?: return@KeyEventDispatcher false

            // Get current keymap settings and create matcher
            val settings = KeymapSettingsManager.currentSettings.value
            val matcher = KeymapMatcher(settings)

            // Try to match the key event against shortcuts
            val binding = findMatchingBinding(event, matcher)

            if (binding != null) {
                // Dispatch the action through MenuActionsHandler
                val handled = dispatchAction(binding.actionId, windowId)
                if (handled) {
                    // Begin (or continue) an MRU tab cycle: remember which modifier is
                    // sustaining it so its release — and only its release — commits the cycle.
                    // This arms even when the focused panel has <=1 tab (the component-side
                    // switchTab/commit then no-op), so the interceptor may briefly believe a
                    // cycle is active when none is — harmless, and Tab stays swallowed.
                    if ((binding.actionId == KeymapActions.TAB_NEXT || binding.actionId == KeymapActions.TAB_PREVIOUS) &&
                        KeymapSettingsManager.currentSettings.value.tabSwitchMode == TabSwitchMode.MRU
                    ) {
                        tabCycleActive = true
                        tabCycleModifierKeyCode = cyclingModifierKeyCode(binding)
                    }
                    // Consume the event to prevent it from reaching BossTerm
                    event.consume()
                    return@KeyEventDispatcher true
                }
            }

            // Plugin-contributed GLOBAL shortcuts (PluginShortcutRegistry).
            // Host bindings always win — this pass only runs when no host
            // binding matched. User rebinds live in the keymap settings under
            // the plugin actionId (matched by the pass above); a spec's
            // defaultBinding applies only while the keymap has no entry for
            // that actionId.
            val pluginActionId = findMatchingPluginDefault(event)
            if (pluginActionId != null) {
                val handled = PluginShortcutRegistryImpl.dispatch(pluginActionId, windowId)
                if (handled) {
                    event.consume()
                    return@KeyEventDispatcher true
                }
            }

            false // Let event propagate normally
        }

        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
        isInstalled = true
    }

    /**
     * Uninstall the keyboard interceptor.
     * Should be called when the application exits.
     */
    fun uninstall() {
        dispatcher?.let { d ->
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(d)
        }
        dispatcher = null
        isInstalled = false
        windowIdMap.clear()
        windowContextMap.clear()
    }

    /**
     * Find the BOSS window ID for an AWT window, checking parent windows.
     */
    private fun findWindowId(window: Window?): String? {
        var current: Window? = window
        while (current != null) {
            val id = windowIdMap[current]
            if (id != null) return id
            current = current.owner
        }
        return null
    }

    /**
     * The physical modifier keycode that sustains an MRU tab cycle for [binding], mirroring
     * the platform-aware mapping in findMatchingBinding: a "Ctrl" binding is the Control key
     * on macOS but the Meta key on Windows/Linux (and vice-versa for a "Cmd" binding).
     */
    private fun cyclingModifierKeyCode(binding: KeyBinding): Int {
        val hasCmd = binding.modifiers.any { it.equals("Cmd", true) || it.equals("Meta", true) }
        return if (SystemUtils.isMacOS) {
            if (hasCmd) KeyEvent.VK_META else KeyEvent.VK_CONTROL
        } else {
            if (hasCmd) KeyEvent.VK_CONTROL else KeyEvent.VK_META
        }
    }

    /**
     * Check if a key code represents a modifier-only key.
     */
    private fun isModifierOnlyKey(keyCode: Int): Boolean {
        return keyCode in setOf(
            KeyEvent.VK_SHIFT,
            KeyEvent.VK_CONTROL,
            KeyEvent.VK_ALT,
            KeyEvent.VK_META,
            KeyEvent.VK_CAPS_LOCK,
            KeyEvent.VK_NUM_LOCK,
            KeyEvent.VK_SCROLL_LOCK
        )
    }

    /**
     * Detect the current shortcut context based on:
     * 1. Explicit per-window context (set by Compose layer)
     * 2. AWT focus owner class hierarchy (fallback)
     */
    private fun detectCurrentContext(windowId: String?): ShortcutContext {
        // Primary: explicit per-window context from Compose layer
        if (windowId != null) {
            windowContextMap[windowId]?.let { return it }
        }

        // Fallback: detect from AWT focus owner's class hierarchy
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        return detectContextFromAwtComponent(focusOwner)
    }

    /**
     * Detect shortcut context by walking up the AWT component hierarchy.
     * JxBrowser components have "jxbrowser" in their package name.
     */
    private fun detectContextFromAwtComponent(component: java.awt.Component?): ShortcutContext {
        var current: java.awt.Component? = component
        while (current != null) {
            val className = current.javaClass.name
            if (className.contains("jxbrowser", ignoreCase = true)) {
                return ShortcutContext.BROWSER
            }
            if (className.contains("bossterm", ignoreCase = true) ||
                className.contains("TerminalPanel", ignoreCase = false)) {
                return ShortcutContext.TERMINAL
            }
            current = current.parent
        }
        return ShortcutContext.GLOBAL
    }

    /**
     * Check if a binding's context is eligible given the current active context.
     * GLOBAL and WORKSPACE bindings always match.
     * Component-specific bindings (BROWSER, TERMINAL, EDITOR) only match their context.
     */
    private fun isContextEligible(bindingContext: ShortcutContext, currentContext: ShortcutContext): Boolean {
        return when (bindingContext) {
            ShortcutContext.GLOBAL -> true
            ShortcutContext.WORKSPACE -> true
            else -> bindingContext == currentContext
        }
    }

    /**
     * Find a matching binding for the AWT KeyEvent.
     * Context-aware: skips component-specific bindings when the focused component
     * doesn't match, and prefers bindings whose context matches the current focus.
     */
    private fun findMatchingBinding(event: KeyEvent, matcher: KeymapMatcher): KeyBinding? {
        val keyName = getKeyName(event.keyCode)
        val settings = KeymapSettingsManager.currentSettings.value

        // Detect current context for filtering
        val focusedWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
        val windowId = findWindowId(focusedWindow)
        val currentContext = detectCurrentContext(windowId)

        // Collect all matching bindings with their context priority
        var bestMatch: KeyBinding? = null
        var bestPriority = -1

        for (binding in settings.shortcuts.values) {
            if (!binding.enabled) continue

            // Check if key matches
            if (!binding.key.equals(keyName, ignoreCase = true)) continue

            if (chordMatchesEvent(binding.modifiers, event)) {
                // Skip bindings whose context doesn't match
                if (!isContextEligible(binding.context, currentContext)) continue

                // Prioritize: exact context match > GLOBAL > WORKSPACE
                val priority = when {
                    binding.context == currentContext -> 3
                    binding.context == ShortcutContext.GLOBAL -> 2
                    binding.context == ShortcutContext.WORKSPACE -> 1
                    else -> 0
                }

                if (priority > bestPriority) {
                    bestMatch = binding
                    bestPriority = priority
                }
            }
        }

        return bestMatch
    }

    /**
     * Match the event against plugin shortcut DEFAULT bindings. Only specs
     * whose actionId has no entry in the keymap settings participate — a user
     * rebind (or explicit unbind) always supersedes the plugin default. Note
     * the dispatcher's early modifier gate applies: plugin defaults must
     * include Cmd/Ctrl/Alt to be reachable.
     */
    private fun findMatchingPluginDefault(event: KeyEvent): String? {
        val pluginShortcuts = PluginShortcutRegistryImpl.shortcuts.value
        if (pluginShortcuts.isEmpty()) return null

        val keyName = getKeyName(event.keyCode)
        val userShortcuts = KeymapSettingsManager.currentSettings.value.shortcuts

        for (registered in pluginShortcuts) {
            val spec = registered.spec
            val default = spec.defaultBinding ?: continue
            if (userShortcuts.containsKey(spec.actionId)) continue
            if (!default.key.equals(keyName, ignoreCase = true)) continue
            if (chordMatchesEvent(default.modifiers, event)) {
                return spec.actionId
            }
        }
        return null
    }

    /**
     * Platform-aware modifier match shared by the host-binding pass and the
     * plugin-default pass, so both agree on Cmd/Ctrl handling (on non-mac,
     * "Cmd" maps to the Control key). [modifiers] is the binding's modifier
     * name list; the caller has already matched the key.
     */
    private fun chordMatchesEvent(modifiers: Collection<String>, event: KeyEvent): Boolean {
        val hasCmd = modifiers.any { it.equals("Cmd", true) || it.equals("Meta", true) }
        val hasCtrl = modifiers.any { it.equals("Ctrl", true) || it.equals("Control", true) }
        val hasShift = modifiers.any { it.equals("Shift", true) }
        val hasAlt = modifiers.any { it.equals("Alt", true) || it.equals("Option", true) }

        val primaryMatch = if (hasCmd || hasCtrl) {
            if (SystemUtils.isMacOS) {
                (hasCmd && event.isMetaDown) || (hasCtrl && event.isControlDown)
            } else {
                (hasCmd && event.isControlDown) || (hasCtrl && event.isMetaDown)
            }
        } else {
            !event.isMetaDown && !event.isControlDown
        }
        return primaryMatch && hasShift == event.isShiftDown && hasAlt == event.isAltDown
    }

    /**
     * Convert AWT key code to key name string.
     */
    private fun getKeyName(keyCode: Int): String {
        return when (keyCode) {
            KeyEvent.VK_A -> "A"
            KeyEvent.VK_B -> "B"
            KeyEvent.VK_C -> "C"
            KeyEvent.VK_D -> "D"
            KeyEvent.VK_E -> "E"
            KeyEvent.VK_F -> "F"
            KeyEvent.VK_G -> "G"
            KeyEvent.VK_H -> "H"
            KeyEvent.VK_I -> "I"
            KeyEvent.VK_J -> "J"
            KeyEvent.VK_K -> "K"
            KeyEvent.VK_L -> "L"
            KeyEvent.VK_M -> "M"
            KeyEvent.VK_N -> "N"
            KeyEvent.VK_O -> "O"
            KeyEvent.VK_P -> "P"
            KeyEvent.VK_Q -> "Q"
            KeyEvent.VK_R -> "R"
            KeyEvent.VK_S -> "S"
            KeyEvent.VK_T -> "T"
            KeyEvent.VK_U -> "U"
            KeyEvent.VK_V -> "V"
            KeyEvent.VK_W -> "W"
            KeyEvent.VK_X -> "X"
            KeyEvent.VK_Y -> "Y"
            KeyEvent.VK_Z -> "Z"
            KeyEvent.VK_0 -> "Zero"
            KeyEvent.VK_1 -> "One"
            KeyEvent.VK_2 -> "Two"
            KeyEvent.VK_3 -> "Three"
            KeyEvent.VK_4 -> "Four"
            KeyEvent.VK_5 -> "Five"
            KeyEvent.VK_6 -> "Six"
            KeyEvent.VK_7 -> "Seven"
            KeyEvent.VK_8 -> "Eight"
            KeyEvent.VK_9 -> "Nine"
            KeyEvent.VK_ENTER -> "Enter"
            KeyEvent.VK_ESCAPE -> "Esc"
            KeyEvent.VK_SPACE -> "Space"
            KeyEvent.VK_TAB -> "Tab"
            KeyEvent.VK_BACK_SPACE -> "Backspace"
            KeyEvent.VK_DELETE -> "Delete"
            KeyEvent.VK_LEFT -> "Left"
            KeyEvent.VK_RIGHT -> "Right"
            KeyEvent.VK_UP -> "Up"
            KeyEvent.VK_DOWN -> "Down"
            KeyEvent.VK_HOME -> "Home"
            KeyEvent.VK_END -> "End"
            KeyEvent.VK_PAGE_UP -> "PageUp"
            KeyEvent.VK_PAGE_DOWN -> "PageDown"
            KeyEvent.VK_F1 -> "F1"
            KeyEvent.VK_F2 -> "F2"
            KeyEvent.VK_F3 -> "F3"
            KeyEvent.VK_F4 -> "F4"
            KeyEvent.VK_F5 -> "F5"
            KeyEvent.VK_F6 -> "F6"
            KeyEvent.VK_F7 -> "F7"
            KeyEvent.VK_F8 -> "F8"
            KeyEvent.VK_F9 -> "F9"
            KeyEvent.VK_F10 -> "F10"
            KeyEvent.VK_F11 -> "F11"
            KeyEvent.VK_F12 -> "F12"
            KeyEvent.VK_MINUS -> "Minus"
            KeyEvent.VK_EQUALS -> "Equals"
            KeyEvent.VK_PLUS -> "Plus"
            KeyEvent.VK_OPEN_BRACKET -> "OpenBracket"
            KeyEvent.VK_CLOSE_BRACKET -> "CloseBracket"
            KeyEvent.VK_SLASH -> "Slash"
            KeyEvent.VK_BACK_SLASH -> "Backslash"
            KeyEvent.VK_SEMICOLON -> "Semicolon"
            KeyEvent.VK_QUOTE -> "Apostrophe"
            KeyEvent.VK_COMMA -> "Comma"
            KeyEvent.VK_PERIOD -> "Period"
            KeyEvent.VK_BACK_QUOTE -> "Grave"
            else -> KeyEvent.getKeyText(keyCode)
        }
    }

    /**
     * Dispatch an action through MenuActionsHandler.
     * Returns true if the action was handled, false otherwise.
     */
    private fun dispatchAction(actionId: String, windowId: String): Boolean {
        return when (actionId) {
            // Tab Management
            KeymapActions.TAB_NEW -> {
                MenuActionsHandler.triggerNewTab(windowId)
                true
            }
            KeymapActions.TAB_CLOSE -> {
                MenuActionsHandler.triggerCloseTab(windowId)
                true
            }
            KeymapActions.TAB_NEXT -> {
                MenuActionsHandler.triggerNextTab(windowId)
                true
            }
            KeymapActions.TAB_PREVIOUS -> {
                MenuActionsHandler.triggerPreviousTab(windowId)
                true
            }

            // Window Management
            KeymapActions.WINDOW_NEW -> {
                WindowOperations.createNewWindow()
                true
            }
            KeymapActions.WINDOW_CLOSE -> {
                WindowOperations.closeWindow(windowId)
                true
            }

            // Browser Controls (Zoom)
            KeymapActions.BROWSER_ZOOM_IN -> {
                MenuActionsHandler.triggerZoomIn(windowId)
                true
            }
            KeymapActions.BROWSER_ZOOM_OUT -> {
                MenuActionsHandler.triggerZoomOut(windowId)
                true
            }
            KeymapActions.BROWSER_ZOOM_RESET -> {
                MenuActionsHandler.triggerActualSize(windowId)
                true
            }

            // View Controls
            KeymapActions.FOCUS_MODE_TOGGLE -> {
                MenuActionsHandler.triggerToggleFocusMode(windowId)
                true
            }

            // Panel Navigation
            KeymapActions.PANEL_NAVIGATE_LEFT -> {
                MenuActionsHandler.triggerNavigatePanelLeft(windowId)
                true
            }
            KeymapActions.PANEL_NAVIGATE_RIGHT -> {
                MenuActionsHandler.triggerNavigatePanelRight(windowId)
                true
            }
            KeymapActions.PANEL_NAVIGATE_UP -> {
                MenuActionsHandler.triggerNavigatePanelUp(windowId)
                true
            }
            KeymapActions.PANEL_NAVIGATE_DOWN -> {
                MenuActionsHandler.triggerNavigatePanelDown(windowId)
                true
            }

            // Split Panel
            KeymapActions.PANEL_SPLIT_VERTICAL -> {
                MenuActionsHandler.triggerSplitVertically(windowId)
                true
            }
            KeymapActions.PANEL_SPLIT_HORIZONTAL -> {
                MenuActionsHandler.triggerSplitHorizontally(windowId)
                true
            }

            // Browser Controls
            KeymapActions.BROWSER_RELOAD -> {
                MenuActionsHandler.triggerReloadBrowser(windowId)
                true
            }
            KeymapActions.BROWSER_FIND -> {
                MenuActionsHandler.triggerBrowserFind(windowId)
                true
            }

            // Codebase
            KeymapActions.CODEBASE_OPEN -> {
                MenuActionsHandler.triggerOpenCodebase(windowId)
                true
            }

            // Global Search
            KeymapActions.GLOBAL_SEARCH_OPEN -> {
                MenuActionsHandler.triggerOpenGlobalSearch(windowId)
                true
            }

            // Settings
            KeymapActions.SETTINGS_OPEN -> {
                MenuActionsHandler.triggerOpenSettings(windowId)
                true
            }

            // Workspace
            KeymapActions.WORKSPACE_SAVE -> {
                MenuActionsHandler.triggerSaveWorkspace(windowId)
                true
            }

            // Help
            KeymapActions.HELP_SHORTCUTS -> {
                MenuActionsHandler.triggerShowShortcutHelp(windowId)
                true
            }

            else -> {
                // Plugin-contributed actions ("plugin.<pluginId>.<name>") —
                // reached when the user rebound a plugin shortcut (the binding
                // then lives in the keymap settings and matches the main pass).
                if (actionId.startsWith(PluginShortcutRegistryImpl.ACTION_ID_PREFIX)) {
                    PluginShortcutRegistryImpl.dispatch(actionId, windowId)
                } else {
                    false
                }
            }
        }
    }
}
