package ai.rever.boss.keymap.handler

import ai.rever.boss.components.events.MODIFIER_ONLY_KEYS
import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.model.ShortcutContext
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * A shortcut chord that ran on KeyDown and still owns its physical primary key.
 *
 * Modifier releases update [modifiers] rather than deleting the record. This keeps repeats of a
 * still-held key consumed while retaining enough identity to discard a stale claim when a later
 * press arrives in another context or with another modifier combination.
 */
internal data class PendingKeymapShortcut(
    val binding: KeyBinding,
    val primaryKey: Key,
    val context: ShortcutContext,
    val modifiers: KeymapModifierSnapshot,
    val consumed: Boolean,
) {
    fun matches(
        event: KeyEvent,
        candidateContext: ShortcutContext,
    ): Boolean = context == candidateContext && modifiers == KeymapModifierSnapshot.from(event)

    fun usedModifier(key: Key): Boolean = modifiers.includes(key)
}

internal data class KeymapModifierSnapshot(
    val metaDown: Boolean,
    val controlDown: Boolean,
    val shiftDown: Boolean,
    val altDown: Boolean,
) {
    fun includes(key: Key): Boolean =
        when (key) {
            Key.MetaLeft, Key.MetaRight -> metaDown
            Key.CtrlLeft, Key.CtrlRight -> controlDown
            Key.ShiftLeft, Key.ShiftRight -> shiftDown
            Key.AltLeft, Key.AltRight -> altDown
            else -> false
        }

    companion object {
        fun from(event: KeyEvent): KeymapModifierSnapshot =
            KeymapModifierSnapshot(
                metaDown = event.isMetaPressed,
                controlDown = event.isCtrlPressed,
                shiftDown = event.isShiftPressed,
                altDown = event.isAltPressed,
            )
    }
}

/**
 * Context-aware keyboard shortcut handler.
 * Routes keyboard events to appropriate actions based on:
 * 1. Current UI context (browser, terminal, workspace, etc.)
 * 2. Configured key bindings
 * 3. Enabled state of shortcuts
 *
 * Recognizes shortcut chords and executes the action on KeyDown, once: auto-repeat KeyDowns of
 * the held key and its KeyUp are consumed without executing again. Releasing a modifier first
 * cancels nothing (BossConsole#1568). This handler is not wired into the desktop event path yet:
 * unlike the AWT/JxBrowser integration, every action (including browser print) executes on
 * KeyDown. A declined action is remembered until KeyUp so auto-repeat does not retry it, but its
 * press, repeats, and release remain unconsumed.
 *
 * Usage:
 * ```kotlin
 * val handler = KeymapHandler(settings)
 * val result = handler.handleKeyEvent(event, currentContext) { actionId ->
 *     when (actionId) {
 *         KeymapActions.WINDOW_NEW -> windowOperations.createNewWindow()
 *         KeymapActions.TAB_CLOSE -> tabsComponent.closeCurrentTab()
 *         // ... other actions
 *     }
 * }
 * ```
 */
