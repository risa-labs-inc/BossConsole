package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.plugin.workspace.TabConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the "Last Session" shutdown save (Issue #19).
 *
 * Window dispose used to `runBlocking` around [WorkspaceManager.saveCurrentWorkspace],
 * which is fire-and-forget on a `Dispatchers.Main` scope — so the wrapper awaited
 * nothing and the write could be dropped entirely when the process exited first.
 * The shutdown path now writes synchronously, and atomically.
 *
 * The workspace directory is injected, so these tests never write to the real
 * `~/Documents/BOSS/workspaces`.
 */
class LastSessionSaveTest {
    @TempDir
    lateinit var workspaceDir: Path

    private fun fileManager() = WorkspaceFileManager(workspaceDir.toFile().absolutePath)

    private fun layout(
        name: String,
        tabTitle: String = "Terminal",
    ) = LayoutWorkspace(
        id = "some-other-id",
        name = name,
        description = "whatever",
        layout =
            SplitConfig.SinglePanel(
                panel =
                    PanelConfig(
                        id = "panel-1",
                        tabs = listOf(TabConfig(type = "terminal", title = tabTitle)),
                    ),
            ),
    )

    @Test
    fun `asLastSession stamps the shared Last Session identity`() {
        val stamped = asLastSession(layout("Some Window Layout"))

        assertEquals(LAST_SESSION_ID, stamped.id)
        assertEquals(LAST_SESSION_NAME, stamped.name)
        assertEquals("Automatically saved session", stamped.description)
        // Layout content is preserved untouched.
        assertEquals(layout("Some Window Layout").layout, stamped.layout)
    }

    /**
     * The load-bearing property for shutdown: when the call returns, the bytes are
     * already on disk. No coroutine, no scope the process can outrun.
     */
    @Test
    fun `saveWorkspaceBlocking has written the file by the time it returns`() {
        val path = fileManager().saveWorkspaceBlocking(asLastSession(layout("Test Layout")))

        assertTrue(path != null, "Blocking save should report the written path")
        val file = File(path!!)
        assertTrue(file.exists(), "File must exist immediately after the call returns")
        assertTrue(file.length() > 0, "File must have content immediately after the call returns")

        val reloaded = WorkspaceSerializer.deserialize(file.readText())
        assertEquals(LAST_SESSION_ID, reloaded.id)
        assertEquals(LAST_SESSION_NAME, reloaded.name)
    }

    /**
     * The default filename derivation is the load-bearing equivalence in this
     * refactor: the shutdown save must overwrite the same "Last Session" file the
     * debounced auto-save writes, not accumulate a second one.
     *
     * **The derivation is now the ID, not the name** - see
     * `WorkspaceFileManagerCommon.fileNameForId`. What this test is about is unchanged and is the
     * reason it still matters: two writes of one record land on ONE file. The record's id is a
     * constant (`last-session`), so it derives one path just as its name did, and a machine with a
     * legacy `Last_Session.json` keeps writing into that file because `WorkspaceManager` remembers
     * the path each Space was loaded from.
     */
    @Test
    fun `saving Last Session twice overwrites one file rather than adding another`() {
        val manager = fileManager()
        manager.saveWorkspaceBlocking(asLastSession(layout("first", tabTitle = "First")))
        manager.saveWorkspaceBlocking(asLastSession(layout("second", tabTitle = "Second")))

        val files =
            workspaceDir
                .toFile()
                .listFiles()
                ?.toList()
                .orEmpty()
        assertEquals(
            1,
            files.size,
            "Expected exactly one Last Session file, found: ${files.map { it.name }}",
        )
        assertEquals(
            WorkspaceFileManagerCommon.fileNameForId(LAST_SESSION_ID),
            files.single().name,
            "a fresh directory gets the id-derived path; a legacy file keeps its own",
        )

        val reloaded = WorkspaceSerializer.deserialize(files.single().readText())
        val panel = reloaded.layout as SplitConfig.SinglePanel
        assertEquals(
            "Second",
            panel.panel.tabs
                .single()
                .title,
            "The later save should win",
        )
    }

    /**
     * Atomicity: the write goes to a unique temp sibling and is renamed into place,
     * so a kill mid-write leaves the previous (valid) file rather than a truncated
     * one. Nothing may be left behind on the happy path either.
     */
    @Test
    fun `the workspace write leaves no temp files behind`() {
        val manager = fileManager()
        manager.saveWorkspaceBlocking(asLastSession(layout("first")))
        manager.saveWorkspaceBlocking(asLastSession(layout("second")))

        val leftovers =
            workspaceDir
                .toFile()
                .listFiles()
                ?.filter { it.name.endsWith(".tmp") }
                .orEmpty()
        assertTrue(
            leftovers.isEmpty(),
            "Atomic write should clean up its temp files, found: ${leftovers.map { it.name }}",
        )
    }

    /**
     * A saved session must still deserialize after being overwritten — the property
     * a truncated in-place write would break.
     */
    @Test
    fun `an overwritten Last Session still deserializes`() {
        val manager = fileManager()
        manager.saveWorkspaceBlocking(asLastSession(layout("first", tabTitle = "A".repeat(4096))))
        val path = manager.saveWorkspaceBlocking(asLastSession(layout("second", tabTitle = "Short")))

        val reloaded = WorkspaceSerializer.deserialize(File(path!!).readText())
        val panel = reloaded.layout as SplitConfig.SinglePanel
        assertEquals(
            "Short",
            panel.panel.tabs
                .single()
                .title,
        )
    }
}
