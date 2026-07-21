package ai.rever.boss.keymap.lifecycle

/**
 * Interface for defining lifecycle conditions for keyboard shortcuts.
 * Implementations determine when a shortcut should be enabled or disabled.
 *
 * Example use cases:
 * - "Close Tab" only enabled when tabs exist
 * - "Paste" only enabled when clipboard has content
 * - "Split Vertical" only enabled when a panel is active
 */
interface ShortcutLifecycleCondition {
    /**
     * Checks if the shortcut should currently be enabled.
     * This is called before executing a shortcut action.
     *
     * @return true if the shortcut should be enabled, false otherwise
     */
    suspend fun isEnabled(): Boolean

    /**
     * Human-readable reason for why the shortcut is disabled.
     * Displayed in UI tooltips when shortcut is disabled.
     *
     * Examples:
     * - "No tabs open"
     * - "Clipboard is empty"
     * - "No active panel"
     */
    val disabledReason: String

    /**
     * Optional: Describes when this condition is enabled.
     * Displayed in UI help/documentation.
     *
     * Examples:
     * - "When tabs are open"
     * - "When clipboard has content"
     */
    val enabledDescription: String
        get() = "Always"
}

/**
 * A lifecycle condition that is always enabled.
 * Used as default for shortcuts without specific conditions.
 */
object AlwaysEnabledCondition : ShortcutLifecycleCondition {
    override suspend fun isEnabled(): Boolean = true

    override val disabledReason: String = "Always enabled"

    override val enabledDescription: String = "Always enabled"
}

/**
 * A lifecycle condition that is never enabled.
 * Used for disabled or deprecated shortcuts.
 */
class NeverEnabledCondition(
    override val disabledReason: String = "Shortcut disabled"
) : ShortcutLifecycleCondition {
    override suspend fun isEnabled(): Boolean = false

    override val enabledDescription: String = "Never"
}

/**
 * Combines multiple conditions with AND logic.
 * All conditions must be enabled for the result to be enabled.
 */
class AndCondition(
    private val conditions: List<ShortcutLifecycleCondition>
) : ShortcutLifecycleCondition {
    override suspend fun isEnabled(): Boolean {
        return conditions.all { it.isEnabled() }
    }

    override val disabledReason: String
        get() = "Multiple conditions not met"

    override val enabledDescription: String
        get() = conditions.joinToString(" and ") { it.enabledDescription }
}

/**
 * Combines multiple conditions with OR logic.
 * At least one condition must be enabled for the result to be enabled.
 */
class OrCondition(
    private val conditions: List<ShortcutLifecycleCondition>
) : ShortcutLifecycleCondition {
    override suspend fun isEnabled(): Boolean {
        return conditions.any { it.isEnabled() }
    }

    override val disabledReason: String
        get() = "All conditions disabled: " + conditions.joinToString(", ") { it.disabledReason }

    override val enabledDescription: String
        get() = conditions.joinToString(" or ") { it.enabledDescription }
}
