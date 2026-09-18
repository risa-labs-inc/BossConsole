package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.ui.BossThemes
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A BOSS theme belongs to a Space: which theme a Space resolves to, what the baked template
 * defaults are, and that the record lives beside the Spaces without being read as one.
 *
 * The pure rules are [spaceThemeId], [withSpaceTheme], [spaceThemesDocument] and [spaceThemesFrom].
 * The file round trip goes through a real [WorkspaceFileManager] on a temp directory, for the
 * reason `LastSessionSetTest` does: the thing most likely to go wrong about a new file in that
 * directory is its NAME, because the Space scan reads every `*.json` in it.
 */
class SpaceThemesTest {
    private fun space(id: String) =
        LayoutWorkspace(
            id = id,
            name = "Alpha",
            description = "d",
            layout = SplitConfig.SinglePanel(PanelConfig(id = "panel-" + id, tabs = emptyList())),
        )

    private fun tempManager(): Pair<WorkspaceFileManager, File> {
        val dir = Files.createTempDirectory("space-themes").toFile()
        return WorkspaceFileManager(dir.absolutePath) to dir
    }

    private val baseline = BossThemes.CLEAN.id

    // ==================== resolution: override, then template, then Settings ====================

    @Test
    fun `a Space with no theme of its own wears the Settings theme`() {
        assertEquals(baseline, spaceThemeId("workspace-1788000000001", emptyMap(), baseline))
    }

    @Test
    fun `a template wears its baked theme, not the Settings one`() {
        assertEquals(
            BossThemes.BLUEPRINT.id,
            spaceThemeId(PredefinedWorkspaces.GEMINI_ID, emptyMap(), baseline),
        )
    }

    @Test
    fun `an override beats the baked template default`() {
        val overrides = mapOf(PredefinedWorkspaces.GEMINI_ID to BossThemes.OPERATOR.id)

        assertEquals(BossThemes.OPERATOR.id, spaceThemeId(PredefinedWorkspaces.GEMINI_ID, overrides, baseline))
    }

    @Test
    fun `an override beats the Settings theme for an ordinary Space`() {
        val overrides = mapOf("workspace-1788000000001" to BossThemes.NVIDIA.id)

        assertEquals(BossThemes.NVIDIA.id, spaceThemeId("workspace-1788000000001", overrides, baseline))
    }

    @Test
    fun `a theme id this build does not know is skipped, not honoured`() {
        // A hand-edited file, or a record written by a newer build. `BossThemeController.select`
        // no-ops on an unknown id, so honouring it would leave whatever the LAST Space set.
        val overrides = mapOf(PredefinedWorkspaces.GEMINI_ID to "midnight-retired")

        assertEquals(
            BossThemes.BLUEPRINT.id,
            spaceThemeId(PredefinedWorkspaces.GEMINI_ID, overrides, baseline),
            "an unknown override falls through to the next rule, not to the compiled-in default",
        )
        assertEquals(
            BossThemes.DEFAULT_ID,
            spaceThemeId("workspace-1788000000001", emptyMap(), "midnight-retired"),
            "with nothing left to fall through to, the class default answers",
        )
    }

    // ==================== writing an assignment ====================

    @Test
    fun `setting a template to the theme it already had removes the entry`() {
        // The whole point of baking the defaults is that one can be changed later and reach
        // everyone who has not chosen otherwise. An entry restating today's default would pin it
        // for that user for ever, silently.
        val updated = withSpaceTheme(emptyMap(), PredefinedWorkspaces.GEMINI_ID, BossThemes.BLUEPRINT.id, baseline)

        assertEquals(emptyMap(), updated)
    }

    @Test
    fun `setting an ordinary Space to the Settings theme removes the entry`() {
        val existing = mapOf("workspace-1788000000001" to BossThemes.NVIDIA.id)

        val updated = withSpaceTheme(existing, "workspace-1788000000001", baseline, baseline)

        assertEquals(emptyMap(), updated, "riding the baseline is what an absent entry means")
    }

