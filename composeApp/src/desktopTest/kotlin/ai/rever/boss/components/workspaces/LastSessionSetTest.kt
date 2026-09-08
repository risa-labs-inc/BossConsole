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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The multi-Space session record: when one is written, what order it comes back in, and that it
 * lives beside the single-Space file rather than replacing it.
 *
 * The pure rules are [sessionSetOf], [restoreOrder] and [isRestorable]; the file round trip is
 * driven through a real [WorkspaceFileManager] pointed at a temp directory, because the two things
 * most likely to go wrong about a new file are its NAME (the manager's Space scan reads every
 * `*.json` in that directory) and its removal (a stale set would be restored in preference to the
 * truth).
 */
class LastSessionSetTest {
    private fun space(
        id: String,
        name: String = id,
        tab: String = id,
    ) = LayoutWorkspace(
        id = id,
        name = name,
        description = "d",
        layout =
            SplitConfig.SinglePanel(
                PanelConfig(id = "panel-$id", tabs = listOf(TabConfig(type = "terminal", title = tab))),
            ),
        timestamp = 1_700_000_000_000,
        projectPath = "/tmp/proj",
    )

    private fun tempManager(): Pair<WorkspaceFileManager, File> {
        val dir = Files.createTempDirectory("last-session-set").toFile()
        return WorkspaceFileManager(dir.absolutePath) to dir
    }

    // ==================== when a set is written at all ====================

    @Test
    fun `two or more running Spaces make a set`() {
        val set = sessionSetOf(listOf(space("a"), space("b")), activeWorkspaceId = "b")

        assertNotNull(set)
        assertEquals("b", set.activeWorkspaceId)
        assertEquals(listOf("a", "b"), set.spaces.map { it.id })
    }

    @Test
    fun `one running Space writes no set`() {
        // Not a gap: `Last_Session.json` already records exactly this, and a second file saying
        // the same thing is a second thing that can disagree with it.
        assertNull(sessionSetOf(listOf(space("a")), activeWorkspaceId = "a"))
        assertNull(sessionSetOf(emptyList(), activeWorkspaceId = null))
    }

    @Test
    fun `a set whose active Space is not in it is refused`() {
        // There would be nothing to show on restore, and guessing - the first, the last - puts a
        // Space on screen the user was not looking at. The single-Space file records the one that
        // WAS, so falling back to it is the honest answer.
        assertNull(sessionSetOf(listOf(space("a"), space("b")), activeWorkspaceId = "c"))
        assertNull(sessionSetOf(listOf(space("a"), space("b")), activeWorkspaceId = null))
    }

    @Test
    fun `restorable asks the same question on the way back in`() {
        // A file with one Space in it - hand-edited, or written by some other version - says
        // nothing the single-Space record does not.
        assertTrue(isRestorable(LastSessionSet("b", listOf(space("a"), space("b")))))
        assertFalse(isRestorable(null))
        assertFalse(isRestorable(LastSessionSet("a", listOf(space("a")))))
        assertFalse(isRestorable(LastSessionSet("c", listOf(space("a"), space("b")))))
    }

    // ==================== the order it comes back in ====================

    @Test
    fun `the Space that was showing is restored LAST`() {
        // Applying a Space replaces what is on screen, so the last apply is what is left showing.
        // Restore it first and the window comes up on a Space the user was not looking at.
        val set = LastSessionSet("a", listOf(space("a"), space("b"), space("c")))

        assertEquals(listOf("b", "c", "a"), restoreOrder(set).map { it.id })
    }

    @Test
    fun `an active Space already last stays last, and nothing else moves`() {
        val set = LastSessionSet("c", listOf(space("a"), space("b"), space("c")))

        assertEquals(listOf("a", "b", "c"), restoreOrder(set).map { it.id })
    }

    @Test
    fun `every Space is restored exactly once`() {
        // The filter-twice shape would duplicate or drop an entry if the predicates ever stopped
        // being complements, and a duplicated Space would be applied twice over itself.
        val set = LastSessionSet("b", listOf(space("a"), space("b"), space("c"), space("d")))

        assertEquals(set.spaces.map { it.id }.sorted(), restoreOrder(set).map { it.id }.sorted())
        assertEquals(set.spaces.size, restoreOrder(set).size)
    }