class KeymapHandler(
    settings: KeymapSettings,
) {
    private val logger = BossLogger.forComponent("KeymapHandler")
    private var matcher = KeymapMatcher(settings)
    private var _settings = settings
    private val pendingShortcuts = mutableMapOf<Key, PendingKeymapShortcut>()

    /**
     * Whether a shortcut chord that already ran still owns its physical primary key.
     */
    val hasPendingShortcut: Boolean
        get() = pendingShortcuts.isNotEmpty()

    /**
     * Clear any pending shortcut state (e.g. on focus loss, window deactivation, or cancellation).
     */
    fun clearPendingShortcut() {
        pendingShortcuts.clear()
    }

    /**
     * Current keymap settings.
     */
    val settings: KeymapSettings
        get() = _settings

    /**
     * Update the handler with new settings.
     */
    fun updateSettings(newSettings: KeymapSettings) {
        _settings = newSettings
        matcher = KeymapMatcher(newSettings)
        clearPendingShortcut()
    }

    /**
     * Handle a keyboard event in the given context.
     * On KeyDown: Matches a shortcut chord and executes it (returns true if the executor handled it).
     * On KeyUp: Consumes the release of a key whose KeyDown was consumed; executes nothing.
     * Returns true if the event was handled/consumed, false otherwise.
     *
     * @param event The keyboard event to handle
     * @param context The current UI context
     * @param executor Function that executes an action by ID and returns true if successful
     */
    fun handleKeyEvent(
        event: KeyEvent,
        context: ShortcutContext,
        executor: (actionId: String) -> Boolean,
    ): Boolean {
        pendingShortcuts.entries.removeAll { it.value.context != context }
        return when (event.type) {
            KeyEventType.KeyDown -> runShortcut(event, context, executor)
            KeyEventType.KeyUp -> releaseShortcut(event)
            else -> false
        }
    }

    private fun runShortcut(
        event: KeyEvent,
        context: ShortcutContext,
        executor: (String) -> Boolean,
    ): Boolean =
        when {
            event.key in MODIFIER_ONLY_KEYS -> {
                false
            }

            else -> {
                val held = pendingShortcuts[event.key]
                if (held != null && held.matches(event, context)) return held.consumed
                if (held != null) pendingShortcuts.remove(event.key)
                val binding = matcher.match(event, context)
                binding != null && runAndHold(binding, event, context, executor)
            }
        }

    /**
     * Execute [binding] once and retain its physical chord through KeyUp. Declined chords remain
     * unconsumed but are still held, preventing OS auto-repeat from invoking the executor again.
     */
    private fun runAndHold(
        binding: KeyBinding,
        event: KeyEvent,
        context: ShortcutContext,
        executor: (String) -> Boolean,
    ): Boolean {
        val handled = executor(binding.actionId)
        pendingShortcuts[event.key] =
            PendingKeymapShortcut(
                binding = binding,
                primaryKey = event.key,
                context = context,
                modifiers = KeymapModifierSnapshot.from(event),
                consumed = handled,
            )
        logger.debug(LogCategory.UI, "Ran shortcut", mapOf("actionId" to binding.actionId, "handled" to handled))
        return handled
    }

    private fun releaseShortcut(event: KeyEvent): Boolean {
        if (event.key in MODIFIER_ONLY_KEYS) {
            val modifiers = KeymapModifierSnapshot.from(event)
            for ((key, held) in pendingShortcuts.toMap()) {
                if (held.usedModifier(event.key)) pendingShortcuts[key] = held.copy(modifiers = modifiers)
            }
            return false
        }
        return pendingShortcuts.remove(event.key)?.consumed == true
    }

    /**
     * Get all bindings that would match the given event.
     * Useful for debugging or showing which actions would be triggered.
     */
    fun getMatchingBindings(
        event: KeyEvent,
        context: ShortcutContext,
    ): List<KeyBinding> = matcher.matchAll(event, context)

    /**
     * Check if an action is bound to any key.
     */
    fun isBound(actionId: String): Boolean = _settings.hasBinding(actionId)

    /**
     * Get the binding for a specific action.
     */
    fun getBinding(actionId: String): KeyBinding? = _settings.getBinding(actionId)

    /**
     * Get display string for an action's key binding.
     * Returns null if action is not bound.
     */
    fun getDisplayString(actionId: String): String? = _settings.getBinding(actionId)?.displayString()

    companion object {
        /**
         * Create a KeymapHandler from settings.
         */
        fun from(settings: KeymapSettings): KeymapHandler = KeymapHandler(settings)

        /**
         * Determine the current context based on active component type.
         * This is a helper function that can be called from BossApp.
         */
        fun determineContext(activeComponentType: String?): ShortcutContext =
            when (activeComponentType) {
                "fluck", "browser" -> ShortcutContext.BROWSER
                "terminal" -> ShortcutContext.TERMINAL
                "editor", "code" -> ShortcutContext.EDITOR
                "workspace" -> ShortcutContext.WORKSPACE
                else -> ShortcutContext.GLOBAL
            }
    }
}

/**
 * Action executor interface for dependency injection.
 * Implementations execute the actual business logic for each action.
 */
interface KeymapActionExecutor {
    fun execute(actionId: String): Boolean
}

/**
 * Simple action executor that delegates to a map of action handlers.
 */
class MapBasedActionExecutor(
    private val handlers: Map<String, () -> Unit>,
) : KeymapActionExecutor {
    override fun execute(actionId: String): Boolean {
        val handler = handlers[actionId] ?: return false
        handler()
        return true
    }

    companion object {
        /**
         * Builder for creating a MapBasedActionExecutor.
         */
        fun builder(): Builder = Builder()

        class Builder {
            private val handlers = mutableMapOf<String, () -> Unit>()

            fun on(
                actionId: String,
                handler: () -> Unit,
            ): Builder {
                handlers[actionId] = handler
                return this
            }

            fun build(): MapBasedActionExecutor = MapBasedActionExecutor(handlers)
        }
    }
}
