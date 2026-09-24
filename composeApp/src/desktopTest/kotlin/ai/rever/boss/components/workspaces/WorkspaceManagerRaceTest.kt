package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig
import ai.rever.boss.plugin.workspace.TabConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * WorkspaceManager's mutation invariants, under concurrent issue.
 *
 * **The contract under test:** a mutation - save, import, rename or delete - commits its disk
 * file and its registry rows as ONE serialized step, so no two can interleave. Before the
 * serialization existed each mutation was a fire-and-forget coroutine doing its disk I/O on the
 * multi-threaded IO pool and updating the registry on its own afterwards, which meant a delete
 * issued alongside a save or rename of the same Space could leave the picker listing a Space
 * whose file was gone, or a file on disk no registry row pointed at - a "deleted" Space that
 * came back on the next launch, or a listed one that silently vanished. An import whose write
 * failed still listed itself. Two renames onto one name both passed the taken check before
 * either landed.
 *
 * Each test issues overlapping mutations back to back - the issuing itself is the race - then
 * waits for every queued mutation to finish and asserts invariants that must hold under EVERY
 * possible interleaving: exactly one row, exactly one file, the two always agreeing.
 *
 * A real [WorkspaceFileManager] on a scratch directory, because the defect lives in the seam
 * between writing and reading, not in either half alone. Main is pinned to an unconfined test
 * dispatcher so a launched mutation runs until its first suspension (the lock, or the IO hop)
 * right at issue time, which is what makes the interleaving adversarial rather than accidental.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceManagerRaceTest {
    private fun layout(vararg titles: String) =
        SplitConfig.SinglePanel(
            PanelConfig(id = "main", tabs = titles.map { TabConfig(type = "terminal", title = it) }),
        )

    private fun space(
        id: String,
        name: String,
    ) = LayoutWorkspace(id = id, name = name, description = "race", layout = layout("one"), timestamp = 1_000)

    /**
     * A manager on a scratch directory. Main is pinned BEFORE construction so the manager's own
     * scope uses it, and the constructor's initial scan is waited out first: it replaces the
     * whole list when it lands, so rows added before it would be clobbered by the very launch
     * the constructor fires.
     */
    private fun managerOn(directory: File): WorkspaceManager {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val manager = WorkspaceManager(directory.absolutePath)
        runBlocking { manager.workspaces.first { it.isNotEmpty() } }
        return manager
    }

    /**
     * Wait for every mutation issued so far to finish committing: the lock is FIFO, so taking
     * it means everything queued ahead of this point has completed its disk write AND its
     * registry update.
     */
    private fun settle(manager: WorkspaceManager) {
        runBlocking { manager.mutations.withLock { } }
    }

    private fun fileFor(
        directory: File,
        workspaceId: String,
    ) = File(directory, WorkspaceFileManagerCommon.fileNameForId(workspaceId))

    @Test
    fun `two saves of one Space land as one coherent record`() {
        val directory = Files.createTempDirectory("workspace-race").toFile()
        try {
            val manager = managerOn(directory)
            val alpha = space(LayoutWorkspace.generateId(), "Alpha")
            manager.registerWorkspace(alpha)
            manager.loadWorkspace(alpha)
            // The same Space saved twice back to back, the way a double click on Save does it.
            manager.saveCurrentWorkspace(name = "first")
            manager.saveCurrentWorkspace(name = "second")
            settle(manager)

            val row = manager.workspaces.value.single { it.id == alpha.id }
            assertTrue(row.name in setOf("first", "second"), "both saves wrote this one Space")
            val record = fileFor(directory, alpha.id)
            assertTrue(record.isFile, "the row must have exactly one file behind it")
            assertEquals(row.name, WorkspaceSerializer.deserialize(record.readText()).name)
            assertEquals(
                0,
                directory.listFiles { file -> file.name.endsWith(".tmp") }?.size ?: 0,
                "no torn-write temp file may survive a save",
            )
        } finally {
            Dispatchers.resetMain()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a save racing a delete never leaves the registry and disk disagreeing`() {
        val directory = Files.createTempDirectory("workspace-race").toFile()
        try {
            val manager = managerOn(directory)
            val alpha = space(LayoutWorkspace.generateId(), "Alpha")
            manager.registerWorkspace(alpha)
            manager.loadWorkspace(alpha)
            manager.saveCurrentWorkspace()
            settle(manager) // the Space now exists as a file plus a row

            // Both issued while the other is still in flight: the adversarial window.
            manager.deleteWorkspaceById(alpha.id)
            manager.saveCurrentWorkspace(name = "Kept")
            settle(manager)

            val row = manager.workspaces.value.firstOrNull { it.id == alpha.id }
            val record = fileFor(directory, alpha.id)
            // Whichever mutation was admitted last wins, but BOTH halves of the outcome - the
            // row and the file - must agree on which one that is.
            assertEquals(
                row != null,
                record.isFile,
                "the registry lists the Space exactly when its file is on disk",
            )
            if (row != null) {
                assertEquals(row.name, WorkspaceSerializer.deserialize(record.readText()).name)
                assertEquals(row.id, manager.currentWorkspace.value?.id)
            } else {
                assertNull(manager.currentWorkspace.value?.takeIf { it.id == alpha.id })
            }
        } finally {
            Dispatchers.resetMain()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `two renames onto one name leave exactly one Space with that name`() {
        val directory = Files.createTempDirectory("workspace-race").toFile()
        try {
            val manager = managerOn(directory)
            val alpha = space("race-alpha", "Alpha")
            val beta = space("race-beta", "Beta")
            manager.registerWorkspace(alpha)
            manager.loadWorkspace(alpha)
            manager.saveCurrentWorkspace()
            manager.registerWorkspace(beta)
            manager.loadWorkspace(beta)
            manager.saveCurrentWorkspace()
            settle(manager) // both exist as a file plus a row

            manager.renameWorkspaceById(alpha.id, "Shared")
            manager.renameWorkspaceById(beta.id, "Shared")
            settle(manager)

            val shared = manager.workspaces.value.filter { isUserOwnedSpace(it.id) && it.name == "Shared" }
            assertEquals(1, shared.size, "one name, one Space: the second rename must see the first")
            val winner = shared.single()
            val loserId = if (winner.id == alpha.id) beta.id else alpha.id
            val loser = manager.workspaces.value.single { it.id == loserId }
            assertEquals(if (loserId == alpha.id) "Alpha" else "Beta", loser.name)
            // Both halves of each rename agree: the winner's file carries its new name, the
            // refused one's carries the name it still has.
            assertEquals("Shared", WorkspaceSerializer.deserialize(fileFor(directory, winner.id).readText()).name)
            assertEquals(loser.name, WorkspaceSerializer.deserialize(fileFor(directory, loserId).readText()).name)
        } finally {
            Dispatchers.resetMain()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `an import whose write fails leaves no registry row`() {
        val blocker = Files.createTempFile("workspace-race-blocker", ".json").toFile()
        try {
            // A REGULAR FILE where the workspace directory would be: every write into it fails.
            val manager = managerOn(blocker)
            val alpha = space(LayoutWorkspace.generateId(), "Imported")
            val imported = manager.importWorkspace(WorkspaceSerializer.serialize(alpha))
            assertNotNull(imported, "deserializing the Space is not what this test breaks")
            settle(manager)
            assertNull(
                manager.workspaces.value.firstOrNull { isUserOwnedSpace(it.id) && it.id == alpha.id },
                "a row must not list a Space whose file could not be written: it would silently " +
                    "vanish on the next launch",
            )
        } finally {
            Dispatchers.resetMain()
            blocker.deleteRecursively()
        }
    }

    /**
     * The startup scan is the other racer, and the lock orders it rather than shielding it: the
     * scan reads disk WITHOUT the lock and only its publish takes it, so a mutation that lands
     * while the scan is reading - an MCP `registerWorkspace`, whose file the scan never looks at,
     * or a save - must survive the publish. Before the publish merged, it replaced the list
     * wholesale and the register's row was lost for good.
     *
     * The interleaving is forced, not hoped for: Main is pinned before construction so the
     * manager's own scan coroutine runs until its first IO hop and suspends there, and each
     * seeded file costs the scan one further IO round-trip - so the register, which commits
     * inline on the test thread with no IO at all, lands while the scan is provably still
     * reading.
     */
    @Test
    fun `a register and a save landing mid-scan survive the publish`() {
        val directory = Files.createTempDirectory("workspace-race").toFile()
        try {
            // Forty-one saved Spaces: one the scan must find, plus enough filler that its read
            // phase outlasts the mutations issued the instant the constructor returns.
            val alpha = space("race-mid-scan-alpha", "Alpha")
            fileFor(directory, alpha.id).writeText(WorkspaceSerializer.serialize(alpha))
            repeat(40) { index ->
                val filler = space("race-mid-scan-filler-$index", "Filler $index")
                fileFor(directory, filler.id).writeText(WorkspaceSerializer.serialize(filler))
            }

            Dispatchers.setMain(UnconfinedTestDispatcher())
            val manager = WorkspaceManager(directory.absolutePath)
            // From here the scan is in flight: it has listed the directory and is reading the
            // files one IO hop at a time, nowhere near the publish.

            // The MCP create_workspace door - a row whose file the scan never sees.
            val beta = space("race-mid-scan-beta", "Beta")
            manager.registerWorkspace(beta)
            assertTrue(
                manager.workspaces.value.any { it.id == beta.id },
                "the register must commit before the scan publishes, or this is not the mid-scan " +
                    "race the fix is for",
            )

            // And a save of that same Space, issued while the scan is still reading.
            manager.loadWorkspace(beta)
            manager.saveCurrentWorkspace(name = "Kept")

            // Only the publish lists the shipped layouts, so waiting for one is waiting for the
            // scan's result to land. The save holds the lock across its write, so the publish
            // runs after it.
            runBlocking {
                manager.workspaces.first { list -> list.any { it.id in PredefinedWorkspaces.allIds } }
            }
            settle(manager)

            assertEquals(
                "Kept",
                manager.workspaces.value
                    .single { it.id == beta.id }
                    .name,
                "the register and the save made while the scan was reading must survive the " +
                    "publish, not be overwritten by its wholesale result",
            )
            assertTrue(
                manager.workspaces.value.any { it.id == alpha.id },
                "what the scan found must still be listed after the merge",
            )
        } finally {
            Dispatchers.resetMain()
            directory.deleteRecursively()
        }
    }
}
