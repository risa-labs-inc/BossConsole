package ai.rever.boss.plugin.ui

import androidx.compose.material.Colors
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Selectable BOSS host themes — the app-level counterpart to BossTerm's themes.
 *
 * - **Operator** — the signature amber-on-ink dark identity (the default).
 * - **Daylight** — a clean light theme.
 * - **Clean** — a neutral charcoal theme with a calm steel-blue accent.
 *
 * The active theme is held reactively in [BossThemeController]; [BossTheme] and
 * the legacy [BossColors] both read it, so selecting a theme re-skins the whole
 * app live. Persisting/restoring the choice is the host's responsibility (the
 * settings UI calls [BossThemeController.select]).
 */
data class BossAppTheme(
    val id: String,
    val name: String,
    /** Description shown under the name in the picker. */
    val blurb: String,
    val isLight: Boolean,
    val colors: BossColorScheme,
    val material: Colors,
)

/** Clean light theme. */
val BossLightColorScheme =
    BossColorScheme(
        ink = Color(0xFFF5F7FA),
        panel = Color(0xFFFFFFFF),
        raised = Color(0xFFFFFFFF),
        line = Color(0xFFE2E7EE),
        lineStrong = Color(0xFFC9D2DC),
        textPrimary = Color(0xFF131820),
        textSecondary = Color(0xFF5A6675),
        textMuted = Color(0xFF94A0AE),
        signal = Color(0xFFD9871A),
        signalDim = Color(0xFFB36F12),
        signalWash = Color(0xFFFBEFD8),
        data = Color(0xFF1E7FA8),
        ok = Color(0xFF2F9E54),
        warn = Color(0xFFC5860C),
        alert = Color(0xFFD2453B),
        onSignal = Color(0xFF2A1B05),
        onData = Color(0xFFFFFFFF),
    )

/** Neutral charcoal theme with a restrained steel-blue accent. */
val BossCleanColorScheme =
    BossColorScheme(
        ink = Color(0xFF15171A),
        panel = Color(0xFF1C1F23),
        raised = Color(0xFF24282D),
        line = Color(0xFF2E333A),
        lineStrong = Color(0xFF424954),
        textPrimary = Color(0xFFEDEFF2),
        textSecondary = Color(0xFF9BA3AD),
        textMuted = Color(0xFF6A727C),
        signal = Color(0xFF6E94C4),
        signalDim = Color(0xFF5A7DAB),
        signalWash = Color(0xFF1B2430),
        data = Color(0xFF58B0A8),
        ok = Color(0xFF6FB58A),
        warn = Color(0xFFD8B66A),
        alert = Color(0xFFD9776E),
        onSignal = Color(0xFF0C1420),
        onData = Color(0xFF04201E),
    )

private fun darkMaterial(s: BossColorScheme): Colors =
    darkColors(
        primary = s.signal,
        primaryVariant = s.signalDim,
        secondary = s.data,
        secondaryVariant = s.data,
        background = s.panel,
        surface = s.raised,
        error = s.alert,
        onPrimary = s.onSignal,
        onSecondary = s.onData,
        onBackground = s.textPrimary,
        onSurface = s.textPrimary,
        onError = s.onSignal,
    )

private fun lightMaterial(s: BossColorScheme): Colors =
    lightColors(
        primary = s.signal,
        primaryVariant = s.signalDim,
        secondary = s.data,
        secondaryVariant = s.data,
        background = s.panel,
        surface = s.panel,
        error = s.alert,
        onPrimary = s.onSignal,
        onSecondary = s.onData,
        onBackground = s.textPrimary,
        onSurface = s.textPrimary,
        onError = Color.White,
    )

object BossThemes {
    const val DEFAULT_ID = "operator"

    val OPERATOR =
        BossAppTheme(
            id = "operator",
            name = "Operator",
            blurb = "Amber signal on ink — the default",
            isLight = false,
            colors = BossDarkColorScheme,
            material = darkMaterial(BossDarkColorScheme),
        )
    val DAYLIGHT =
        BossAppTheme(
            id = "daylight",
            name = "Daylight",
            blurb = "Clean light theme",
            isLight = true,
            colors = BossLightColorScheme,
            material = lightMaterial(BossLightColorScheme),
        )
    val CLEAN =
        BossAppTheme(
            id = "clean",
            name = "Clean",
            blurb = "Neutral charcoal, steel-blue accent",
            isLight = false,
            colors = BossCleanColorScheme,
            material = darkMaterial(BossCleanColorScheme),
        )

    /** All selectable themes, in display order. */
    val all: List<BossAppTheme> = listOf(OPERATOR, DAYLIGHT, CLEAN)

    fun byId(id: String?): BossAppTheme = all.find { it.id == id } ?: OPERATOR
}

/**
 * Reactive holder for the active host theme. Reads register for Compose
 * recomposition, so changing [currentId] re-skins everything that reads
 * [BossTheme.colors] or [BossColors].
 */
object BossThemeController {
    var currentId: String by mutableStateOf(BossThemes.DEFAULT_ID)
        private set

    val current: BossAppTheme get() = BossThemes.byId(currentId)

    /** Select a theme by id (no-op if unknown). The host persists the choice. */
    fun select(id: String) {
        if (BossThemes.all.any { it.id == id }) currentId = id
    }
}
