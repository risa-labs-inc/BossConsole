package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.plugin.workspace.TabConfig
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A name is identity, not an address: two Spaces may share one and neither loses its layout.
 *
 * **This is the test the naming suffix existed for.** The file path used to be
 * `generateFileName(displayName)`, so one name was one file and
 * `DesktopWorkspaceFileManager.saveWorkspaceBlocking` atomically replaced whatever sat there - the
 * second save destroyed the first Space's layout while both rows stayed in the list. A suffix on
 * derived names made the collision improbable and was therefore guarding real data loss, which is
 * why the suffix could not simply be deleted: the PATH had to move first.
 *
 * The hazard was reachable with no suffix in sight, which is what settled it:
 * `materialisedTemplateName` mints a fresh id with no uniqueness check, so materialising one
 * template twice against one project gave two ids and one file, and a name typed into
 * "Save Space..." bypassed `uniqueWorkspaceName` altogether.
 *
 * Driven through a real [WorkspaceFileManager] on a temp directory, because the defect lives in the
 * seam between writing and reading: both halves are correct in isolation.
 */
class SpaceNameIsNotAPathTest {
    private fun layout(vararg titles: String) =
        SplitConfig.SinglePanel(
            PanelConfig(id = "main", tabs = titles.map { TabConfig(type = "terminal", title = it) }),
        )

    private fun space(
        id: String,
        name: String,
        layout: SplitConfig,
    ) = LayoutWorkspace(id = id, name = name, description = "d", layout = layout, timestamp = 1_000)

    private fun tempFileManager(): Pair<WorkspaceFileManager, File> {
        val dir = Files.createTempDirectory("space-name-not-path").toFile()
        return WorkspaceFileManager(dir.absolutePath) to dir
    }

    /** Everything the manager's load scan would produce. */
    private fun reload(fileManager: WorkspaceFileManager): List<LayoutWorkspace> =
        runBlocking {
            fileManager
                .listWorkspaces()
                .filterNot { it.fileName == LAST_SESSION_SET_FILE }
                .mapNotNull { fileManager.loadWorkspace(it.fileName) }
        }

    // ==================== A1: the path is the id ====================

    /**
     * The failing-before test for the whole track. Today the second save destroys the first
     * layout, because both Spaces resolve to `My_Space.json`.
     */
    @Test
    fun `two Spaces with one name both survive a save and reload with their own layouts`() {
        val (fileManager, dir) = tempFileManager()
        val first = space("workspace-1788000000001", "My Space", layout("first"))
        val second = space("workspace-1788000000002", "My Space", layout("second"))

        // What the manager writes: the file comes from the ID.
        assertNotNull(fileManager.saveWorkspaceBlocking(first, WorkspaceFileManagerCommon.fileNameForId(first.id)))
        assertNotNull(fileManager.saveWorkspaceBlocking(second, WorkspaceFileManagerCommon.fileNameForId(second.id)))

        val reloaded = mergeSavedWorkspaces(PredefinedWorkspaces.allWorkspaces, reload(fileManager))

        assertEquals(
            layout("first"),
            reloaded.single { it.id == first.id }.layout,
            "the first Space's layout must survive the second one being saved",
        )
        assertEquals(layout("second"), reloaded.single { it.id == second.id }.layout)
        assertEquals(2, reloaded.count { it.name == "My Space" }, "and both rows are real Spaces")
        dir.deleteRecursively()
    }

    @Test
    fun `the file is named for the id, and a name with punctuation cannot escape the directory`() {
        val (fileManager, dir) = tempFileManager()
        // A name is free text now, so it must reach no path decision at all.
        val awkward = space("workspace-1788000000001", "../../etc/passwd", layout("x"))

        val path = fileManager.saveWorkspaceBlocking(awkward, WorkspaceFileManagerCommon.fileNameForId(awkward.id))

        assertEquals("workspace-1788000000001.json", File(path!!).name)
        assertEquals(dir.absolutePath, File(path).parentFile.absolutePath, "written inside the directory")
        assertEquals("../../etc/passwd", reload(fileManager).single().name, "and the name is kept verbatim")
        dir.deleteRecursively()
    }

    @Test
    fun `an id read from a hand-edited file cannot escape the directory either`() {
        // The id is now a path component, so it is sanitised the same way a name was.
        assertEquals(".._.._etc_passwd.json", WorkspaceFileManagerCommon.fileNameForId("../../etc/passwd"))
        assertEquals(
            "workspace-1788000000001.json",
            WorkspaceFileManagerCommon.fileNameForId("workspace-1788000000001"),
        )
    }

