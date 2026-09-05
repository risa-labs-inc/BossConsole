package ai.rever.boss.keymap.handler

import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeyStroke
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.model.ShortcutContext
import ai.rever.boss.keymap.model.canonicalKeyName
import ai.rever.boss.keymap.model.canonicalModifiers
import ai.rever.boss.utils.SystemUtils
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key

/**
 * Matches keyboard events to key bindings.
 * Handles context-aware matching and modifier key resolution.
 */
class KeymapMatcher(
    private val settings: KeymapSettings,
) {
    /**
     * Find the first matching key binding for a keyboard event in a specific context.
     * Returns null if no match is found.
     *
     * Priority order:
     * 1. Context-specific shortcuts (e.g., BROWSER)
     * 2. WORKSPACE shortcuts (work everywhere)
     * 3. GLOBAL shortcuts (fallback)
     */
    fun match(
        event: KeyEvent,
        context: ShortcutContext,
    ): KeyBinding? {
        // First check context-specific bindings
        val contextCandidates = getEnabledBindingsForContext(context)

        val contextMatch =
            contextCandidates.firstOrNull { binding ->
                matchesBinding(event, binding)
            }

        if (contextMatch != null) return contextMatch

        // Check WORKSPACE shortcuts (work in all contexts)
        if (context != ShortcutContext.WORKSPACE) {
            val workspaceCandidates = getEnabledBindingsForContext(ShortcutContext.WORKSPACE)

            val workspaceMatch =
                workspaceCandidates.firstOrNull { binding ->
                    matchesBinding(event, binding)
                }

            if (workspaceMatch != null) return workspaceMatch
        }

        // If no context-specific match, check GLOBAL shortcuts as fallback
        // (but only if we're not already in GLOBAL context)
        if (context != ShortcutContext.GLOBAL) {
            val globalCandidates = getEnabledBindingsForContext(ShortcutContext.GLOBAL)

            val globalMatch =
                globalCandidates.firstOrNull { binding ->
                    matchesBinding(event, binding)
                }

            if (globalMatch != null) {
                // For TERMINAL context, only intercept GLOBAL shortcuts with system modifiers
                // This follows JxBrowser's pattern: let the component handle typable characters
                // Bindings with only Shift (like '?' = Shift+/) should pass through to terminal
                if (context == ShortcutContext.TERMINAL && !hasSystemModifier(globalMatch)) {
                    return null // Don't intercept - let terminal handle it
                }
                return globalMatch
            }
        }

        return null
    }

    /**
     * Check if a binding requires system modifiers (Cmd/Ctrl/Alt).
     * These are "true" shortcuts that should be intercepted even in text-input contexts.
     * Bindings with only Shift or no modifiers are considered "typable" characters.
     *
     * This follows JxBrowser's pattern: only intercept known system shortcuts,
     * let the component handle everything else (including Shift-only like '?').
     *
     * Public because it is also the honest answer to "would the host actually act on this key?", which
     * is not the same question as "is this key in the keymap". `AWTKeyboardInterceptor` returns before
     * it ever consults the keymap unless Meta, Ctrl or Alt is down, so a bound-but-modifier-less binding
     * — `Shift+/` for the shortcut sheet, say — is never dispatched. Anything deciding whether a key is
     * still available to something else (see `Modifier.forwardUnclaimedKeys` in the remote-UI renderer)
     * must ask this too, or it will decline a key the host was never going to take and the key is lost
     * to everyone.
     */
    fun hasSystemModifier(binding: KeyBinding): Boolean =
        binding.modifiers.any { mod ->
            mod.equals("Cmd", true) || mod.equals("Meta", true) ||
                mod.equals("Ctrl", true) || mod.equals("Control", true) ||
                mod.equals("Alt", true) || mod.equals("Option", true)
        }

    /**
     * Find all matching key bindings for a keyboard event (including global context).
     * Returns list ordered by specificity (context-specific first, global last).
     */
    fun matchAll(
        event: KeyEvent,
        context: ShortcutContext,
    ): List<KeyBinding> {
        val contextBindings = getEnabledBindingsForContext(context)
        val globalBindings =
            if (context != ShortcutContext.GLOBAL) {
                getEnabledBindingsForContext(ShortcutContext.GLOBAL)
            } else {
                emptyList()
            }

        val matches = mutableListOf<KeyBinding>()

        // Check context-specific bindings first
        contextBindings.forEach { binding ->
            if (matchesBinding(event, binding)) {
                matches.add(binding)
            }
        }

        // Then check global bindings
        globalBindings.forEach { binding ->
            if (matchesBinding(event, binding)) {
                matches.add(binding)
            }
        }

        return matches
    }

    /**
     * Which modifiers a keystroke's modifier-name list actually asks for.
     *
     * Extracted so the modifier vocabulary ("Cmd"/"Meta", "Alt"/"Option", ...) is described in
     * one place rather than re-parsed inline at every comparison.
     */
    private data class RequiredModifiers(
        val cmd: Boolean,
        val ctrl: Boolean,
        val shift: Boolean,
        val alt: Boolean,
    ) {
        companion object {
            fun of(modifiers: List<String>): RequiredModifiers {
                val canonical = canonicalModifiers(modifiers)
                return RequiredModifiers(
                    cmd = "cmd" in canonical,
                    ctrl = "ctrl" in canonical,
                    shift = "shift" in canonical,
                    alt = "alt" in canonical,
                )
            }
        }
    }

    /**
     * Check if a keyboard event matches a specific key binding.
     */
    private fun matchesBinding(
        event: KeyEvent,
        binding: KeyBinding,
    ): Boolean {
        if (!binding.enabled) return false

        // Primary keystroke OR any alternate: a binding declaring alternateKeystrokes means
        // "any of these fires this action" (Cmd+Plus alongside Cmd+Equals for zoom in).
        return binding.allKeystrokes.any { matchesKeystroke(event, it) }
    }

    /**
     * Check if a keyboard event matches one keystroke of a binding.
     */
    private fun matchesKeystroke(
        event: KeyEvent,
        keystroke: KeyStroke,
    ): Boolean {
        // Check if key matches
        if (!keyMatches(event.key, keystroke.key)) return false

        // Check modifiers
        val required = RequiredModifiers.of(keystroke.modifiers)

        // Platform-aware modifier matching:
        // - macOS: Cmd key sets isMetaPressed, Ctrl key sets isCtrlPressed
        // - Linux/Windows: Ctrl key sets isCtrlPressed (NOT isMetaPressed)
        // So "Cmd" in binding should match isMetaPressed on macOS, isCtrlPressed on Linux/Windows
        val isMacOS = SystemUtils.isMacOS

        val eventShift = event.isShiftPressed
        val eventAlt = event.isAltPressed

        // Match logic: Handle platform-aware Cmd/Ctrl matching
        val primaryModifierMatch =
            if (required.cmd || required.ctrl) {
                if (isMacOS) {
                    // macOS: Cmd matches Meta, Ctrl matches Ctrl
                    (required.cmd && event.isMetaPressed) || (required.ctrl && event.isCtrlPressed)
                } else {
                    // Linux/Windows: Cmd matches Ctrl (since Ctrl is the primary modifier)
                    // Meta/Super key is rarely used for shortcuts
                    (required.cmd && event.isCtrlPressed) || (required.ctrl && event.isMetaPressed)
                }
            } else {
                // Binding doesn't require primary modifier
                // Event must not have any primary modifier pressed
                !event.isMetaPressed && !event.isCtrlPressed
            }

        val modifierMatch =
            primaryModifierMatch &&
                required.shift == eventShift &&
                required.alt == eventAlt

        return modifierMatch
    }

    /**
     * Check if event key matches binding key.
     * Handles key name normalization and aliases.
     */
    private fun keyMatches(
        eventKey: Key,
        bindingKeyName: String,
    ): Boolean {
        // Extract key name from the Key object
        // Key.toString() format is "Key: X" where X is the key name
        val eventKeyString = eventKey.toString()
        val eventKeyName =
            if (eventKeyString.startsWith("Key: ")) {
                eventKeyString.substring(5).trim()
            } else {
                eventKey.keyCode.toString()
            }

        val eventKeyNormalized = normalizeKeyName(eventKeyName)
        val bindingKeyNormalized = normalizeKeyName(bindingKeyName)

        return eventKeyNormalized.equals(bindingKeyNormalized, ignoreCase = true)
    }

    /**
     * Normalize key names to handle variations, delegated to the model's [canonicalKeyName].
     *
     * This used to be its own table, and the drift is what made Cmd+[ and Cmd+] report "no
     * match" in the Shortcuts tester while firing perfectly through the AWT path: Compose renders
     * `Key.RightBracket` as "Right Bracket" and the presets store "CloseBracket". One fold now
     * serves this matcher, the AWT interceptor and `KeyStroke.signature`.
     */
    private fun normalizeKeyName(keyName: String): String = canonicalKeyName(keyName)

    /**
     * Get all enabled bindings for a specific context.
     */
    private fun getEnabledBindingsForContext(context: ShortcutContext): List<KeyBinding> =
        settings.shortcuts.values
            .filter { it.enabled && it.context == context }

    /**
     * Get the display string for a matched binding.
     */
    fun getDisplayString(binding: KeyBinding): String = binding.displayString()

    companion object {
        /**
         * Create a KeymapMatcher from settings.
         */
        fun from(settings: KeymapSettings): KeymapMatcher = KeymapMatcher(settings)
    }
}
