@file:Suppress("UNUSED")

/**
 * Re-exports from plugin-ui-core module for backward compatibility.
 * New code should import directly from ai.rever.boss.plugin.ui
 */

import ai.rever.boss.plugin.ui.bossTypography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import boss_kotlin.composeapp.generated.resources.Res
import boss_kotlin.composeapp.generated.resources.meslolgs_nf_bold
import boss_kotlin.composeapp.generated.resources.meslolgs_nf_bold_italic
import boss_kotlin.composeapp.generated.resources.meslolgs_nf_italic
import boss_kotlin.composeapp.generated.resources.meslolgs_nf_regular
import org.jetbrains.compose.resources.Font
import ai.rever.boss.plugin.ui.BossTheme as PluginBossTheme

/**
 * BOSS theme for the host app.
 *
 * Builds the design system's mono brand voice from the bundled MesloLGS Nerd
 * Font and injects it into [PluginBossTheme], so `BossTheme.type.*` (and any
 * component that reads it) renders in the real face instead of the generic
 * platform monospace fallback. All host theme roots route through here.
 */
@Composable
fun BossTheme(content: @Composable () -> Unit) {
    val regular = Font(Res.font.meslolgs_nf_regular, FontWeight.Normal, FontStyle.Normal)
    val bold = Font(Res.font.meslolgs_nf_bold, FontWeight.Bold, FontStyle.Normal)
    val italic = Font(Res.font.meslolgs_nf_italic, FontWeight.Normal, FontStyle.Italic)
    val boldItalic = Font(Res.font.meslolgs_nf_bold_italic, FontWeight.Bold, FontStyle.Italic)
    // Remember the typography: a fresh instance per recomposition changes the
    // LocalBossTypography value, and a static composition local recomposes the
    // entire subtree below the theme root whenever its value changes.
    //
    // The keys ARE stable across recompositions: Res.font.* accessors are
    // generated `by lazy` (one FontResource instance), and the desktop Font()
    // composable loads blocking and remembers its result keyed on it. Keep the
    // keys (don't go keyless): Font() legitimately returns a new instance when
    // the resource environment changes, and recomputing then is the point.
    val typography =
        remember(regular, bold, italic, boldItalic) {
            bossTypography(mono = FontFamily(regular, bold, italic, boldItalic))
        }
    PluginBossTheme(
        typography = typography,
        content = content,
    )
}

// Re-export color values for backward compatibility.
// Imported as top-level so existing code using "import BossDarkAccent" still works.
// Getters (not stored vals) so they stay reactive to the active theme.
//
// The `Dark` in these names is historical and now MISLEADING: they resolve
// through the active theme, which may be Daylight (light). Deprecated so the
// names age out — new code should read the semantic tokens instead.
private const val DARK_ALIAS_DEPRECATION =
    "Misleading name: resolves to the ACTIVE theme (which may be light), not a dark value. " +
        "Use BossTheme.colors.<token> in composables, or BossThemeController.current.colors.<token> elsewhere."

@Deprecated(DARK_ALIAS_DEPRECATION, ReplaceWith("BossThemeController.current.colors.panel", "ai.rever.boss.plugin.ui.BossThemeController"))
val BossDarkBackground get() = ai.rever.boss.plugin.ui.BossColors.darkBackground

@Deprecated(DARK_ALIAS_DEPRECATION, ReplaceWith("BossThemeController.current.colors.raised", "ai.rever.boss.plugin.ui.BossThemeController"))
val BossDarkSurface get() = ai.rever.boss.plugin.ui.BossColors.darkSurface

@Deprecated(DARK_ALIAS_DEPRECATION, ReplaceWith("BossThemeController.current.colors.ink", "ai.rever.boss.plugin.ui.BossThemeController"))
val BossDarkContentBackground get() = ai.rever.boss.plugin.ui.BossColors.darkContentBackground

@Deprecated(DARK_ALIAS_DEPRECATION, ReplaceWith("BossThemeController.current.colors.line", "ai.rever.boss.plugin.ui.BossThemeController"))
val BossDarkBorder get() = ai.rever.boss.plugin.ui.BossColors.darkBorder

@Deprecated(
    DARK_ALIAS_DEPRECATION,
    ReplaceWith("BossThemeController.current.colors.textPrimary", "ai.rever.boss.plugin.ui.BossThemeController"),
)
val BossDarkTextPrimary get() = ai.rever.boss.plugin.ui.BossColors.darkTextPrimary

@Deprecated(
    DARK_ALIAS_DEPRECATION,
    ReplaceWith("BossThemeController.current.colors.textSecondary", "ai.rever.boss.plugin.ui.BossThemeController"),
)
val BossDarkTextSecondary get() = ai.rever.boss.plugin.ui.BossColors.darkTextSecondary

@Deprecated(
    DARK_ALIAS_DEPRECATION,
    ReplaceWith("BossThemeController.current.colors.textMuted", "ai.rever.boss.plugin.ui.BossThemeController"),
)
val BossDarkTextMuted get() = ai.rever.boss.plugin.ui.BossColors.darkTextMuted

@Deprecated(DARK_ALIAS_DEPRECATION, ReplaceWith("BossThemeController.current.colors.signal", "ai.rever.boss.plugin.ui.BossThemeController"))
val BossDarkAccent get() = ai.rever.boss.plugin.ui.BossColors.darkAccent

@Deprecated(DARK_ALIAS_DEPRECATION, ReplaceWith("BossThemeController.current.colors.data", "ai.rever.boss.plugin.ui.BossThemeController"))
val BossDarkSecondary get() = ai.rever.boss.plugin.ui.BossColors.darkSecondary

@Deprecated(DARK_ALIAS_DEPRECATION, ReplaceWith("BossThemeController.current.colors.alert", "ai.rever.boss.plugin.ui.BossThemeController"))
val BossDarkError get() = ai.rever.boss.plugin.ui.BossColors.darkError

@Deprecated(DARK_ALIAS_DEPRECATION, ReplaceWith("BossThemeController.current.colors.ok", "ai.rever.boss.plugin.ui.BossThemeController"))
val BossDarkSuccess get() = ai.rever.boss.plugin.ui.BossColors.darkSuccess

@Deprecated(DARK_ALIAS_DEPRECATION, ReplaceWith("BossThemeController.current.colors.warn", "ai.rever.boss.plugin.ui.BossThemeController"))
val BossDarkWarning get() = ai.rever.boss.plugin.ui.BossColors.darkWarning

// Not deprecated: these names carry no dark/light claim.
val ContextMenuBackground get() = ai.rever.boss.plugin.ui.ContextMenuBackground
val ContextMenuBorder get() = ai.rever.boss.plugin.ui.ContextMenuBorder
val ContextMenuHover get() = ai.rever.boss.plugin.ui.ContextMenuHover