    @Test
    fun `a Space named like the session record does not resolve to the record's file`() {
        // `generateFileName("Last Session Set")` was `Last_Session_Set.json`: a Space with that
        // name overwrote the session record and was then skipped on load, and nothing refused the
        // name. The path no longer comes from the name, so the collision is gone as a class.
        assertEquals(LAST_SESSION_SET_FILE, WorkspaceFileManagerCommon.generateFileName("Last Session Set"))
        assertTrue(
            WorkspaceFileManagerCommon.fileNameForId("workspace-1788000000001") != LAST_SESSION_SET_FILE,
        )
    }

    // ==================== A2: the vetoes are id-keyed ====================

    /**
     * The failing-before test for A2. Today the button hides this Space from the delete dialog and
     * the manager refuses it, both because the SHIPPED Codex answers to the same name.
     */
    @Test
    fun `a Space named exactly like a template is the user's, so it can be deleted and renamed`() {
        val mine = space("workspace-1788000000001", "Codex", layout("mine"))
        val shipped = PredefinedWorkspaces.allWorkspaces.single { it.id == PredefinedWorkspaces.CODEX_ID }
        assertEquals(shipped.name, mine.name, "the fixture is only interesting if the names match")

        assertTrue(isUserOwnedSpace(mine.id), "it is the user's Space, whatever it is called")
        assertFalse(isUserOwnedSpace(shipped.id), "and the shipped layout is not theirs to delete")

        val offered = deletableWorkspaces(PredefinedWorkspaces.allWorkspaces + mine)
        assertEquals(
            listOf(mine.id),
            offered.map { it.id },
            "the delete dialog offers the user's Space and none of the shipped ones",
        )
    }

    @Test
    fun `the session record is listed as deletable, which is unchanged behaviour`() {
        // Stated rather than asserted as desirable: it was never a predefined NAME either, so the
        // dialog has always offered it. Worth pinning so a change to it is a decision.
        assertTrue(isUserOwnedSpace(LAST_SESSION_ID))
    }

    // ==================== A3: the name carries no state word ====================

    @Test
    fun `a Space adopted from a legacy file keeps the name the file carries`() {
        val legacy = space("workspace-code-review", "Code Review", layout("a", "b"))

        val merged = mergeSavedWorkspaces(PredefinedWorkspaces.allWorkspaces, listOf(legacy))

        assertEquals(
            "Code Review",
            merged.single { it.id == "workspace-code-review-saved" }.name,
            "no suffix: the shipped Code Review is in the Templates section, which is the distinction",
        )
        assertEquals("Code Review", merged.single { it.id == "workspace-code-review" }.name)
    }

    @Test
    fun `saving out of a shipped layout keeps its name`() {
        val shipped = PredefinedWorkspaces.allWorkspaces.single { it.id == PredefinedWorkspaces.BROWSER_ONLY_ID }

        val saved =
            savedCopyOfSlot(
                current = shipped,
                id = "workspace-1788000000001",
                now = 1_788_000_000_000,
                takenNames = savedSpaceNames(PredefinedWorkspaces.allWorkspaces),
            )

        assertEquals("Browser Only", saved.name, "a name is identity; unsaved is a state with its own marks")
    }

    @Test
    fun `two Spaces are numbered against each other, never against a shipped layout`() {
        // The collision worth avoiding is user-vs-user: it is a list picked from by name. The
        // first run of the round trip numbered the four recovered Spaces to "Code Review 2"
        // because the shipped names were in the taken set.
        val mine = space("workspace-1788000000001", "Code Review", layout("x"))
        val merged = mergeSavedWorkspaces(PredefinedWorkspaces.allWorkspaces, listOf(mine))

        assertEquals("Code Review", merged.single { it.id == mine.id }.name)
        assertEquals(
            setOf("Code Review"),
            savedSpaceNames(merged),
            "only Spaces are in the taken set, and the shipped layouts are not",
        )

        val second = space("workspace-1788000000002", "Code Review", layout("y"))
        assertEquals(
            "Code Review 2",
            uniqueWorkspaceName(second.name, savedSpaceNames(merged)),
            "a second Space of that name is numbered, because the list would be unreadable",
        )
    }
}
