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
 * Information about an armed shortcut chord pending primary key release.
 */
data class PendingKeymapShortcut(
    val binding: KeyBinding,
    val primaryKey: Key,
    val context: ShortcutContext,
)

/**
 * Context-aware keyboard shortcut handler.
 * Routes keyboard events to appropriate actions based on:
 * 1. Current UI context (browser, terminal, workspace, etc.)
 * 2. Configured key bindings
 * 3. Enabled state of shortcuts
 *
 * Recognizes shortcut chords on KeyDown and executes the action on primary KeyUp.
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
    private val claimedKeys = mutableSetOf<Key>()

    /**
     * Whether a shortcut chord is currently armed and pending release.
     */
    val hasPendingShortcut: Boolean
        get() = pendingShortcuts.isNotEmpty()

    /**
     * Clear any pending shortcut state (e.g. on focus loss, window deactivation, or cancellation).
     */
    fun clearPendingShortcut() {
        pendingShortcuts.clear()
        claimedKeys.clear()
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
     * On KeyDown: Matches and arms a shortcut chord (returns true if matched/consumed without executing).
     * On KeyUp: Executes the action when the matching primary key is released.
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
        val pending = pendingShortcuts[event.key]
        val hasModifier =
            listOf(event.isMetaPressed, event.isCtrlPressed, event.isAltPressed, event.isShiftPressed).any { it }
        val barePress = event.type == KeyEventType.KeyDown && !hasModifier
        if (barePress && pending != null && pending.binding.modifiers.isNotEmpty()) {
            pendingShortcuts.remove(event.key)
            claimedKeys.remove(event.key)
        }
        return when (event.type) {
            KeyEventType.KeyDown -> armShortcut(event, context)
            KeyEventType.KeyUp -> releaseShortcut(event, executor)
            else -> false
        }
    }

    private fun armShortcut(
        event: KeyEvent,
        context: ShortcutContext,
    ): Boolean =
        when {
            event.key in MODIFIER_ONLY_KEYS -> {
                false
            }

            event.key in claimedKeys -> {
                true
            }

            else -> {
                val binding = matcher.match(event, context)
                if (binding != null) pendingShortcuts[event.key] = PendingKeymapShortcut(binding, event.key, context)
                if (binding != null) claimedKeys.add(event.key)
                binding != null
            }
        }

    private fun releaseShortcut(
        event: KeyEvent,
        executor: (String) -> Boolean,
    ): Boolean {
        val claimed = claimedKeys.remove(event.key)
        if (event.key in MODIFIER_ONLY_KEYS) pendingShortcuts.clear()
        val pending = pendingShortcuts.remove(event.key)
        if (pending != null) {
            val handled = executor(pending.binding.actionId)
            logger.debug(
                LogCategory.UI,
                "Released shortcut",
                mapOf("actionId" to pending.binding.actionId, "handled" to handled),
            )
        }
        return claimed
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
