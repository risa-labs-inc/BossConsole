package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.plugin.workspace.TabConfig
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * That a save survives a relaunch, and that a Space is not dropped for being called the same thing
 * as a shipped layout.
 *
 * **The round trips here go through a real [WorkspaceFileManager] on a temp directory**, because
 * the defect was in the seam between writing and reading: the write was fine and the read threw it
 * away. A test on either half alone passes against the bug.
 *
 * `WorkspaceManager` itself cannot be driven from a test - it is a singleton on a
 * `Dispatchers.Main` scope writing to the user's real Documents folder - so the two rules it now
 * calls are the pure functions [savedCopyOfBuiltIn] and [mergeSavedWorkspaces], and these exercise
 * them either side of the file manager exactly as it does.
 */
class SavedSpaceMergeTest {
    private fun layout(vararg titles: String) =
        SplitConfig.SinglePanel(
            PanelConfig(id = "main", tabs = titles.map { TabConfig(type = "terminal", title = it) }),
        )

    private fun space(
        id: String,
        name: String,
        layout: SplitConfig = layout("one"),
        timestamp: Long = 1_000,
    ) = LayoutWorkspace(id = id, name = name, description = "d", layout = layout, timestamp = timestamp)

    /** The shipped layouts, as the manager starts from them. */
    private val predefined = PredefinedWorkspaces.allWorkspaces

    private val browserOnly = predefined.single { it.id == PredefinedWorkspaces.BROWSER_ONLY_ID }

    private fun tempFileManager(): Pair<WorkspaceFileManager, File> {
        val dir = Files.createTempDirectory("saved-space-merge").toFile()
        return WorkspaceFileManager(dir.absolutePath) to dir
    }

    /** Everything the manager would read back off disk, in the order it scans. */
    private fun reload(fileManager: WorkspaceFileManager): List<LayoutWorkspace> =
        runBlocking {
            fileManager
                .listWorkspaces()
                .filterNot { it.fileName == LAST_SESSION_SET_FILE }
                .mapNotNull { fileManager.loadWorkspace(it.fileName) }
        }

    /** What `saveCurrentWorkspace` writes, for a window whose current Space is [current]. */
    private fun saveOf(
        current: LayoutWorkspace,
        list: List<LayoutWorkspace>,
        requestedName: String? = null,
    ): LayoutWorkspace =
        if (current.id in PredefinedWorkspaces.allIds) {
            savedCopyOfBuiltIn(
                current = current,
                id = "workspace-1788000000000",
                now = 2_000,
                takenNames = list.map { it.name }.toSet(),
                requestedName = requestedName,
            )
        } else {
            current.copy(name = requestedName ?: current.name, timestamp = 2_000)
        }

    // ==================== defect 1: a save on a built-in must survive ====================

    /**
     * The headline, and the test that fails before this change. The old save wrote the built-in's
     * own id and name, so the file landed as `Browser_Only.json` and the name dedupe dropped it on
     * the next launch in favour of the shipped entry - the Save button appearing to work and then
     * losing the layout.
     */
    @Test
    fun `a save made while on a built-in is still there after a reload`() {
        val (fileManager, dir) = tempFileManager()
        // The user opened a second tab in Browser Only and pressed save.
        val edited = browserOnly.copy(layout = layout("RISA Labs", "GitHub"))

        val written = saveOf(edited, predefined)
        assertNotNull(fileManager.saveWorkspaceBlocking(written))

        val list = mergeSavedWorkspaces(predefined, reload(fileManager))
        val mine = list.singleOrNull { it.id == written.id }

        assertNotNull(mine, "the saved Space must be in the list after a relaunch, got ${list.map { it.name }}")
        assertEquals(layout("RISA Labs", "GitHub"), mine.layout, "and it must be the layout that was saved")
        dir.deleteRecursively()
    }

    @Test
    fun `the shipped layout is untouched by a save on top of it`() {
        val (fileManager, dir) = tempFileManager()
        val edited = browserOnly.copy(layout = layout("RISA Labs", "GitHub"))

        fileManager.saveWorkspaceBlocking(saveOf(edited, predefined))
        val list = mergeSavedWorkspaces(predefined, reload(fileManager))

        val shipped = list.singleOrNull { it.id == PredefinedWorkspaces.BROWSER_ONLY_ID }
        assertNotNull(shipped, "the template has to stay in the list, or Templates loses a tile")
        assertEquals(browserOnly, shipped, "pristine: same id, same name, same layout as shipped")
        dir.deleteRecursively()
    }

