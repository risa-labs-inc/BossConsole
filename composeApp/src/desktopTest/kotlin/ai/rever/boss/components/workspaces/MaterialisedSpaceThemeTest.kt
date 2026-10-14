package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.ui.BossThemes
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A Space materialised from a template wears the template's theme.
 *
 * **The bug this pins showed as a flash.** Picking a template applied its baked theme, and about
 * half a second later the app reverted: the pick enters the TEMPLATE (whose theme
 * `TEMPLATE_SPACE_THEMES` names), then `spaceToOpen` materialises a NEW Space with a fresh
 * `generateId()` and enters THAT - and a fresh id is neither a built-in nor an override, so it
 * resolved to the Settings baseline. Two Spaces entered back to back, not a race.
 *
 * The inherited theme is written through `WorkspaceManager.setSpaceTheme`, the one writer
 * everything else uses, so the new Space owns its theme EXPLICITLY. That matters beyond tidiness:
 * an inheritance that merely re-derived from the template later would silently move an existing
 * Space the day a shipped template's default changed.
 */
class MaterialisedSpaceThemeTest {
    private val claudeCode =
        PredefinedWorkspaces.allWorkspaces.single { it.id == PredefinedWorkspaces.CLAUDE_CODE_ID }

    /**
     * A manager of its own, starting from a template that wears its BAKED default.
     *
     * `user.home` is redirected per test task (see composeApp's build script), so the workspace
     * directory this writes into is under `build/test-home` rather than a real Documents folder -
     * but every test in this class shares that one directory, and `Space_Themes.json` outlives the
     * test that wrote it. So the clear is not tidiness: without it, whether a test sees the baked
     * default depends on which test ran first, and the whole suite passed or failed by ordering.
     *
     * It also settles the seed. `setSpaceTheme` marks this store as written to, so the manager's
     * own asynchronous read of that file cannot land on top of what a test then sets.
     */
    private fun manager() = WorkspaceManager().also { it.setSpaceTheme(PredefinedWorkspaces.CLAUDE_CODE_ID, null) }

    @Test
    fun `a materialised Space inherits its template's theme rather than the baseline`() {
        val manager = manager()
        val templateTheme = manager.themeIdFor(PredefinedWorkspaces.CLAUDE_CODE_ID)
        assertNotEquals(
            SettingsThemeBaseline.themeId.value,
            templateTheme,
            "this test proves nothing if the template already resolves to the baseline",
        )

        val opened =
            runBlocking {
                spaceToOpen(picked = claudeCode, projectPath = "/tmp/some-project", manager = manager)
            }

        assertNotEquals(claudeCode.id, opened.id, "the pick really did materialise into a new Space")
        assertEquals(
            templateTheme,
            manager.themeIdFor(opened.id),
            "the Space the user is now in reverted to the Settings theme, which is the flash",
        )
    }

    @Test
    fun `the inherited theme is written down, not re-derived`() {
        val manager = manager()

        val opened =
            runBlocking {
                spaceToOpen(picked = claudeCode, projectPath = "/tmp/some-project", manager = manager)
            }

        assertEquals(
            manager.themeIdFor(PredefinedWorkspaces.CLAUDE_CODE_ID),
            manager.spaceThemes.value[opened.id],
            "an entry of its own is what stops a later change to the template's baked default " +
                "silently re-theming a Space somebody has been using for months",
        )
    }

    /**
     * Also the pin for `WorkspaceManager.spaceThemesAssigned`.
     *
     * The assignment below happens in the first moments of a manager's life, while its own
     * asynchronous read of `Space_Themes.json` is still in flight - which is exactly when the
     * inheritance this class is about runs. Without the seed guard the file lands on top of the
     * override and this test reads the baked default back.
     */
    @Test
    fun `a user's override of the template is what gets inherited, not the baked default`() {
        val manager = manager()
        manager.setSpaceTheme(PredefinedWorkspaces.CLAUDE_CODE_ID, BossThemes.CLEAN.id)

        val opened =
            runBlocking {
                spaceToOpen(picked = claudeCode, projectPath = "/tmp/some-project", manager = manager)
            }

        assertEquals(BossThemes.CLEAN.id, manager.themeIdFor(opened.id))
    }

    @Test
    fun `with no project the template is applied as itself, so its own theme already answers`() {
        // Confirmed rather than assumed: this path materialises nothing, so the Space entered IS
        // the template and `TEMPLATE_SPACE_THEMES` resolves it with no inheritance involved.
        val manager = manager()

        val opened = runBlocking { spaceToOpen(picked = claudeCode, projectPath = "", manager = manager) }

        assertEquals(claudeCode.id, opened.id)
        assertEquals(TEMPLATE_SPACE_THEMES[PredefinedWorkspaces.CLAUDE_CODE_ID], manager.themeIdFor(opened.id))
        assertTrue(
            opened.id !in manager.spaceThemes.value,
            "and nothing was written: a template wearing its own baked default owns no entry",
        )
    }
}
