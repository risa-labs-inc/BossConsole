package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks down the default workspace, how the setting resolves, and the one-time migrations.
 *
 * The default is now platform-independent - nothing is applied until someone picks - but
 * the migrations still branch on the platform, so they are driven through the explicit
 * `isWindows` parameter of [WorkspaceSettingsMigrations.migrate] and both branches run on
 * every CI leg rather than only on the matching runner.
 */
class WorkspaceDefaultsTest {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The whole point of the change: no platform comes up on a layout nobody chose. Asserted
     * as a plain equality on the compiled-in default rather than through a platform helper,
     * because there is no longer a platform branch for a helper to hide.
     */
    @Test
    fun `every platform starts with no workspace and asks`() {
        assertEquals(WorkspaceSettings.ASK_WORKSPACE_ID, WorkspaceSettings().defaultWorkspaceId)
    }

    /**
     * "Ask" must apply nothing by itself. `getDefaultWorkspace()` is what the fresh-start
     * path reads, and a non-null answer there would put a layout on screen at launch - the
     * exact behaviour being removed.
     */
    @Test
    fun `ask and none both resolve to no workspace to apply`() {
        assertIs<ProjectSelectionWorkspace.Ask>(WorkspaceSettings().resolveOnProjectSelection())
        assertIs<ProjectSelectionWorkspace.None>(
            WorkspaceSettings(defaultWorkspaceId = WorkspaceSettings.NO_WORKSPACE_ID).resolveOnProjectSelection(),
        )
    }

    @Test
    fun `an explicitly chosen workspace still resolves to applying it`() {
        val resolved =
            WorkspaceSettings(defaultWorkspaceId = PredefinedWorkspaces.CLAUDE_CODE_ID).resolveOnProjectSelection()
        val apply = assertIs<ProjectSelectionWorkspace.Apply>(resolved)
        assertEquals(PredefinedWorkspaces.CLAUDE_CODE_ID, apply.workspace.id)
    }

    /**
     * A stale id - a workspace a newer build shipped and this one does not - must resolve to
     * doing nothing, not to a prompt. This is what the previous nullable lookup did, and a
     * dialog raised because an id went missing would be unexplainable to the user.
     */
    @Test
    fun `an unknown workspace id resolves to doing nothing`() {
        assertIs<ProjectSelectionWorkspace.None>(
            WorkspaceSettings(defaultWorkspaceId = "workspace-from-the-future").resolveOnProjectSelection(),
        )
    }

    @Test
    fun `the browser-only workspace is a single browser panel on the boss home page`() {
        val workspace = PredefinedWorkspaces.allWorkspaces.single { it.id == PredefinedWorkspaces.BROWSER_ONLY_ID }
        val layout = assertIs<SinglePanel>(workspace.layout, "browser-only must not split the window")
        val tab = layout.panel.tabs.single()
        assertEquals("browser", tab.type)
        assertEquals("https://www.risalabs.ai", tab.url)
    }

    /**
     * The browser-only workspace must stand without a project, or the fresh-start apply
     * declines it and a new Windows install comes up on an empty window instead - see
     * [ai.rever.boss.app.shouldApplyOnFreshStart].
     */
    @Test
    fun `the browser-only workspace needs no project`() {
        val workspace = PredefinedWorkspaces.allWorkspaces.single { it.id == PredefinedWorkspaces.BROWSER_ONLY_ID }
        assertFalse(workspace.requiresProject())
    }

    /** Developer templates keep their existing project requirement. */
    @Test
    fun `developer templates need a project`() {
        val projectFreeIds =
            setOf(
                PredefinedWorkspaces.BROWSER_ONLY_ID,
                PredefinedWorkspaces.DUAL_BROWSER_ID,
                PredefinedWorkspaces.BROWSER_TERMINAL_ID,
            )
        PredefinedWorkspaces.allWorkspaces
            .filterNot { it.id in projectFreeIds }
            .forEach { assertTrue(it.requiresProject(), it.id) }
    }

    @Test
    fun `every predefined workspace id is unique`() {
        val ids = PredefinedWorkspaces.allWorkspaces.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate workspace ids: $ids")
    }

    /**
     * Names, not just ids: `WorkspaceManager.loadAllWorkspaces` drops a saved workspace
     * whose NAME matches a predefined one, and `WorkspaceButton` decides what is
     * renameable the same way. A duplicate name here would make one built-in unreachable.
     */
    @Test
    fun `every predefined workspace name is unique`() {
        val names = PredefinedWorkspaces.allWorkspaces.map { it.name }
        assertEquals(names.size, names.toSet().size, "duplicate workspace names: $names")
    }

    /**
     * Panel ids key the split tree. They used to be `currentTimeMillis()` plus a
     * 1-in-10000 random draw, all minted inside one initializer - so a collision was a
     * dice roll on every launch rather than something a test could catch.
     */
    @Test
    fun `every predefined panel id is unique`() {
        val panelIds = PredefinedWorkspaces.allWorkspaces.flatMap { it.layout.extractPanels().map { p -> p.first } }
        assertEquals(panelIds.size, panelIds.toSet().size, "duplicate panel ids: $panelIds")
    }

