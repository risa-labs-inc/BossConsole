package ai.rever.boss.theme

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** Only the main window has a native backdrop. Keep unsupported/disabled glass opaque. */
internal val sidebarGlassEnabled: Boolean
    @Composable get() = LocalWindowGlass.current.let { it.installed && it.coverage in setOf("sidebar", "window") }

/** BossTerm's neutral glass wash for favorite tiles and hover targets. */
@Composable
internal fun sidebarTileFill(hovered: Boolean): Color {
    val colors = BossTheme.colors
    return if (sidebarGlassEnabled) {
        colors.textPrimary.copy(alpha = if (hovered) 0.14f else 0.06f)
    } else if (hovered) {
        colors.signalWash
    } else {
        colors.raised
    }
}

/** A stable selection base keeps a light sidebar readable over a bright desktop. */
@Composable
internal fun sidebarSelectionFill(): Color {
    val colors = BossTheme.colors
    return if (colors.ink.luminance() < 0.5f) {
        lerp(colors.signalWash, colors.textPrimary, 0.22f)
    } else {
        colors.signalText.copy(alpha = 0.16f).compositeOver(colors.signalWash)
    }
}

@Composable
internal fun sidebarSelectionText(): Color {
    val colors = BossTheme.colors
    return if (sidebarGlassEnabled) colors.textPrimary else colors.onSignal
}

@Composable
internal fun sidebarDividerColor(): Color =
    if (sidebarGlassEnabled) BossTheme.colors.textPrimary.copy(alpha = 0.14f) else BossTheme.colors.line
