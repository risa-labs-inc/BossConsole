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
 * - **Blueprint** — electric blue on ink, matching bossconsole.ai (the default).
 * - **Blueprint Light** — the paper-and-blue light half of the same identity.
 * - **Operator** — the original amber-on-ink dark identity.
 * - **Daylight** — a clean light theme.
 * - **Clean** — a neutral charcoal theme with a calm steel-blue accent.
 * - **NVIDIA** — NVIDIA green on black, lifted from nvidia.com.
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

/**
 * Blueprint — the bossconsole.ai identity, in the app.
 *
 * Every value below is either lifted verbatim from the site's stylesheet or
 * composited from it (a site alpha over the site's own floor); the comment on
 * each line names the source. The site's hero mocks the app itself
 * (`.console-frame` / `.console-topbar` / `.console-sidebar` / `.agent-strip`),
 * so the chrome ladder is the site's, not an interpretation of it.
 *
 * On [signal]: `--blue` sits at 3.8:1 against [ink] — above the WCAG 3:1 floor
 * for UI components, below a text floor. That is deliberate and it is how the
 * site behaves: emphasis comes from a [signalWash] fill plus a 2.dp indicator,
 * never from a hairline of `signal` alone. Do not "fix" it by brightening
 * `signal`; brighten the wash or thicken the indicator.
 */
val BossBlueprintColorScheme =
    BossColorScheme(
        ink = Color(0xFF05070B), // --ink
        panel = Color(0xFF080B11), // .console-frame background
        raised = Color(0xFF0E141E), // between --ink-2 #0b1019 and panel
        line = Color(0xFF1C2432), // --line-dark #ffffff29 over ink, cooled
        lineStrong = Color(0xFF2E3B4F), // #ffffff4d over ink, cooled
        textPrimary = Color(0xFFE7EDFA), // site #e7edfa
        textSecondary = Color(0xFF9AA7BB), // .eyebrow
        textMuted = Color(0xFF69768B), // .console-topbar
        signal = Color(0xFF0F5BFF), // --blue
        signalDim = Color(0xFF0A45C4), // pressed / variant
        signalWash = Color(0xFF0A1A3C), // .console-sidebar .selected (#0f5bff2e over panel)
        // Coincides with `data` here on purpose: the site never sets --blue as
        // text on ink either, it reaches for this same light-blue family.
        signalText = Color(0xFF88A9FF), // 8.1:1 min vs --blue's 3.5:1
        data = Color(0xFF88A9FF), // .audit-line svg #8af / .approval > span
        ok = Color(0xFF2FD98A),
        warn = Color(0xFFF0B429),
        alert = Color(0xFFFF5D5D),
        onSignal = Color(0xFFFFFFFF), // .button-light { background: --blue; color: #fff }
        onData = Color(0xFF05070B),
    )

/**
 * Blueprint Light — the site's `--paper` half (`.section` / `.subpage`).
 *
 * The same electric blue, re-grounded on paper. [lineStrong] softens the site's
 * full-ink card border (`.install-card { border: 1px solid var(--ink) }`): a
 * hard black edge is a signature on one landing-page card and a wall of noise
 * on every text field in an app.
 */
val BossBlueprintLightColorScheme =
    BossColorScheme(
        ink = Color(0xFFF5F7FB), // --paper
        panel = Color(0xFFFFFFFF), // .feature-card / .install-card
        raised = Color(0xFFFFFFFF),
        line = Color(0xFFDCE2EB), // --line #05070b24 over paper
        lineStrong = Color(0xFFA8B2C2), // softened from the site's full-ink card border
        textPrimary = Color(0xFF05070B), // --ink
        textSecondary = Color(0xFF687081), // --muted
        textMuted = Color(0xFF9AA3B2),
        signal = Color(0xFF0F5BFF), // --blue (.subpage .eyebrow)
        signalDim = Color(0xFF0A45C4),
        signalWash = Color(0xFFDCE7FF), // --blue-soft
        signalText = Color(0xFF0F5BFF), // --blue clears 4.9:1 on paper, no separate value needed
        data = Color(0xFF0C3FBF), // deeper than signal so links stay distinct from the action blue
        ok = Color(0xFF1E9E63),
        warn = Color(0xFFA8710A),
        alert = Color(0xFFD33B4A),
        onSignal = Color(0xFFFFFFFF),
        onData = Color(0xFFFFFFFF),
    )

