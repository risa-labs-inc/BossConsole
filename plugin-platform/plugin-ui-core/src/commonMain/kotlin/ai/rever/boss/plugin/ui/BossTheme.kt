package ai.rever.boss.plugin.ui

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

/**
 * Shared UI theme constants for BOSS plugin panels.
 *
 * Provides consistent styling across all plugin panels, aligned with the main
 * BOSS application design language. Values resolve through the active
 * [BossThemeController] theme, so panels re-skin live when the user switches
 * themes. (Getters, not stored vals — keeps reads reactive and preserves the
 * existing JVM signatures for dynamic plugins.)
 */
object BossThemeColors {
    // Background colors
    val SurfaceColor: Color get() = BossColors.darkBackground // Card/sidebar background
    val BackgroundColor: Color get() = BossColors.darkContentBackground // Content area background
    val BorderColor: Color get() = BossColors.darkBorder // Border/divider color

    // Text colors
    val TextPrimary: Color get() = BossColors.darkTextPrimary // Primary text color
    val TextSecondary: Color get() = BossColors.darkTextSecondary // Secondary text color
    val TextMuted: Color get() = BossColors.darkTextMuted // Muted text color

    // Accent colors
    val AccentColor: Color get() = BossColors.darkAccent // Selection/highlight color
    val SecondaryColor: Color get() = BossColors.darkSecondary // Secondary accent

    // Status colors
    val ErrorColor: Color get() = BossColors.darkError // Error states
    val SuccessColor: Color get() = BossColors.darkSuccess // Success states
    val WarningColor: Color get() = BossColors.darkWarning // Warning states
}

/**
 * BOSS application theme.
 *
 * Resolves through [BossThemeController]'s active theme — one of [BossThemes.all],
 * Blueprint (dark) by default — selected explicitly by the user in Settings and
 * persisted; the OS theme setting is not consulted.
 *
 * @param content The content to be styled with this theme
 */
@Composable
fun BossTheme(content: @Composable () -> Unit) {
    BossTheme(typography = bossTypography(), content = content)
}

/**
 * Theme overload that accepts an explicit [typography].
 *
 * IMPORTANT: this is a separate overload, NOT a defaulted parameter on the
 * single-argument [BossTheme] above. Dynamic plugins are compiled against the
 * `BossTheme(content)` JVM signature; collapsing this into
 * `BossTheme(typography, content)` with a default would change that method's
 * descriptor and break plugin binary compatibility — the loader rejects the
 * plugin (observed with boss-plugin-fluck-browser). composeApp's BossTheme
 * re-export calls this overload with a MesloLGS-backed typography.
 *
 * @param typography Type scale to publish to [LocalBossTypography].
 * @param content The content to be styled with this theme
 */
@Composable
fun BossTheme(
    typography: BossTypography,
    content: @Composable () -> Unit,
) {
    // Resolve the active host theme reactively — switching it (via the settings
    // UI / BossThemeController) re-skins the whole subtree.
    val theme = BossThemeController.current

    // Compose's default ScrollbarStyle paints the thumb as Color.Black at 12% alpha
    // unhovered, which is invisible against BOSS's dark panels — a scrollbar that
    // communicates neither "there is more below" nor a visible drag target.
    // Deriving it here means every VerticalScrollbar/HorizontalScrollbar call site
    // under this root — host and plugin panels alike, since plugin-ui-core is what
    // plugins theme against — inherits a visible thumb with no per-call-site opt-in.
    // CrashReportDialog keeps its own copy of this fix separately: it renders through
    // its own ComposePanel().setContent root with no BossTheme ancestor, so this
    // provider never reaches it.
    //
    // textMuted at 45% alpha (the first cut of this fix) measured 1.01-1.32:1 against
    // panel across the six themes - below the 3:1 UI-component contrast floor
    // everywhere. textSecondary at full alpha is what PanelScrollbarConfig already
    // uses for its own scrollbar thumb, and clears 3:1 in all six (4.97:1 worst
    // case); textPrimary for the hover state keeps it one step brighter without
    // introducing a third color the design system doesn't already use for this.
    val defaultScrollbarStyle = LocalScrollbarStyle.current
    val scrollbarStyle =
        remember(defaultScrollbarStyle, theme.colors.textSecondary, theme.colors.textPrimary) {
            defaultScrollbarStyle.copy(
                unhoverColor = theme.colors.textSecondary,
                hoverColor = theme.colors.textPrimary,
            )
        }

    MaterialTheme(
        colors = theme.material,
    ) {
        // Provide the BOSS design-system tokens so components can read
        // `BossTheme.colors.signal`, `BossTheme.space.md`, etc.
        CompositionLocalProvider(
            LocalBossColors provides theme.colors,
            LocalBossSpacing provides BossSpacing(),
            LocalBossRadii provides BossRadii(),
            LocalBossElevation provides BossElevation(),
            LocalBossTypography provides typography,
            LocalScrollbarStyle provides scrollbarStyle,
        ) {
            content()
        }
    }
}
