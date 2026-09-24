package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.ui.BossThemes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The name of the Space-to-theme record, in the workspace directory beside the Spaces.
 *
 * **`WorkspaceManager.loadAllWorkspaces` skips it by name**, exactly as it skips
 * [LAST_SESSION_SET_FILE] and for the same reason: `WorkspaceFileManager.listWorkspaces` lists
 * every `*.json` in that directory and the manager tries to read each one as a [LayoutWorkspace],
 * so without the skip this file would be deserialized as a Space on every launch, fail, and log a
 * warning for ever.
 */
const val SPACE_THEMES_FILE = "Space_Themes.json"

/**
 * Which theme each Space wears, for the Spaces whose owner has said.
 *
 * **A side document, deliberately, rather than a field on [LayoutWorkspace].** That data class is
 * the plugin api type, member-checked against 33 plugin repos: a constructor parameter on it is a
 * hard break, and the price of doing it anyway is `@JvmOverloads` plus a hidden deprecated `copy()`
 * plus a hand-written `SerializationConstructorMarker` constructor on `PanelConfig`. The same
 * reasoning [LastSessionSet] records, and the same verb it uses -
 * `WorkspaceFileManager.writeDocumentBlocking` / `loadDocument`, for records that live beside the
 * Spaces without being one.
 *
 * **Only overrides live here.** The eight layouts BOSS ships have [TEMPLATE_SPACE_THEMES], which is
 * BAKED rather than written out on first run, so shipping a different default for a template later
 * reaches everybody who has not chosen otherwise and needs no migration. A user's choice lands in
 * this file and wins; clearing it puts that Space back on the baked default, or on the Settings
 * theme if it has none.
 */
@Serializable
data class SpaceThemeAssignments(
    val themes: Map<String, String> = emptyMap(),
)

/** JSON for [SpaceThemeAssignments], with the same forward-compatible settings as [WorkspaceSerializer]. */
object SpaceThemeAssignmentsSerializer {
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    fun serialize(assignments: SpaceThemeAssignments): String = json.encodeToString(assignments)

    fun deserialize(jsonString: String): SpaceThemeAssignments = json.decodeFromString(jsonString)
}

/**
 * The theme each of the eight layouts BOSS ships opens in, baked rather than persisted.
 *
 * **Two of these are forced, not chosen.** [PredefinedWorkspaces.CLAUDE_CODE_ID] is the layout
 * macOS and Linux name as their default and [PredefinedWorkspaces.BROWSER_ONLY_ID] is Windows',
 * so each carries its own platform's default theme ([BossThemes.UNIX_DEFAULT_ID],
 * [BossThemes.WINDOWS_DEFAULT_ID]). Anything else would flip a first run off the theme its
 * platform deliberately opens with, the instant it entered the layout that platform deliberately
 * opens with - and `defaultIdFor` would be a dead letter for exactly the users it exists for.
 *
 * **The four AI-CLI templates never share a hue**, because they are the four most likely to be
 * open at once: Claude Code is green, Gemini electric blue, Codex steel blue, OpenCode amber. BOSS
 * ships six themes in four hue families, so with eight templates something has to repeat; every
 * repeat here pairs a branded layout with the un-branded layout of the SAME SHAPE, which are
 * substitutes rather than neighbours - Terminal + Browser is Codex with no agent in it, Dual
 * Terminal is OpenCode with no agent in it, and Code Review is Browser Only's other reading
 * surface.
 *
 * **Daylight is deliberately on none of them.** It carries a recorded contrast defect - its amber
 * signal sits at 2.63:1 on its own near-white floor, under the 3:1 floor for UI components, which
 * `BossThemesRegistryTest` pins as debt - and that is precisely why it is not the Windows default.
 * A baked template theme is met without being chosen, so the same objection applies. Blueprint
 * Light is the light theme with no such debt, and it carries both light slots.
 */
val TEMPLATE_SPACE_THEMES: Map<String, String> =
    mapOf(
        // The macOS/Linux default layout, on the macOS/Linux default theme.
        PredefinedWorkspaces.CLAUDE_CODE_ID to BossThemes.UNIX_DEFAULT_ID,
        // The one layout here you READ rather than drive; paper is the reading surface, and the
        // light value separates it at a glance from every dark neighbour.
        PredefinedWorkspaces.CODE_REVIEW_ID to BossThemes.BLUEPRINT_LIGHT.id,
        // Gemini is Google's, and #0F5BFF is the nearest thing BOSS ships to Google blue.
        PredefinedWorkspaces.GEMINI_ID to BossThemes.BLUEPRINT.id,
        // OpenAI's identity is greyscale; a neutral charcoal with a restrained steel-blue accent is
        // the closest BOSS gets, and steel blue stays a different hue from Gemini's electric blue.
        PredefinedWorkspaces.CODEX_ID to BossThemes.CLEAN.id,
        // Amber on ink is the original terminal identity, and it is the fourth distinct hue - so no
        // two of the four AI-CLI templates share one.
        PredefinedWorkspaces.OPENCODE_ID to BossThemes.OPERATOR.id,
        // Codex's shape with no agent in it, on the theme with no vendor in it.
        PredefinedWorkspaces.TERMINAL_BROWSER_ID to BossThemes.CLEAN.id,
        // Two shells and nothing else: OpenCode's picture without the agent, so it wears OpenCode's
        // amber.
        PredefinedWorkspaces.DUAL_TERMINAL_ID to BossThemes.OPERATOR.id,
        // The student's Space. It is driven, not read - two terminals and a notebook doing the
        // building - so it does not want a paper surface like Code Review's; and it is the one
        // template with no vendor identity to echo, so it takes the platform-default dark theme
        // a fresh install would meet anyway: the first Space a student materialises looks like
        // the rest of their machine, and they make it theirs from there.
        PredefinedWorkspaces.PROJECT_STUDIO_ID to BossThemes.UNIX_DEFAULT_ID,
        // The Windows default layout, on the Windows default theme.
        PredefinedWorkspaces.BROWSER_ONLY_ID to BossThemes.WINDOWS_DEFAULT_ID,
    )

