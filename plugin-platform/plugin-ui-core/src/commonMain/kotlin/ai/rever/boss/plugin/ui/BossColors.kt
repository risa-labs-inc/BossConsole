package ai.rever.boss.plugin.ui

import androidx.compose.ui.graphics.Color

/**
 * BOSS application color palette — resolves through the **active host theme**.
 *
 * Property names are stable, but the values now read from
 * [BossThemeController]'s current [BossColorScheme]. They are getters (not
 * stored vals) so reads are reactive: switching the theme in settings re-skins
 * every component that references these names. New code should prefer the
 * semantic accessors on [BossTheme] (e.g. `BossTheme.colors.signal`).
 * See [BossDesignSystem] and [BossThemes] for the full system.
 */
object BossColors {
    private val c: BossColorScheme get() = BossThemeController.current.colors

    // Background colors
    val darkBackground: Color get() = c.panel            // chrome / card / sidebar
    val darkSurface: Color get() = c.raised              // raised surface
    val darkContentBackground: Color get() = c.ink       // content floor (host + terminal)
    val darkBorder: Color get() = c.line                 // hairline border / divider

    // Text colors
    val darkTextPrimary: Color get() = c.textPrimary
    val darkTextSecondary: Color get() = c.textSecondary
    val darkTextMuted: Color get() = c.textMuted

    // Accent colors
    val darkAccent: Color get() = c.signal               // primary / live / active
    val darkSecondary: Color get() = c.data              // links / data

    // Status colors
    val darkError: Color get() = c.alert
    val darkSuccess: Color get() = c.ok
    val darkWarning: Color get() = c.warn

    // Context menu colors
    val contextMenuBackground: Color get() = c.raised
    val contextMenuBorder: Color get() = c.lineStrong
    val contextMenuHover: Color get() = c.signalWash
}

// Convenience aliases for backward compatibility (also reactive — getters, so
// `import BossDarkAccent` callers follow theme switches too).
val BossDarkBackground: Color get() = BossColors.darkBackground
val BossDarkSurface: Color get() = BossColors.darkSurface
val BossDarkContentBackground: Color get() = BossColors.darkContentBackground
val BossDarkBorder: Color get() = BossColors.darkBorder
val BossDarkTextPrimary: Color get() = BossColors.darkTextPrimary
val BossDarkTextSecondary: Color get() = BossColors.darkTextSecondary
val BossDarkTextMuted: Color get() = BossColors.darkTextMuted
val BossDarkAccent: Color get() = BossColors.darkAccent
val BossDarkSecondary: Color get() = BossColors.darkSecondary
val BossDarkError: Color get() = BossColors.darkError
val BossDarkSuccess: Color get() = BossColors.darkSuccess
val BossDarkWarning: Color get() = BossColors.darkWarning
val ContextMenuBackground: Color get() = BossColors.contextMenuBackground
val ContextMenuBorder: Color get() = BossColors.contextMenuBorder
val ContextMenuHover: Color get() = BossColors.contextMenuHover
