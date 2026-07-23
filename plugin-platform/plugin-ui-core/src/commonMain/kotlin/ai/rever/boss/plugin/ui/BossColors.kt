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
//
// The `Dark` in these names is historical and now MISLEADING: they resolve
// through the active theme, which may be Daylight (light). They are deprecated
// so the names age out — new code should read the semantic tokens instead
// (`BossTheme.colors.<token>` in composables, or
// `BossThemeController.current.colors.<token>` anywhere else).
private const val DARK_ALIAS_DEPRECATION =
    "Misleading name: resolves to the ACTIVE theme (which may be light), not a dark value. " +
    "Use BossTheme.colors.<token> in composables, or BossThemeController.current.colors.<token> elsewhere."

@Deprecated(DARK_ALIAS_DEPRECATION, ReplaceWith("BossThemeController.current.colors.panel", "ai.rever.boss.plugin.ui.BossThemeController"))
val BossDarkBackground: Color get() = BossColors.darkBackground
@Deprecated(DARK_ALIAS_DEPRECATION, ReplaceWith("BossThemeController.current.colors.raised", "ai.rever.boss.plugin.ui.BossThemeController"))
val BossDarkSurface: Color get() = BossColors.darkSurface
@Deprecated(DARK_ALIAS_DEPRECATION, ReplaceWith("BossThemeController.current.colors.ink", "ai.rever.boss.plugin.ui.BossThemeController"))
val BossDarkContentBackground: Color get() = BossColors.darkContentBackground
@Deprecated(DARK_ALIAS_DEPRECATION, ReplaceWith("BossThemeController.current.colors.line", "ai.rever.boss.plugin.ui.BossThemeController"))
val BossDarkBorder: Color get() = BossColors.darkBorder
@Deprecated(DARK_ALIAS_DEPRECATION, ReplaceWith("BossThemeController.current.colors.textPrimary", "ai.rever.boss.plugin.ui.BossThemeController"))
val BossDarkTextPrimary: Color get() = BossColors.darkTextPrimary
@Deprecated(DARK_ALIAS_DEPRECATION, ReplaceWith("BossThemeController.current.colors.textSecondary", "ai.rever.boss.plugin.ui.BossThemeController"))
val BossDarkTextSecondary: Color get() = BossColors.darkTextSecondary
@Deprecated(DARK_ALIAS_DEPRECATION, ReplaceWith("BossThemeController.current.colors.textMuted", "ai.rever.boss.plugin.ui.BossThemeController"))
val BossDarkTextMuted: Color get() = BossColors.darkTextMuted
@Deprecated(DARK_ALIAS_DEPRECATION, ReplaceWith("BossThemeController.current.colors.signal", "ai.rever.boss.plugin.ui.BossThemeController"))
val BossDarkAccent: Color get() = BossColors.darkAccent
@Deprecated(DARK_ALIAS_DEPRECATION, ReplaceWith("BossThemeController.current.colors.data", "ai.rever.boss.plugin.ui.BossThemeController"))
val BossDarkSecondary: Color get() = BossColors.darkSecondary
@Deprecated(DARK_ALIAS_DEPRECATION, ReplaceWith("BossThemeController.current.colors.alert", "ai.rever.boss.plugin.ui.BossThemeController"))
val BossDarkError: Color get() = BossColors.darkError
@Deprecated(DARK_ALIAS_DEPRECATION, ReplaceWith("BossThemeController.current.colors.ok", "ai.rever.boss.plugin.ui.BossThemeController"))
val BossDarkSuccess: Color get() = BossColors.darkSuccess
@Deprecated(DARK_ALIAS_DEPRECATION, ReplaceWith("BossThemeController.current.colors.warn", "ai.rever.boss.plugin.ui.BossThemeController"))
val BossDarkWarning: Color get() = BossColors.darkWarning

// Not deprecated: these names carry no dark/light claim.
val ContextMenuBackground: Color get() = BossColors.contextMenuBackground
val ContextMenuBorder: Color get() = BossColors.contextMenuBorder
val ContextMenuHover: Color get() = BossColors.contextMenuHover
