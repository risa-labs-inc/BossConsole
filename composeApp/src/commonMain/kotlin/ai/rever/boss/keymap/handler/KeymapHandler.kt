package ai.rever.boss.keymap.handler

import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.model.ShortcutContext
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.ui.input.key.KeyEvent

/**
 * Context-aware keyboard shortcut handler.
 * Routes keyboard events to appropriate actions based on:
 * 1. Current UI context (browser, terminal, workspace, etc.)
 * 2. Configured key bindings
 * 3. Enabled state of shortcuts
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
    private val matcher = KeymapMatcher(settings)
    private var _settings = settings

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
    }

    /**
     * Handle a keyboard event in the given context.
     * Returns true if the event was handled, false otherwise.
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
        // Match the event to a binding
        val binding = matcher.match(event, context) ?: return false

        // Execute the action
        val handled = executor(binding.actionId)

        if (handled) {
            logger.debug(LogCategory.UI, "Executed action", mapOf("actionId" to binding.actionId, "description" to binding.description))
        } else {
            logger.debug(LogCategory.UI, "Failed to execute action", mapOf("actionId" to binding.actionId))
        }

        return handled
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