/**
 * The theme [workspaceId] should be showing: its own override, then the baked template default,
 * then [settingsThemeId].
 *
 * **An id this build does not know is skipped rather than honoured**, at every level. A theme that
 * has been retired can sit in a hand-edited file or in a record written by a newer build, and
 * `BossThemeController.select` would silently no-op on it - leaving whatever the last Space
 * happened to set. Falling through to the next answer instead means the Space still gets a theme,
 * and it is the one the next rule would have given it.
 *
 * Pure in its inputs, so what a Space shows is a function of the file, the bakery and the baseline,
 * and nothing else.
 */
fun spaceThemeId(
    workspaceId: String,
    overrides: Map<String, String>,
    settingsThemeId: String,
): String =
    listOfNotNull(overrides[workspaceId], TEMPLATE_SPACE_THEMES[workspaceId], settingsThemeId)
        .firstOrNull(::isKnownThemeId)
        ?: BossThemes.DEFAULT_ID

/**
 * [assignments] with [workspaceId] set to [themeId], or cleared when [themeId] is null.
 *
 * **Setting a Space to the theme it would have anyway REMOVES the entry** rather than writing it
 * down. The whole point of baking [TEMPLATE_SPACE_THEMES] is that a template's default can be
 * changed later and reach everyone who has not chosen otherwise; an entry that merely restates
 * today's default would pin that template for that user for ever, and they would never know they
 * had opted out. The same holds for the Settings baseline: a Space told to wear the baseline is a
 * Space with no theme of its own, which is what an absent entry means.
 *
 * An unknown theme id writes nothing, for the reason [spaceThemeId] skips one on the way back out.
 */
@Suppress("ReturnCount") // Three ordered refusals - no Space, a clear, an unknown theme - then the write.
fun withSpaceTheme(
    assignments: Map<String, String>,
    workspaceId: String,
    themeId: String?,
    settingsThemeId: String,
): Map<String, String> {
    if (workspaceId.isEmpty()) return assignments
    if (themeId == null) return assignments - workspaceId
    if (!isKnownThemeId(themeId)) return assignments
    val withoutOverride = spaceThemeId(workspaceId, assignments - workspaceId, settingsThemeId)
    return if (themeId == withoutOverride) assignments - workspaceId else assignments + (workspaceId to themeId)
}

/** What to write for [assignments]: null when there is nothing to say, which DELETES the file. */
fun spaceThemesDocument(assignments: Map<String, String>): String? =
    if (assignments.isEmpty()) {
        null
    } else {
        SpaceThemeAssignmentsSerializer.serialize(SpaceThemeAssignments(assignments))
    }

/**
 * The assignments in [json], or none when it is absent, unreadable or names nothing this build
 * knows.
 *
 * Unknown ids are dropped HERE as well as in [spaceThemeId], so a file written by a newer build
 * does not keep a dead theme alive in memory and then write it back out on the next change.
 *
 * Pure, so it can be tested on its own - which leaves it nothing to log with. The caller warns on
 * "there were bytes and they said nothing", which covers both a broken record and one naming only
 * themes this build has retired; an assignment map that is legitimately empty is never written at
 * all (see [spaceThemesDocument]), so there is no innocent case to shout about.
 */
@Suppress("SwallowedException") // Pure, so it has nothing to log with; the caller warns on an empty result.
fun spaceThemesFrom(json: String?): Map<String, String> {
    if (json == null) return emptyMap()
    return try {
        SpaceThemeAssignmentsSerializer
            .deserialize(json)
            .themes
            .filter { (id, themeId) -> id.isNotEmpty() && isKnownThemeId(themeId) }
    } catch (e: IllegalArgumentException) {
        // A truncated or hand-broken record is "no Spaces are themed", not a failed launch - the
        // same fallback `WorkspaceManager.loadLastSessionSet` makes. `SerializationException` IS an
        // `IllegalArgumentException`, so this is the narrow catch, not a widened one.
        emptyMap()
    }
}

private fun isKnownThemeId(themeId: String): Boolean = BossThemes.all.any { it.id == themeId }

/**
 * The theme chosen in **Settings**: what a Space wearing no theme of its own shows.
 *
 * **A Space theme is an override LAYERED OVER this, never a replacement for it.** The switch drives
 * `BossThemeController.select` alone and never `AppThemeSettingsManager.select`, which would
 * persist into `app-theme-settings.json` and destroy the baseline the user picked - after two Space
 * switches there would be no record of what they had chosen, and nothing to fall back to for a
 * Space that names no theme.
 *
 * It is written by `AppThemeSettingsManager` (which is desktop-only, because it resolves a home
 * directory) and read here, in `commonMain`, where the Spaces are. A `StateFlow` rather than a
 * plain value because `WorkspaceManager.spaceAccents` is derived from it: a Settings change has to
 * move the colour of every Space that was riding the baseline.
 */
object SettingsThemeBaseline {
    private val _themeId = MutableStateFlow(BossThemes.DEFAULT_ID)

    val themeId: StateFlow<String> = _themeId.asStateFlow()

    /** Record the Settings choice. Ignores an id this build does not know, as `select` does. */
    fun set(themeId: String) {
        if (isKnownThemeId(themeId)) _themeId.value = themeId
    }
}
