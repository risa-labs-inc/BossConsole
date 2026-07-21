package ai.rever.boss.keymap.lifecycle.conditions

import ai.rever.boss.keymap.lifecycle.ShortcutLifecycleCondition

/**
 * Lifecycle condition that checks if panels/splits are available.
 * Used for shortcuts like "Split Vertical", "Split Horizontal", "Navigate Splits".
 *
 * @param hasPanels Lambda that returns true if panels/splits exist
 */
class PanelCondition(
    private val hasPanels: () -> Boolean
) : ShortcutLifecycleCondition {

    override suspend fun isEnabled(): Boolean {
        return hasPanels()
    }

    override val disabledReason: String
        get() = "No active panels"

    override val enabledDescription: String
        get() = "When panels are active"
}

/**
 * Lifecycle condition that checks if split creation is possible.
 * May be disabled if maximum splits reached or no content to split.
 *
 * @param getSplitCount Lambda that returns the current number of splits
 * @param maxSplits Maximum number of allowed splits (default: 4)
 */
class CanCreateSplitCondition(
    private val getSplitCount: () -> Int,
    private val maxSplits: Int = 4
) : ShortcutLifecycleCondition {

    override suspend fun isEnabled(): Boolean {
        return getSplitCount() < maxSplits
    }

    override val disabledReason: String
        get() = "Maximum splits reached ($maxSplits)"

    override val enabledDescription: String
        get() = "When under max splits ($maxSplits)"
}

/**
 * Lifecycle condition that checks if there are multiple splits for navigation.
 * Used for "Navigate to Next/Previous Split" shortcuts.
 *
 * @param getSplitCount Lambda that returns the current number of splits
 */
class SplitNavigationCondition(
    private val getSplitCount: () -> Int
) : ShortcutLifecycleCondition {

    override suspend fun isEnabled(): Boolean {
        return getSplitCount() >= 2
    }

    override val disabledReason: String
        get() = "Need multiple splits to navigate"

    override val enabledDescription: String
        get() = "When 2 or more splits exist"
}
