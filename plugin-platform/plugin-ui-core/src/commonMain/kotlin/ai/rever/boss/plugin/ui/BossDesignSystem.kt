package ai.rever.boss.plugin.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * BOSS Design System — "Operator's Console".
 *
 * One visual language shared by the BossConsole host and the BossTerm surface.
 * Two ideas drive every token here:
 *
 *  1. The terminal character cell is the atomic grid unit; chrome borrows the
 *     terminal's discipline (8.dp base, hairline borders, high density).
 *  2. One amber [BossPalette.signal] means "live / active / now" — the primary
 *     action, the focused field, the selected tab, the cursor. Cyan
 *     [BossPalette.data] carries links and data. Everything else stays quiet.
 *
 * Access tokens inside a [BossTheme] via the [BossTheme] accessor object, e.g.
 * `BossTheme.colors.signal`, `BossTheme.space.md`, `BossTheme.radius.button`.
 * Raw constants live in [BossPalette] for use outside composition.
 */

// ---------------------------------------------------------------------------
// Raw palette — the single source of truth for color values.
// ---------------------------------------------------------------------------

object BossPalette {
    // Surface — host AND terminal share `ink` as the floor.
    val ink = Color(0xFF0E1217) // base floor
    val panel = Color(0xFF161D26) // chrome / raised surface
    val raised = Color(0xFF1E2731) // menus, popovers, hover
    val line = Color(0xFF2A3744) // hairline border / divider
    val lineStrong = Color(0xFF3A4B5C) // input edge / strong border

    // Ink on surface.
    val chalk = Color(0xFFE9EEF3) // primary text
    val mist = Color(0xFF8593A3) // secondary text
    val muted = Color(0xFF5C6977) // tertiary / disabled

    // Signals.
    val signal = Color(0xFFF2A93B) // amber — live / active / primary action
    val signalDim = Color(0xFFC98A2E) // pressed / variant
    val signalWash = Color(0xFF2A2113) // amber at home on ink (hover fill)
    val data = Color(0xFF56C7E0) // cyan — links / info / data

    // Semantic.
    val ok = Color(0xFF6FD08C)
    val warn = Color(0xFFF0B429)
    val alert = Color(0xFFF2685F)

    // Ink that sits ON a signal fill (e.g. text on an amber button).
    val onSignal = Color(0xFF1A1206)
    val onData = Color(0xFF06222A)
}

// ---------------------------------------------------------------------------
// Semantic color scheme — what components should reference.
// ---------------------------------------------------------------------------

data class BossColorScheme(
    val ink: Color,
    val panel: Color,
    val raised: Color,
    val line: Color,
    val lineStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val signal: Color,
    val signalDim: Color,
    val signalWash: Color,
    val data: Color,
    val ok: Color,
    val warn: Color,
    val alert: Color,
    val onSignal: Color,
    val onData: Color,
)

val BossDarkColorScheme =
    BossColorScheme(
        ink = BossPalette.ink,
        panel = BossPalette.panel,
        raised = BossPalette.raised,
        line = BossPalette.line,
        lineStrong = BossPalette.lineStrong,
        textPrimary = BossPalette.chalk,
        textSecondary = BossPalette.mist,
        textMuted = BossPalette.muted,
        signal = BossPalette.signal,
        signalDim = BossPalette.signalDim,
        signalWash = BossPalette.signalWash,
        data = BossPalette.data,
        ok = BossPalette.ok,
        warn = BossPalette.warn,
        alert = BossPalette.alert,
        onSignal = BossPalette.onSignal,
        onData = BossPalette.onData,
    )

// ---------------------------------------------------------------------------
// Spacing — 8.dp base with a 4.dp half-step. "cell" mirrors a Meslo char cell.
// ---------------------------------------------------------------------------

data class BossSpacing(
    val hairline: androidx.compose.ui.unit.Dp = 2.dp,
    val xs: androidx.compose.ui.unit.Dp = 4.dp,
    val sm: androidx.compose.ui.unit.Dp = 8.dp, // base unit
    val md: androidx.compose.ui.unit.Dp = 12.dp,
    val lg: androidx.compose.ui.unit.Dp = 16.dp,
    val xl: androidx.compose.ui.unit.Dp = 24.dp,
    val xxl: androidx.compose.ui.unit.Dp = 32.dp,
    /** Terminal character cell metrics at 14.sp MesloLGS — the grid the UI snaps to. */
    val cellWidth: androidx.compose.ui.unit.Dp = 8.4.dp,
    val cellHeight: androidx.compose.ui.unit.Dp = 17.dp,
)