    @Test
    fun `a save on a built-in takes a new id and a name that collides with nothing`() {
        val written = saveOf(browserOnly, predefined)

        assertTrue(
            written.id !in PredefinedWorkspaces.allIds,
            "a file carrying a built-in id is the legacy shape this exists to stop making more of",
        )
        assertEquals("Browser Only (saved)", written.name)
        assertTrue(predefined.none { it.name == written.name }, "and no shipped layout answers to it")
    }

    @Test
    fun `a second save on the same built-in does not collide with the first`() {
        // The Space list is keyed by NAME where it writes, so two entries sharing one would mean
        // saving either destroys the other.
        val first = saveOf(browserOnly, predefined)
        val second = saveOf(browserOnly, predefined + first)
        val third = saveOf(browserOnly, predefined + first + second)

        assertEquals(
            listOf("Browser Only (saved)", "Browser Only (saved) 2", "Browser Only (saved) 3"),
            listOf(first, second, third).map { it.name },
        )
    }

    @Test
    fun `a name the user typed is honoured, and still gets a new id`() {
        // "Save Space..." asks for a name. They named it, so it is theirs - but the id still has to
        // be new, or the file is the legacy shape again.
        val written = saveOf(browserOnly, predefined, requestedName = "Reading")

        assertEquals("Reading", written.name)
        assertTrue(written.id !in PredefinedWorkspaces.allIds)
    }

    // ==================== defect 2: a name is not an identity ====================

    /**
     * Nothing to do with templates: hand-roll a Space called "Codex" and it used to disappear at
     * the next launch, with nothing to say it had happened.
     */
    @Test
    fun `a saved Space named exactly like a built-in survives a reload`() {
        val (fileManager, dir) = tempFileManager()
        val mine = space(id = "workspace-1788000000000", name = "Codex", layout = layout("my own tab"))
        assertNotNull(fileManager.saveWorkspaceBlocking(mine))

        val list = mergeSavedWorkspaces(predefined, reload(fileManager))

        assertEquals(
            listOf("workspace-codex", "workspace-1788000000000"),
            list.filter { it.name == "Codex" }.map { it.id },
            "both the shipped Codex and the user's own, which are different Spaces",
        )
        assertEquals(layout("my own tab"), list.single { it.id == mine.id }.layout)
        dir.deleteRecursively()
    }

    @Test
    fun `an ordinary saved Space is added whatever it is called`() {
        val mine = space(id = "workspace-1788000000000", name = "Scratch")

        val list = mergeSavedWorkspaces(predefined, listOf(mine))

        assertEquals(predefined.size + 1, list.size)
        assertEquals(mine, list.last(), "and it comes after the shipped ones, which is the app's order")
    }

    // ==================== the legacy files that are already on disk ====================

    /**
     * `~/Documents/BOSS/workspaces` on the machine this was written on held four of these -
     * `Browser_Only`, `Claude_Code`, `Code_Review`, `Gemini`, all with real substituted paths and
     * up to six tabs - written by the old auto-save, which used the built-in's own id and name.
     * Every one of them was already being dropped on every launch by the name dedupe.
     *
     * They are ADOPTED rather than dropped, so nothing is lost, and rather than allowed to replace
     * the shipped entry, so the Templates section keeps its tile.
     */
    @Test
    fun `a legacy file carrying a built-in id is adopted beside the shipped layout`() {
        val legacy = space(id = "workspace-gemini", name = "Gemini", layout = layout("a", "b", "c"))

        val list = mergeSavedWorkspaces(predefined, listOf(legacy))

        val shipped = list.single { it.id == "workspace-gemini" }
        assertEquals(predefined.single { it.id == "workspace-gemini" }, shipped, "the template stays pristine")

        val adopted = list.single { it.id == "workspace-gemini-saved" }
        assertEquals("Gemini (saved)", adopted.name)
        assertEquals(layout("a", "b", "c"), adopted.layout, "with the layout the file actually held")
    }

