package ai.rever.boss.keymap.menu

import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.model.canonicalKeyName
import ai.rever.boss.keymap.model.canonicalModifiers
import ai.rever.boss.utils.SystemUtils
import androidx.compose.ui.input.key.Key
import java.awt.event.KeyEvent as AwtKeyEvent

/** The (ctrl, meta) pair a Compose menu accelerator carries for a binding's primary modifiers. */
internal data class MenuAcceleratorModifiers(
    val ctrl: Boolean,
    val meta: Boolean,
)

/**
 * The (ctrl, meta) pair a Compose [androidx.compose.ui.input.key.KeyShortcut] must carry for a
 * binding with [hasCmd] and [hasCtrl] on the platform named by [isMacOS].
 *
 * Pure, and taking the platform as a parameter, so a test can pin BOTH branches on any runner:
 * `SystemUtils.isMacOS` is an `actual val` initialised once from `os.name`, which a test cannot
 * override, so a platform-conditional test only ever exercises the branch of the OS it runs on.
 *
 * The rest of the keymap system treats "Cmd" as the primary modifier (Meta on macOS, Ctrl on
 * Windows/Linux). Compose Desktop's KeyShortcut uses `meta` for the Super/Windows key, so a
 * "Cmd" binding on non-mac must swap to `ctrl` or it would incorrectly require the Super key.
 * Note that we do *not* also map a literal "Ctrl" binding to `meta` on non-mac: the native menu
 * accelerator and its display string both speak in the same physical key, while the AWT/Compose
 * interceptors swap the two to give non-mac users a way to trigger an explicit Ctrl binding.
 */
internal fun menuAcceleratorModifiers(
    hasCmd: Boolean,
    hasCtrl: Boolean,
    isMacOS: Boolean,
): MenuAcceleratorModifiers {
    val ctrl = if (isMacOS) hasCtrl else hasCtrl || hasCmd
    val meta = if (isMacOS) hasCmd else false
    return MenuAcceleratorModifiers(ctrl = ctrl, meta = meta)
}