    /**
     * The settings file predates the version field, so a file written by any
     * older build decodes as version 0 - which is what makes the migration
     * reachable at all. If the field ever defaults to the current version, every
     * install silently skips every migration.
     */
    @Test
    fun `a settings file without the version field decodes as version zero`() {
        val legacy = json.decodeFromString<WorkspaceSettings>("""{"defaultWorkspaceId":"workspace-claude-code"}""")
        assertEquals(0, legacy.settingsVersion)
        assertEquals(PredefinedWorkspaces.CLAUDE_CODE_ID, legacy.defaultWorkspaceId)
    }

    /**
     * A never-updated Windows file is on the pre-v1 universal Claude Code default. Both steps
     * run against one value in one pass, so it lands on "ask" rather than stopping at
     * browser-only and needing a second launch.
     */
    @Test
    fun `a pre-v1 windows install lands on ask in one pass`() {
        val legacy = WorkspaceSettings(defaultWorkspaceId = PredefinedWorkspaces.CLAUDE_CODE_ID, settingsVersion = 0)

        val migrated = assertNotNull(WorkspaceSettingsMigrations.migrate(legacy, isWindows = true))
        assertEquals(WorkspaceSettings.ASK_WORKSPACE_ID, migrated.defaultWorkspaceId)
        assertEquals(WorkspaceSettings.CURRENT_SETTINGS_VERSION, migrated.settingsVersion)

        // Stamped version, so the next launch is a no-op rather than a rewrite.
        assertNull(WorkspaceSettingsMigrations.migrate(migrated, isWindows = true))
    }

    /** A v1 Windows install already sits on browser-only; step two is the only one that fires. */
    @Test
    fun `a v1 windows install on browser-only moves to ask`() {
        val v1 = WorkspaceSettings(defaultWorkspaceId = PredefinedWorkspaces.BROWSER_ONLY_ID, settingsVersion = 1)

        val migrated = assertNotNull(WorkspaceSettingsMigrations.migrate(v1, isWindows = true))
        assertEquals(WorkspaceSettings.ASK_WORKSPACE_ID, migrated.defaultWorkspaceId)
    }

    /**
     * Browser-only is *not* the pre-v2 default anywhere but Windows, so a Mac or Linux user
     * who picked it deliberately keeps it. The 1 -> 2 step branches on the platform for this
     * reason alone.
     */
    @Test
    fun `a non-windows install that chose browser-only keeps it`() {
        val chosen = WorkspaceSettings(defaultWorkspaceId = PredefinedWorkspaces.BROWSER_ONLY_ID, settingsVersion = 1)

        val migrated = assertNotNull(WorkspaceSettingsMigrations.migrate(chosen, isWindows = false))
        assertEquals(PredefinedWorkspaces.BROWSER_ONLY_ID, migrated.defaultWorkspaceId)
    }

    @Test
    fun `an install that chose another workspace keeps it`() {
        val chosen = WorkspaceSettings(defaultWorkspaceId = "workspace-dual-terminal", settingsVersion = 0)

        val migrated = assertNotNull(WorkspaceSettingsMigrations.migrate(chosen, isWindows = true))
        assertEquals("workspace-dual-terminal", migrated.defaultWorkspaceId)
        assertEquals(WorkspaceSettings.CURRENT_SETTINGS_VERSION, migrated.settingsVersion)
    }

    /**
     * "None" is an answer someone gave, and it is now one of two ways to apply nothing.
     * Rewriting it to "ask" would turn a deliberate "leave me alone" into a prompt on every
     * project selection.
     */
    @Test
    fun `disabled auto-apply survives the migration on both platforms`() {
        listOf(true, false).forEach { isWindows ->
            val none = WorkspaceSettings(defaultWorkspaceId = WorkspaceSettings.NO_WORKSPACE_ID, settingsVersion = 0)

            val migrated = assertNotNull(WorkspaceSettingsMigrations.migrate(none, isWindows = isWindows))
            assertEquals(WorkspaceSettings.NO_WORKSPACE_ID, migrated.defaultWorkspaceId, "isWindows=$isWindows")
        }
    }

    /**
     * The one-shot property depends on the version surviving a write. The manager encodes
     * with `encodeDefaults = true` for exactly this reason - without it a value equal to
     * the class default (0) is omitted, and the next launch decodes 0 and migrates again.
     */
    @Test
    fun `the stamped version survives a write and read`() {
        val encoder =
            Json {
                prettyPrint = true
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
        val stamped =
            WorkspaceSettings(
                defaultWorkspaceId = WorkspaceSettings.ASK_WORKSPACE_ID,
                settingsVersion = WorkspaceSettings.CURRENT_SETTINGS_VERSION,
            )

        val written = encoder.encodeToString(WorkspaceSettings.serializer(), stamped)
        assertTrue("settingsVersion" in written, "the version must be written, not omitted: $written")
        assertNull(
            WorkspaceSettingsMigrations.migrate(json.decodeFromString<WorkspaceSettings>(written), isWindows = true),
            "a file written after migration must not migrate again",
        )
    }

    /** The Mac case the user reported: a fresh-enough install sitting on Claude Code stops. */
    @Test
    fun `a non-windows install on the claude code default moves to ask`() {
        val legacy = WorkspaceSettings(defaultWorkspaceId = PredefinedWorkspaces.CLAUDE_CODE_ID, settingsVersion = 0)

        val migrated = assertNotNull(WorkspaceSettingsMigrations.migrate(legacy, isWindows = false))
        assertEquals(WorkspaceSettings.ASK_WORKSPACE_ID, migrated.defaultWorkspaceId)
        assertEquals(WorkspaceSettings.CURRENT_SETTINGS_VERSION, migrated.settingsVersion)
    }
}