    @Test
    fun `null clears an override`() {
        val existing = mapOf(PredefinedWorkspaces.GEMINI_ID to BossThemes.OPERATOR.id)

        assertEquals(emptyMap(), withSpaceTheme(existing, PredefinedWorkspaces.GEMINI_ID, null, baseline))
    }

    @Test
    fun `an unknown theme id writes nothing`() {
        val updated = withSpaceTheme(emptyMap(), "workspace-1788000000001", "midnight-retired", baseline)

        assertEquals(emptyMap(), updated)
    }

    @Test
    fun `an empty workspace id writes nothing`() {
        // A window with no Space loaded has one, and it is not a key anything could resolve later.
        assertEquals(emptyMap(), withSpaceTheme(emptyMap(), "", BossThemes.NVIDIA.id, baseline))
    }

    @Test
    fun `a real choice is kept`() {
        val updated = withSpaceTheme(emptyMap(), PredefinedWorkspaces.GEMINI_ID, BossThemes.OPERATOR.id, baseline)

        assertEquals(mapOf(PredefinedWorkspaces.GEMINI_ID to BossThemes.OPERATOR.id), updated)
    }

    // ==================== the document ====================

    @Test
    fun `no assignments writes no file`() {
        assertNull(spaceThemesDocument(emptyMap()), "null is what DELETES the record")
    }

    @Test
    fun `assignments survive a round trip`() {
        val assignments =
            mapOf(
                PredefinedWorkspaces.GEMINI_ID to BossThemes.OPERATOR.id,
                "workspace-1" to BossThemes.NVIDIA.id,
            )

        assertEquals(assignments, spaceThemesFrom(spaceThemesDocument(assignments)))
    }

    @Test
    fun `a broken record is no assignments rather than a failed launch`() {
        assertEquals(emptyMap(), spaceThemesFrom("{ this is not json"))
        assertEquals(emptyMap(), spaceThemesFrom(null))
    }

    @Test
    fun `a retired theme is dropped on the way in`() {
        // So it cannot be kept alive in memory and written back out on the next change.
        val json = """{"themes":{"workspace-1":"midnight-retired","workspace-2":"${BossThemes.NVIDIA.id}"}}"""

        assertEquals(mapOf("workspace-2" to BossThemes.NVIDIA.id), spaceThemesFrom(json))
    }

    // ==================== the baked template defaults ====================

    @Test
    fun `existing templates retain baked themes while starter splits follow settings`() {
        val starterIds = setOf(PredefinedWorkspaces.DUAL_BROWSER_ID, PredefinedWorkspaces.BROWSER_TERMINAL_ID)
        assertEquals(PredefinedWorkspaces.allIds - starterIds, TEMPLATE_SPACE_THEMES.keys)
        starterIds.forEach { id ->
            BossThemes.all.forEach { theme ->
                assertEquals(theme.id, spaceThemeId(id, emptyMap(), theme.id))
            }
            assertEquals(
                BossThemes.CLEAN.id,
                spaceThemeId(id, mapOf(id to BossThemes.CLEAN.id), BossThemes.BLUEPRINT.id),
            )
        }
    }

    @Test
    fun `every baked theme is one this build has`() {
        val known = BossThemes.all.map { it.id }.toSet()

        assertTrue(
            TEMPLATE_SPACE_THEMES.values.all { it in known },
            "a baked id that no theme answers to would resolve through to the Settings theme " +
                "silently: ${TEMPLATE_SPACE_THEMES.values - known}",
        )
    }

    @Test
    fun `the platform default layouts carry their platform's default theme`() {
        // Forced, not chosen. These two are what each platform opens with, so anything else here
        // would flip a first run off `BossThemes.defaultIdFor` the instant it entered its own
        // default Space - and that function would be a dead letter for the users it exists for.
        assertEquals(
            BossThemes.defaultIdFor(isWindows = false),
            TEMPLATE_SPACE_THEMES[PredefinedWorkspaces.CLAUDE_CODE_ID],
        )
        assertEquals(
            BossThemes.defaultIdFor(isWindows = true),
            TEMPLATE_SPACE_THEMES[PredefinedWorkspaces.BROWSER_ONLY_ID],
        )
    }