/**
 * Bridge between menu items and the keymap system.
 * Maps menu actions to keymap action IDs and provides shortcut display strings.
 *
 * Usage:
 * ```kotlin
 * val bridge = MenuShortcutBridge(keymapSettings)
 *
 * // In menu item:
 * Item(
 *     "New Tab",
 *     shortcut = bridge.getKeyShortcut(KeymapActions.TAB_NEW),
 *     onClick = { /* ... */ }
 * )
 * ```
 */
class MenuShortcutBridge(
    private var settings: KeymapSettings,
) {
    /**
     * Update the settings used by this bridge.
     */
    fun updateSettings(newSettings: KeymapSettings) {
        settings = newSettings
    }

    /**
     * Get the KeyBinding for a given action ID.
     * Returns null if the action is not bound.
     */
    fun getBinding(actionId: String): KeyBinding? = settings.getBinding(actionId)

    /**
     * Get the display string for a shortcut (e.g., "⌘N" or "Ctrl+N").
     * Returns null if the action is not bound.
     */
    fun getDisplayString(actionId: String): String? = settings.getBinding(actionId)?.displayString()

    /**
     * Get a KeyShortcut object for use with Compose Desktop MenuBar items.
     * Returns null if the action is not bound.
     *
     * Note: This converts our internal binding format to Compose's KeyShortcut format.
     */
    fun getKeyShortcut(actionId: String): androidx.compose.ui.input.key.KeyShortcut? {
        val binding = settings.getBinding(actionId) ?: return null
        if (!binding.enabled) return null

        val key = keyNameToComposeKey(canonicalKeyName(binding.key)) ?: return null

        // Shared canonicaliser, not a fourth private copy: this one only ever sees
        // preset-authored spellings, so it was harmless, but "one definition" has to be true
        // for the claim on canonicalModifiers to mean anything.
        val modifiers = canonicalModifiers(binding.modifiers)
        val hasCmd = "cmd" in modifiers
        val hasCtrl = "ctrl" in modifiers
        val hasShift = "shift" in modifiers
        val hasAlt = "alt" in modifiers

        val (effectiveCtrl, effectiveMeta) = menuAcceleratorModifiers(hasCmd, hasCtrl, SystemUtils.isMacOS)

        return androidx.compose.ui.input.key.KeyShortcut(
            key = key,
            meta = effectiveMeta,
            ctrl = effectiveCtrl,
            shift = hasShift,
            alt = hasAlt,
        )
    }

    /**
     * Check if an action has a bound shortcut.
     */
    fun hasBoundShortcut(actionId: String): Boolean {
        val binding = settings.getBinding(actionId)
        return binding != null && binding.enabled
    }

    /**
     * Convert a key name string to a Compose Key object.
     */
    private fun keyNameToComposeKey(keyName: String): Key? =
        when (keyName.uppercase()) {
            // Letters
            "A" -> Key.A

            "B" -> Key.B

            "C" -> Key.C

            "D" -> Key.D

            "E" -> Key.E

            "F" -> Key.F

            "G" -> Key.G

            "H" -> Key.H

            "I" -> Key.I

            "J" -> Key.J

            "K" -> Key.K

            "L" -> Key.L

            "M" -> Key.M

            "N" -> Key.N

            "O" -> Key.O

            "P" -> Key.P

            "Q" -> Key.Q

            "R" -> Key.R

            "S" -> Key.S

            "T" -> Key.T

            "U" -> Key.U

            "V" -> Key.V

            "W" -> Key.W

            "X" -> Key.X

            "Y" -> Key.Y

            "Z" -> Key.Z

            // Numbers
            "0", "ZERO" -> Key.Zero

            "1", "ONE" -> Key.One

            "2", "TWO" -> Key.Two

            "3", "THREE" -> Key.Three

            "4", "FOUR" -> Key.Four

            "5", "FIVE" -> Key.Five

            "6", "SIX" -> Key.Six

            "7", "SEVEN" -> Key.Seven

            "8", "EIGHT" -> Key.Eight

            "9", "NINE" -> Key.Nine

            // Function keys
            "F1" -> Key.F1

            "F2" -> Key.F2

            "F3" -> Key.F3

            "F4" -> Key.F4

            "F5" -> Key.F5

            "F6" -> Key.F6

            "F7" -> Key.F7

            "F8" -> Key.F8

            "F9" -> Key.F9

            "F10" -> Key.F10

            "F11" -> Key.F11

            "F12" -> Key.F12

            // Arrow keys
            "LEFT", "ARROWLEFT", "DIRECTIONLEFT" -> Key.DirectionLeft

            "RIGHT", "ARROWRIGHT", "DIRECTIONRIGHT" -> Key.DirectionRight

            "UP", "ARROWUP", "DIRECTIONUP" -> Key.DirectionUp

            "DOWN", "ARROWDOWN", "DIRECTIONDOWN" -> Key.DirectionDown

            // Special keys
            "ENTER", "RETURN" -> Key.Enter

            "SPACE", "SPACEBAR" -> Key.Spacebar

            "TAB" -> Key.Tab

            "ESCAPE", "ESC" -> Key.Escape

            "BACKSPACE" -> Key.Backspace

            "DELETE" -> Key.Delete

            "HOME" -> Key.MoveHome

            "END" -> Key.MoveEnd

            "PAGEUP" -> Key.PageUp

            "PAGEDOWN" -> Key.PageDown

            "INSERT" -> Key.Insert

            // Punctuation
            "COMMA" -> Key.Comma

            "PERIOD" -> Key.Period

            "SLASH" -> Key.Slash

            "BACKSLASH" -> Key.Backslash

            "SEMICOLON" -> Key.Semicolon

            "APOSTROPHE" -> Key.Apostrophe

            "OPENBRACKET" -> Key.LeftBracket

            "CLOSEBRACKET" -> Key.RightBracket

            "MINUS" -> Key.Minus

            "EQUALS" -> Key.Equals

            "GRAVE" -> Key.Grave

            else -> null
        }

    companion object {
        /**
         * Create a MenuShortcutBridge from KeymapSettings.
         */
        fun from(settings: KeymapSettings): MenuShortcutBridge = MenuShortcutBridge(settings)

        /**
         * Maps commonly used menu action names to their keymap action IDs.
         * This allows menu items to use descriptive names that map to our action constants.
         */
        val menuActionMappings =
            mapOf(
                // File menu
                "newTab" to KeymapActions.TAB_NEW,
                "newWindow" to KeymapActions.WINDOW_NEW,
                "closeTab" to KeymapActions.TAB_CLOSE,
                "closeWindow" to KeymapActions.WINDOW_CLOSE,
                "settings" to KeymapActions.SETTINGS_OPEN,
                // Edit menu
                "find" to KeymapActions.EDITOR_FIND,
                "replace" to KeymapActions.EDITOR_REPLACE,
                "findNext" to KeymapActions.EDITOR_FIND_NEXT,
                "findPrevious" to KeymapActions.EDITOR_FIND_PREVIOUS,
                "goToLine" to KeymapActions.EDITOR_GO_TO_LINE,
                // View menu
                "focusMode" to KeymapActions.FOCUS_MODE_TOGGLE,
                "zoomIn" to KeymapActions.BROWSER_ZOOM_IN,
                "zoomOut" to KeymapActions.BROWSER_ZOOM_OUT,
                "actualSize" to KeymapActions.BROWSER_ZOOM_RESET,
                "reload" to KeymapActions.BROWSER_RELOAD,
                // Navigate menu
                "quickSwitcher" to KeymapActions.QUICK_SWITCHER_OPEN,
                "navigateLeft" to KeymapActions.PANEL_NAVIGATE_LEFT,
                "navigateRight" to KeymapActions.PANEL_NAVIGATE_RIGHT,
                "navigateUp" to KeymapActions.PANEL_NAVIGATE_UP,
                "navigateDown" to KeymapActions.PANEL_NAVIGATE_DOWN,
                // Tools menu
                "codebase" to KeymapActions.CODEBASE_OPEN,
                // Workspace menu
                "saveWorkspace" to KeymapActions.WORKSPACE_SAVE,
            )

        /**
         * Get the action ID for a menu action name.
         */
        fun getActionId(menuActionName: String): String? = menuActionMappings[menuActionName]
    }
}