    // ==================== the file ====================

    @Test
    fun `a set survives a JSON round trip`() {
        val set = LastSessionSet("b", listOf(space("a", name = "Alpha"), space("b", name = "Beta")))

        val reloaded = LastSessionSetSerializer.deserialize(LastSessionSetSerializer.serialize(set))

        assertEquals(set, reloaded)
    }

    @Test
    fun `the set file is written beside the Spaces and is not read as one`() {
        val (fileManager, dir) = tempManager()
        val set = LastSessionSet("b", listOf(space("a"), space("b")))

        // A real Space, and the set, in one directory - which is the arrangement on a user's disk.
        val savedSpace = fileManager.saveWorkspaceBlocking(space("a", name = "Alpha"))
        val savedSet = fileManager.writeDocumentBlocking(LAST_SESSION_SET_FILE, LastSessionSetSerializer.serialize(set))

        assertNotNull(savedSpace)
        assertTrue(savedSet)
        assertTrue(File(fileManager.getWorkspaceFilePath(LAST_SESSION_SET_FILE)).exists())

        val listed = runBlocking { fileManager.listWorkspaces() }.map { it.fileName }
        assertTrue(
            LAST_SESSION_SET_FILE in listed,
            "the Space scan is 'every *.json' in the directory, so the set IS listed: $listed - " +
                "which is exactly why WorkspaceManager skips it by name",
        )

        val reloaded = runBlocking { fileManager.loadDocument(LAST_SESSION_SET_FILE) }
        assertEquals(set, LastSessionSetSerializer.deserialize(reloaded!!))

        dir.deleteRecursively()
    }

    @Test
    fun `deleting the set leaves the Spaces alone, and a missing set deletes cleanly`() {
        val (fileManager, dir) = tempManager()
        fileManager.saveWorkspaceBlocking(space("a", name = "Alpha"))
        fileManager.writeDocumentBlocking(LAST_SESSION_SET_FILE, "{}")

        assertTrue(fileManager.writeDocumentBlocking(LAST_SESSION_SET_FILE, content = null))
        assertFalse(File(fileManager.getWorkspaceFilePath(LAST_SESSION_SET_FILE)).exists())
        assertTrue(
            File(fileManager.getWorkspaceFilePath(WorkspaceFileManagerCommon.generateFileName("Alpha"))).exists(),
            "deleting the session record must not touch a saved Space",
        )
        assertTrue(
            fileManager.writeDocumentBlocking(LAST_SESSION_SET_FILE, content = null),
            "absent is success: the caller wants it gone, and it is",
        )

        dir.deleteRecursively()
    }

    @Test
    fun `an unreadable set file is null rather than a failed launch`() {
        val (fileManager, dir) = tempManager()
        fileManager.writeDocumentBlocking(LAST_SESSION_SET_FILE, "{ this is not json")

        val json = runBlocking { fileManager.loadDocument(LAST_SESSION_SET_FILE) }
        assertNotNull(json)
        val parsed = runCatching { LastSessionSetSerializer.deserialize(json) }.getOrNull()
        assertNull(parsed, "a truncated record must fall back to the single-Space restore")
        assertFalse(isRestorable(parsed))

        dir.deleteRecursively()
    }

    @Test
    fun `a set written by a newer build still reads`() {
        // `ignoreUnknownKeys`, the same setting WorkspaceSerializer carries, so a field added
        // later does not make an installed build refuse the record it is looking at.
        val json =
            """
            {
              "activeWorkspaceId": "b",
              "someFutureField": 3,
              "spaces": [
                { "id": "a", "name": "a", "description": "d",
                  "layout": { "type": "ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel",
                              "panel": { "id": "p", "tabs": [] } } },
                { "id": "b", "name": "b", "description": "d",
                  "layout": { "type": "ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel",
                              "panel": { "id": "p", "tabs": [] } } }
              ]
            }
            """.trimIndent()

        val set = LastSessionSetSerializer.deserialize(json)

        assertEquals("b", set.activeWorkspaceId)
        assertEquals(listOf("a", "b"), set.spaces.map { it.id })
    }
}