/**
 * NVIDIA — the green-on-black identity from nvidia.com.
 *
 * Like Blueprint, every value below is either a literal from the site's
 * stylesheet (`clientlib-site.min.css`) or composited from it, and the comment
 * on each line names the source. The surface ladder is the site's own grey
 * ramp; the status colors are its category-tag ramp, because the site has no
 * semantic status system of its own (see [ok] below).
 *
 * On [signal]: `#76b900` needs no [signalText] twin. It is 8.7:1 on black as a
 * glyph *and* 8.7:1 against the black type it carries as a fill, which is
 * exactly how the site uses it in both directions - `.nv-button .btn-content`
 * is a green ground with `color:#000`, and the black footer sets
 * `.page-footer-link-set__links > li > a { color: #76b900 }`. So a hairline of
 * `signal` is legitimate here, the one thing Blueprint's KDoc rules out. What
 * the site never does is put green on white (2.4:1), which is why there is no
 * light half of this theme.
 */
val BossNvidiaColorScheme =
    BossColorScheme(
        // Surfaces: `.theme-dark { background-color: #000 }` and the grey ramp
        // layered over it. The site's floor is genuinely pure black.
        ink = Color(0xFF000000), // .theme-dark / .page-footer-wrapper
        panel = Color(0xFF0C0C0C), // --color-gray-975, the dark card surface
        raised = Color(0xFF1A1A1A), // dark modal body / the homepage's one grey band
        line = Color(0xFF313131), // --color-gray-800, the dark-mode hairline
        lineStrong = Color(0xFF4B4B4B), // --color-gray-700
        // Text: the site runs very high contrast and carries hierarchy in size
        // and weight, not color, so nothing here is dimmed for style.
        textPrimary = Color(0xFFEEEEEE), // .theme-dark { color: #eee }
        textSecondary = Color(0xFFCCCCCC), // --color-gray-200, nav + menu body copy
        textMuted = Color(0xFF999999), // the site's muted-on-dark and disabled grey
        signal = Color(0xFF76B900), // --color-nvidia-green
        signalDim = Color(0xFF549A00), // .nv-nav-cta-primary:hover
        signalWash = Color(0xFF19210B), // --color-nvidia-green at 12% over `panel`
        signalText = Color(0xFF76B900), // = signal; 7.2:1 at its worst surface, no twin needed
        data = Color(0xFF10B1FB), // the site's blue tag chip
        // Not the brand green. The site never uses green as "success" - it has
        // no status system at all, only a category-tag ramp - and with a green
        // `signal` a same-hue success chip reads as a primary action. Teal sits
        // 89 degrees off the brand's 82, so the two stay different words.
        ok = Color(0xFF1DBBA4), // --color-teal-300
        warn = Color(0xFFEF9100), // --color-yellow-300
        alert = Color(0xFFFF8181), // --color-red-300 (red-500 #e52020 is 2.4:1 on black)
        onSignal = Color(0xFF000000), // .nv-button .btn-content { background: #76b900; color: #000 }
        onData = Color(0xFF000000),
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
        signalText = Color(0xFF95580A), // 5.3:1; the amber signal is 2.6:1 on this floor
        data = Color(0xFF1E7FA8),
        ok = Color(0xFF2F9E54),
        // Darkened from #C5860C, which was 2.88:1 on this theme's own near-white
        // floor — under the 3:1 UI-component floor. Amber on white is a hard
        // combination; this is the smallest change that clears it (3.27:1).
        warn = Color(0xFFB87D0A),
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
        signalText = Color(0xFF6E94C4), // = signal; clears 4.7:1
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
    /**
     * The **class** default, and no longer any platform's first run.
     *
     * Since macOS and Linux moved to [UNIX_DEFAULT_ID] and Windows to
     * [WINDOWS_DEFAULT_ID], nothing in [defaultIdFor] returns this. It survives
     * because it still answers two questions neither platform constant does:
     * what a settings file that omits `appThemeId` means (someone who chose
     * Blueprint under the old `encodeDefaults = false` writer), and what
     * [byId] falls back to for an id this build does not know.
     *
     * Which is exactly why it must NOT be re-pointed when a platform default
     * moves: doing so would silently re-skin everyone whose stored choice was
     * written as `{}`, on every platform at once.
     */
    const val DEFAULT_ID = "blueprint"

    /**
     * What macOS and Linux open with.
     *
     * A first run on either now lands on the NVIDIA identity rather than on
     * Blueprint. Only a first run reaches this - see [defaultIdFor] for which
     * absences count as "has never chosen".
     */
    const val UNIX_DEFAULT_ID = "nvidia"

    /**
     * What Windows opens with. Blueprint Light rather than Daylight because
     * Daylight carries a known contrast defect (its amber signal sits at 2.63:1
     * on its own near-white floor, under the 3:1 floor for UI components) that
     * has no business being anyone's out-of-the-box look;
     * `BossThemesRegistryTest` records that debt.
     *
     * Note that Windows and [UNIX_DEFAULT_ID] now differ in **brand**, not just
     * in value, which was not true while both halves were Blueprint. That is
     * deliberate and it is forced: NVIDIA has no light half to reach for.
     * `#76b900` is 2.4:1 on white, and darkening it far enough to clear the 3:1
     * component floor stops it being the brand green - so pairing a light
     * Windows default with the NVIDIA identity is not something a palette can
     * fix. Windows keeps the identity that does have both halves.
     */
    const val WINDOWS_DEFAULT_ID = "blueprint-light"

    val BLUEPRINT =
        BossAppTheme(
            id = "blueprint",
            name = "Blueprint",
            blurb = "Electric blue on ink - the bossconsole.ai look",
            isLight = false,
            colors = BossBlueprintColorScheme,
            material = darkMaterial(BossBlueprintColorScheme),
        )
    val BLUEPRINT_LIGHT =
        BossAppTheme(
            id = "blueprint-light",
            name = "Blueprint Light",
            blurb = "Paper and blue - the light half of the site",
            isLight = true,
            colors = BossBlueprintLightColorScheme,
            material = lightMaterial(BossBlueprintLightColorScheme),
        )
    val OPERATOR =
        BossAppTheme(
            id = "operator",
            name = "Operator",
            blurb = "Amber signal on ink - the original identity",
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
    val NVIDIA =
        BossAppTheme(
            id = "nvidia",
            name = "NVIDIA",
            blurb = "NVIDIA green on black - the nvidia.com look",
            isLight = false,
            colors = BossNvidiaColorScheme,
            material = darkMaterial(BossNvidiaColorScheme),
        )

    val LIQUID_GLASS_DARK =
        BLUEPRINT.copy(
            id = "liquid-glass-dark",
            name = "Liquid Glass Dark",
            blurb = "Blue on dark native glass",
        )
    val LIQUID_GLASS_LIGHT =
        BLUEPRINT_LIGHT.copy(
            id = "liquid-glass-light",
            name = "Liquid Glass Light",
            blurb = "Light native glass with blue accents",
        )

    /** All selectable themes, in display order. */
    val all: List<BossAppTheme> =
        listOf(BLUEPRINT, BLUEPRINT_LIGHT, LIQUID_GLASS_DARK, LIQUID_GLASS_LIGHT, OPERATOR, DAYLIGHT, CLEAN, NVIDIA)

    /** The theme [DEFAULT_ID] names — resolved, never restated. */
    private val default: BossAppTheme get() = all.first { it.id == DEFAULT_ID }

    /**
     * Resolve a persisted id, falling back to [DEFAULT_ID]'s theme. Derived
     * rather than hardcoded: naming a theme here is exactly the drift the old
     * KDoc warned about, and a comment is a weaker guarantee than an expression.
     */
    fun byId(id: String?): BossAppTheme = all.find { it.id == id } ?: default

    /**
     * The default theme id for a platform, in the platform-explicit form the
     * host's other platform defaults use (`StartupPanelPolicy`,
     * `FocusModeSettings`), so both branches are reachable from a test on any
     * CI leg. Callers resolve `isWindows` themselves: this module is
     * commonMain and has no business reading system properties.
     *
     * **Only a first run with no file at all resolves through here**, and that
     * is the whole safety margin of a default move. `AppThemeSettingsManager`
     * writes the settings file solely from `select()`, so the file's existence
     * is the record that someone chose. A user who has never opened the picker
     * has no file and *is* re-skinned by a change here - which is the intent -
     * while a user who chose Blueprint keeps it, because their file names it
     * (or, under the old writer, says `{}`, which resolves through
     * [DEFAULT_ID] rather than through this).
     *
     * macOS and Linux share a branch because the boolean does: if they ever
     * need to differ, this takes a platform enum rather than a second flag.
     */
    fun defaultIdFor(isWindows: Boolean): String = if (isWindows) WINDOWS_DEFAULT_ID else UNIX_DEFAULT_ID
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