// ---------------------------------------------------------------------------
// Radius / shapes — small radii read as a precision instrument.
// ---------------------------------------------------------------------------

data class BossRadii(
    val grid: androidx.compose.ui.unit.Dp = 0.dp, // terminal, cell grid
    val input: androidx.compose.ui.unit.Dp = 3.dp,
    val button: androidx.compose.ui.unit.Dp = 5.dp,
    val card: androidx.compose.ui.unit.Dp = 5.dp,
    val dialog: androidx.compose.ui.unit.Dp = 8.dp,
) {
    val gridShape get() = RoundedCornerShape(grid)
    val inputShape get() = RoundedCornerShape(input)
    val buttonShape get() = RoundedCornerShape(button)
    val cardShape get() = RoundedCornerShape(card)
    val dialogShape get() = RoundedCornerShape(dialog)
}

// ---------------------------------------------------------------------------
// Elevation — surfaces separate by tint first; shadow only for true popovers.
// ---------------------------------------------------------------------------

data class BossElevation(
    val floor: androidx.compose.ui.unit.Dp = 0.dp, // ink
    val panel: androidx.compose.ui.unit.Dp = 0.dp, // tint + 1px line
    val popover: androidx.compose.ui.unit.Dp = 8.dp, // menus / dialogs
)

// ---------------------------------------------------------------------------
// Motion — durations in ms; easing exposed as control points so this file
// stays free of animation-core imports. Build with CubicBezierEasing(...).
// ---------------------------------------------------------------------------

object BossMotion {
    const val instantMs = 0 // cursor, key echo
    const val fastMs = 90 // hover, press
    const val baseMs = 160 // menus, panels
    const val cursorBlinkMs = 530

    // Standard easing control points: cubic-bezier(0.2, 0.0, 0.0, 1.0)
    const val easeA = 0.2f
    const val easeB = 0.0f
    const val easeC = 0.0f
    const val easeD = 1.0f
}

// ---------------------------------------------------------------------------
// Typography — monospace is the brand voice (display, labels, data); a sans
// carries running UI copy. Pass real families (MesloLGS / Inter) from the host;
// defaults fall back to the platform generic families so this stays portable.
// ---------------------------------------------------------------------------

data class BossTypography(
    val displayLarge: TextStyle,
    val displaySmall: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val data: TextStyle,
    val label: TextStyle,
    val micro: TextStyle,
)

fun bossTypography(
    mono: FontFamily = FontFamily.Monospace,
    sans: FontFamily = FontFamily.Default,
): BossTypography =
    BossTypography(
        displayLarge = TextStyle(fontFamily = mono, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, letterSpacing = (-0.5).sp),
        displaySmall = TextStyle(fontFamily = mono, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
        title = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        body = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 13.sp),
        data = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = 14.sp),
        label = TextStyle(fontFamily = mono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.5.sp),
        micro = TextStyle(fontFamily = mono, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 1.0.sp),
    )

// ---------------------------------------------------------------------------
// CompositionLocals + accessor. Mirrors the MaterialTheme pattern: there is a
// `fun BossTheme(content)` (in BossTheme.kt) AND this `object BossTheme`.
// ---------------------------------------------------------------------------

val LocalBossColors = staticCompositionLocalOf { BossDarkColorScheme }
val LocalBossSpacing = staticCompositionLocalOf { BossSpacing() }
val LocalBossRadii = staticCompositionLocalOf { BossRadii() }
val LocalBossElevation = staticCompositionLocalOf { BossElevation() }
val LocalBossTypography = staticCompositionLocalOf { bossTypography() }

object BossTheme {
    val colors: BossColorScheme
        @Composable @ReadOnlyComposable
        get() = LocalBossColors.current
    val space: BossSpacing
        @Composable @ReadOnlyComposable
        get() = LocalBossSpacing.current
    val radius: BossRadii
        @Composable @ReadOnlyComposable
        get() = LocalBossRadii.current
    val elevation: BossElevation
        @Composable @ReadOnlyComposable
        get() = LocalBossElevation.current
    val type: BossTypography
        @Composable @ReadOnlyComposable
        get() = LocalBossTypography.current
}
