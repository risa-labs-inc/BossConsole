package ai.rever.boss.keymap.lifecycle

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Represents the current lifecycle state of a keyboard shortcut.
 */
data class ShortcutLifecycleState(
    val actionId: String,
    val enabled: Boolean,
    val reason: String? = null,
    val condition: ShortcutLifecycleCondition? = null,
)

/**
 * Manages lifecycle conditions for keyboard shortcuts.
 * Tracks which shortcuts are enabled/disabled based on application state.
 *
 * Usage:
 * ```
 * // Register conditions
 * ShortcutLifecycleManager.registerCondition(
 *     KeymapActions.TAB_CLOSE,
 *     TabCountCondition(windowState)
 * )
 *
 * // Check if enabled before executing
 * if (ShortcutLifecycleManager.isEnabled(KeymapActions.TAB_CLOSE)) {
 *     closeTab()
 * }
 *
 * // Trigger reevaluation when state changes
 * LaunchedEffect(windowState.tabs.size) {
 *     ShortcutLifecycleManager.reevaluate()
 * }
 * ```
 */
object ShortcutLifecycleManager {
    private val logger = BossLogger.forComponent("ShortcutLifecycleManager")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Registered conditions for each action ID.
     * Uses ConcurrentHashMap for thread-safe access from multiple coroutines.
     */
    private val conditions = ConcurrentHashMap<String, ShortcutLifecycleCondition>()

    /**
     * Current lifecycle states for all shortcuts.
     */
    private val _states = MutableStateFlow<Map<String, ShortcutLifecycleState>>(emptyMap())
    val states: StateFlow<Map<String, ShortcutLifecycleState>> = _states.asStateFlow()

    /**
     * Debug mode - logs all state changes.
     */
    var debugMode: Boolean = false

    /**
     * Registers a lifecycle condition for a shortcut action.
     * Immediately evaluates the condition and updates state.
     *
     * @param actionId The action ID (e.g., KeymapActions.TAB_CLOSE)
     * @param condition The condition that determines if this shortcut is enabled
     */
    fun registerCondition(
        actionId: String,
        condition: ShortcutLifecycleCondition,
    ) {
        conditions[actionId] = condition

        if (debugMode) {
            logger.debug(LogCategory.UI, "Registered condition", mapOf("actionId" to actionId))
        }

        // Immediately evaluate the condition
        scope.launch {
            evaluateSingle(actionId)
        }
    }

    /**
     * Unregisters the lifecycle condition for a shortcut action.
     * The shortcut will be considered always enabled after unregistration.
     *
     * @param actionId The action ID to unregister
     */
    fun unregisterCondition(actionId: String) {
        conditions.remove(actionId)

        _states.value = _states.value.filterKeys { it != actionId }

        if (debugMode) {
            logger.debug(LogCategory.UI, "Unregistered condition", mapOf("actionId" to actionId))
        }
    }

    /**
     * Checks if a shortcut is currently enabled.
     * Returns true if no condition is registered (default enabled).
     *
     * @param actionId The action ID to check
     * @return true if the shortcut is enabled, false otherwise
     */
    suspend fun isEnabled(actionId: String): Boolean {
        val condition = conditions[actionId] ?: return true // No condition = always enabled

        return try {
            condition.isEnabled()
        } catch (e: Exception) {
            logger.warn(LogCategory.UI, "Error checking condition", mapOf("actionId" to actionId), error = e)
            false // Fail-safe: disable on error
        }
    }

    /**
     * Gets the lifecycle state for a shortcut synchronously.
     * Returns null if no state has been computed yet.
     *
     * @param actionId The action ID to get state for
     * @return The current lifecycle state, or null if not computed
     */
    fun getState(actionId: String): ShortcutLifecycleState? = _states.value[actionId]

