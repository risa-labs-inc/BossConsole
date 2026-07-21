@file:Suppress("UNUSED")

/**
 * Re-exports from plugin-ui-core module for backward compatibility.
 * New code should import directly from ai.rever.boss.plugin.ui
 */

import ai.rever.boss.plugin.ui.BossTheme as PluginBossTheme
import ai.rever.boss.plugin.ui.bossTypography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.Font
import boss_kotlin.composeapp.generated.resources.Res
import boss_kotlin.composeapp.generated.resources.meslolgs_nf_regular
import boss_kotlin.composeapp.generated.resources.meslolgs_nf_bold
import boss_kotlin.composeapp.generated.resources.meslolgs_nf_italic
import boss_kotlin.composeapp.generated.resources.meslolgs_nf_bold_italic

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
    val typography = remember(regular, bold, italic, boldItalic) {
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
val BossDarkBackground get() = ai.rever.boss.plugin.ui.BossDarkBackground
val BossDarkSurface get() = ai.rever.boss.plugin.ui.BossDarkSurface
val BossDarkContentBackground get() = ai.rever.boss.plugin.ui.BossDarkContentBackground
val BossDarkBorder get() = ai.rever.boss.plugin.ui.BossDarkBorder
val BossDarkTextPrimary get() = ai.rever.boss.plugin.ui.BossDarkTextPrimary
val BossDarkTextSecondary get() = ai.rever.boss.plugin.ui.BossDarkTextSecondary
val BossDarkTextMuted get() = ai.rever.boss.plugin.ui.BossDarkTextMuted
val BossDarkAccent get() = ai.rever.boss.plugin.ui.BossDarkAccent
val BossDarkSecondary get() = ai.rever.boss.plugin.ui.BossDarkSecondary
val BossDarkError get() = ai.rever.boss.plugin.ui.BossDarkError
val BossDarkSuccess get() = ai.rever.boss.plugin.ui.BossDarkSuccess
val BossDarkWarning get() = ai.rever.boss.plugin.ui.BossDarkWarning
val ContextMenuBackground get() = ai.rever.boss.plugin.ui.ContextMenuBackground
val ContextMenuBorder get() = ai.rever.boss.plugin.ui.ContextMenuBorder
val ContextMenuHover get() = ai.rever.boss.plugin.ui.ContextMenuHover