    @Test
    fun `the four AI CLI templates never share a colour`() {
        // They are the four most likely to be open at once, and a tint that cannot tell two Spaces
        // apart is a tint that says nothing. BOSS ships four hue families, which is exactly enough.
        val cliTemplates =
            listOf(
                PredefinedWorkspaces.CLAUDE_CODE_ID,
                PredefinedWorkspaces.GEMINI_ID,
                PredefinedWorkspaces.CODEX_ID,
                PredefinedWorkspaces.OPENCODE_ID,
            )
        val signals = cliTemplates.map { BossThemes.byId(TEMPLATE_SPACE_THEMES[it]).colors.signal }

        assertEquals(cliTemplates.size, signals.toSet().size, "two AI CLI templates resolve to one colour: $signals")
    }

    @Test
    fun `Daylight is baked onto no template`() {
        // Its amber signal is 2.63:1 on its own near-white floor, under the 3:1 floor for UI
        // components - the recorded debt that keeps it from being the Windows default. A baked
        // template theme is met without being chosen, so the same objection applies.
        assertFalse(BossThemes.DAYLIGHT.id in TEMPLATE_SPACE_THEMES.values)
    }

    // ==================== the file ====================

    @Test
    fun `the record is written beside the Spaces and is not read as one`() {
        val (fileManager, dir) = tempManager()
        val assignments = mapOf(PredefinedWorkspaces.GEMINI_ID to BossThemes.OPERATOR.id)

        val savedSpace =
            fileManager.saveWorkspaceBlocking(space("workspace-1"))
        val saved = fileManager.writeDocumentBlocking(SPACE_THEMES_FILE, spaceThemesDocument(assignments))

        assertNotNull(savedSpace)
        assertTrue(saved)

        val listed = runBlocking { fileManager.listWorkspaces() }.map { it.fileName }
        assertTrue(
            SPACE_THEMES_FILE in listed,
            "the Space scan is 'every *.json' in the directory, so the record IS listed: $listed - " +
                "which is exactly why WorkspaceManager skips it by name",
        )

        assertEquals(assignments, spaceThemesFrom(runBlocking { fileManager.loadDocument(SPACE_THEMES_FILE) }))

        dir.deleteRecursively()
    }

    @Test
    fun `clearing the last assignment deletes the record and leaves the Spaces alone`() {
        val (fileManager, dir) = tempManager()
        fileManager.saveWorkspaceBlocking(space("workspace-1"))
        val one = spaceThemesDocument(mapOf("workspace-1" to BossThemes.NVIDIA.id))
        fileManager.writeDocumentBlocking(SPACE_THEMES_FILE, one)

        assertTrue(fileManager.writeDocumentBlocking(SPACE_THEMES_FILE, spaceThemesDocument(emptyMap())))

        assertFalse(File(fileManager.getWorkspaceFilePath(SPACE_THEMES_FILE)).exists())
        assertTrue(
            File(fileManager.getWorkspaceFilePath(WorkspaceFileManagerCommon.fileNameForId("workspace-1"))).exists(),
            "deleting the theme record must not touch a saved Space",
        )

        dir.deleteRecursively()
    }

    @Test
    fun `a Space cannot be named onto the record's path`() {
        // The reserved-path collision `fileNameForId` closed as a class, asked again for this file.
        assertTrue(WorkspaceFileManagerCommon.fileNameForId("workspace-1788000000001") != SPACE_THEMES_FILE)
        assertTrue(WorkspaceFileManagerCommon.fileNameForId(PredefinedWorkspaces.GEMINI_ID) != SPACE_THEMES_FILE)
    }
}