    /**
     * Gets the disabled reason for a shortcut.
     * Returns null if the shortcut is enabled or has no condition.
     *
     * @param actionId The action ID to get reason for
     * @return The disabled reason, or null if enabled
     */
    fun getDisabledReason(actionId: String): String? {
        val state = _states.value[actionId] ?: return null
        return if (!state.enabled) state.reason else null
    }

    /**
     * Reevaluates all registered lifecycle conditions.
     * Should be called when application state changes that might affect conditions.
     *
     * Examples of when to call:
     * - Tab count changes
     * - Panel/split state changes
     * - Clipboard content changes
     * - Selection changes
     */
    suspend fun reevaluate() {
        if (debugMode) {
            logger.debug(LogCategory.UI, "Reevaluating all conditions", mapOf("total" to conditions.size))
        }

        val newStates = mutableMapOf<String, ShortcutLifecycleState>()

        for ((actionId, condition) in conditions) {
            try {
                val enabled = condition.isEnabled()
                newStates[actionId] =
                    ShortcutLifecycleState(
                        actionId = actionId,
                        enabled = enabled,
                        reason = if (enabled) null else condition.disabledReason,
                        condition = condition,
                    )

                if (debugMode) {
                    logger.debug(
                        LogCategory.UI,
                        "Condition evaluated",
                        mapOf(
                            "actionId" to actionId,
                            "enabled" to enabled,
                            "reason" to condition.disabledReason,
                        ),
                    )
                }
            } catch (e: Exception) {
                logger.warn(LogCategory.UI, "Error evaluating condition", mapOf("actionId" to actionId), error = e)
                newStates[actionId] =
                    ShortcutLifecycleState(
                        actionId = actionId,
                        enabled = false,
                        reason = "Error: ${e.message}",
                        condition = condition,
                    )
            }
        }

        _states.value = newStates
    }

    /**
     * Reevaluates a single condition.
     * More efficient than reevaluating all when only one condition changed.
     *
     * @param actionId The action ID to reevaluate
     */
    suspend fun reevaluateSingle(actionId: String) {
        evaluateSingle(actionId)
    }

    /**
     * Internal helper to evaluate a single condition.
     */
    private suspend fun evaluateSingle(actionId: String) {
        val condition = conditions[actionId] ?: return

        try {
            val enabled = condition.isEnabled()
            val newState =
                ShortcutLifecycleState(
                    actionId = actionId,
                    enabled = enabled,
                    reason = if (enabled) null else condition.disabledReason,
                    condition = condition,
                )

            _states.value = _states.value + (actionId to newState)

            if (debugMode) {
                logger.debug(
                    LogCategory.UI,
                    "Updated condition",
                    mapOf(
                        "actionId" to actionId,
                        "enabled" to enabled,
                        "reason" to condition.disabledReason,
                    ),
                )
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.UI, "Error evaluating condition", mapOf("actionId" to actionId), error = e)

            _states.value = _states.value + (
                actionId to
                    ShortcutLifecycleState(
                        actionId = actionId,
                        enabled = false,
                        reason = "Error: ${e.message}",
                        condition = condition,
                    )
            )
        }
    }

    /**
     * Clears all registered conditions and states.
     * Useful for testing or resetting the manager.
     */
    fun clear() {
        conditions.clear()
        _states.value = emptyMap()

        if (debugMode) {
            logger.debug(LogCategory.UI, "Cleared all conditions and states")
        }
    }

    /**
     * Gets all registered action IDs with conditions.
     */
    fun getRegisteredActions(): Set<String> = conditions.keys.toSet()

    /**
     * Starts automatic reevaluation on a fixed interval.
     * Useful for conditions that depend on external state (clipboard, etc.).
     *
     * @param intervalMs The interval in milliseconds (default: 1000ms)
     * @return Job that can be cancelled to stop automatic reevaluation
     */
    fun startAutoReevaluation(intervalMs: Long = 1000): Job =
        scope.launch {
            while (isActive) {
                delay(intervalMs)
                reevaluate()
            }
        }
}
