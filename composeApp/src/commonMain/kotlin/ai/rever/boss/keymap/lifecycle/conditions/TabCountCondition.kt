package ai.rever.boss.keymap.lifecycle.conditions

import ai.rever.boss.keymap.lifecycle.ShortcutLifecycleCondition

/**
 * Lifecycle condition that checks if tabs exist in a window.
 * Used for shortcuts like "Close Tab", "Next Tab", "Previous Tab".
 *
 * @param getTabCount Lambda that returns the current tab count
 * @param minTabCount Minimum number of tabs required (default: 1)
 */
open class TabCountCondition(
    private val getTabCount: () -> Int,
    private val minTabCount: Int = 1
) : ShortcutLifecycleCondition {

    override suspend fun isEnabled(): Boolean {
        return getTabCount() >= minTabCount
    }

    override val disabledReason: String
        get() = when (minTabCount) {
            1 -> "No tabs open"
            else -> "Need at least $minTabCount tabs"
        }

    override val enabledDescription: String
        get() = when (minTabCount) {
            1 -> "When tabs are open"
            else -> "When at least $minTabCount tabs exist"
        }
}

/**
 * Lifecycle condition that checks if there are enough tabs for navigation.
 * Specifically for Next/Previous Tab shortcuts (requires 2+ tabs).
 *
 * @param getTabCount Lambda that returns the current tab count
 */
class TabNavigationCondition(
    getTabCount: () -> Int
) : TabCountCondition(getTabCount, minTabCount = 2) {

    override val disabledReason: String
        get() = "Need multiple tabs to navigate"

    override val enabledDescription: String
        get() = "When 2 or more tabs exist"
}

/**
 * Lifecycle condition that checks if there's an active tab.
 * Used for shortcuts that operate on the current tab.
 *
 * @param hasActiveTab Lambda that returns true if there's an active tab
 */
class ActiveTabCondition(
    private val hasActiveTab: () -> Boolean
) : ShortcutLifecycleCondition {

    override suspend fun isEnabled(): Boolean {
        return hasActiveTab()
    }

    override val disabledReason: String
        get() = "No active tab"

    override val enabledDescription: String
        get() = "When a tab is active"
}
