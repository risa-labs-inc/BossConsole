package ai.rever.boss.components.buttons

/**
 * Hover hints for the three actions that appear in both the top bar and the focus-mode cluster.
 *
 * `BossTopRightBar` and `FocusModeQuickActions` are the same three buttons rendered in two places,
 * so the strings are shared rather than copied. The search hint is the reason this exists: it names
 * a keyboard shortcut, and a duplicated shortcut is wrong in one of the two places from the day it
 * changes, silently and only for whoever is reading the copy nobody updated.
 *
 * A leaf next to `BossActionButton` so both callers can reach it without either package depending
 * on the other.
 */
internal object QuickActionHints {
    const val SETTINGS = "Configure application settings"
    const val SEARCH = "Search files, tabs, bookmarks (⇧⇧)"
    const val SIGN_OUT = "Sign out of your account"
}