    @Test
    fun `an adopted id is stable across launches, and is not one generateId could mint`() {
        // A generated id here would be a different id for the same file on every launch, so nothing
        // could refer to that Space across a restart - the session set records ids, and so does
        // every preserved-state key.
        val legacy = space(id = "workspace-gemini", name = "Gemini")

        val first = mergeSavedWorkspaces(predefined, listOf(legacy)).single { it.name == "Gemini (saved)" }
        val second = mergeSavedWorkspaces(predefined, listOf(legacy)).single { it.name == "Gemini (saved)" }

        assertEquals(first.id, second.id)
        assertTrue(first.id !in PredefinedWorkspaces.allIds)
        assertTrue(
            !first.id.substringAfter("workspace-").all { it.isDigit() },
            "`generateId()` is workspace-<epoch millis>, so an adopted id must not look like one",
        )
    }

    @Test
    fun `an adopted name that is already taken gets a number`() {
        val legacy = space(id = "workspace-gemini", name = "Gemini")
        val alreadyMine = space(id = "workspace-1788000000000", name = "Gemini (saved)")

        val list = mergeSavedWorkspaces(predefined, listOf(alreadyMine, legacy))

        assertEquals("Gemini (saved) 2", list.single { it.id == "workspace-gemini-saved" }.name)
    }

    // ==================== two files, one id ====================

    @Test
    fun `two saved files claiming one id keep the newer`() {
        // Reachable by hand-copying a file, and reachable through the adoption above: a legacy file
        // adopted under a derived id and then re-saved by the user are two files claiming one id.
        val older = space(id = "workspace-1788000000000", name = "Scratch", layout = layout("old"), timestamp = 1_000)
        val newer = space(id = "workspace-1788000000000", name = "Scratch", layout = layout("new"), timestamp = 9_000)

        assertEquals(layout("new"), mergeSavedWorkspaces(predefined, listOf(older, newer)).last().layout)
        assertEquals(
            layout("new"),
            mergeSavedWorkspaces(predefined, listOf(newer, older)).last().layout,
            "whichever order the directory scan happens to return them in",
        )
    }

    @Test
    fun `a saved file never replaces a shipped layout, whatever its timestamp says`() {
        // The guard against the one thing adoption must never do. A file with a built-in id is
        // adopted, so it cannot reach that slot - and if some other route ever produced one, a
        // future timestamp must not evict the template.
        val list =
            mergeSavedWorkspaces(
                predefined,
                listOf(space(id = "workspace-gemini", name = "Gemini", timestamp = Long.MAX_VALUE)),
            )

        assertEquals(predefined.single { it.id == "workspace-gemini" }, list.single { it.id == "workspace-gemini" })
    }

    /**
     * The same rule inside the manager's own list update, which the survival above exposes: by NAME
     * it replaced the shipped entry for the session, so the Templates section lost a tile until the
     * next launch.
     */
    @Test
    fun `saving updates the entry with the same ID, not the one with the same name`() {
        val shippedCodex = predefined.single { it.id == "workspace-codex" }
        val mine = space(id = "workspace-1788000000000", name = "Codex", layout = layout("mine"))
        val list = (predefined + mine).toMutableList()

        // What saveCurrentWorkspace does after a successful write.
        val saved = mine.copy(layout = layout("mine, edited"), timestamp = 3_000)
        val index = list.indexOfFirst { it.id == saved.id }
        list[index] = saved

        assertEquals(shippedCodex, list.single { it.id == "workspace-codex" }, "the template is untouched")
        assertEquals(layout("mine, edited"), list.single { it.id == mine.id }.layout)
    }

    @Test
    fun `nothing saved leaves exactly the shipped layouts`() {
        assertEquals(predefined, mergeSavedWorkspaces(predefined, emptyList()))
    }

    @Test
    fun `the session set record is not read as a Space`() {
        // It lives in the same directory and the scan is "every *.json". The filter is the
        // manager's; this pins that the file manager really does list it, which is why.
        val (fileManager, dir) = tempFileManager()
        fileManager.writeDocumentBlocking(LAST_SESSION_SET_FILE, "{\"activeWorkspaceId\":\"a\",\"spaces\":[]}")

        assertTrue(runBlocking { fileManager.listWorkspaces() }.any { it.fileName == LAST_SESSION_SET_FILE })
        assertNull(reload(fileManager).firstOrNull(), "and the manager's filter is what keeps it out of the list")
        dir.deleteRecursively()
    }
}
