package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.workspace.SplitConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkspaceMutationOrderingTest {
    @TempDir
    lateinit var directory: Path

    private fun layout(
        name: String = "Before",
        description: String = "old",
    ) = LayoutWorkspace(
        id = "ordered-space",
        name = name,
        description = description,
        layout = SplitConfig.SinglePanel(PanelConfig(id = "panel", tabs = emptyList())),
    )

    @Test
    fun `delayed save finishes before rename and rename retains the newly saved content`() =
        runTest {
            val files = WorkspaceFileManager(directory.toString())
            val initial = layout()
            files.saveWorkspace(initial, "Legacy_Name.json")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var writes = 0
            val manager =
                WorkspaceManager(files, backgroundScope, writeWorkspace = { workspace, name ->
                    if (++writes == 1) {
                        entered.complete(Unit)
                        release.await()
                    }
                    files.saveWorkspace(workspace, name)
                })
            manager.awaitLoaded()
            val edited = initial.copy(description = "new tabs")
            manager.loadWorkspace(edited)
            val save = async(start = CoroutineStart.UNDISPATCHED) { manager.saveWorkspaceAwait(edited) }
            entered.await()
            val rename =
                async(start = CoroutineStart.UNDISPATCHED) {
                    manager.renameWorkspaceByIdAwait(initial.id, "Renamed")
                }
            runCurrent()
            assertFalse(save.isCompleted)
            assertFalse(rename.isCompleted, "Rename must not overtake the admitted save")
            release.complete(Unit)
            assertTrue(save.await().isSuccess)
            assertTrue(rename.await().isSuccess)
            val disk = files.loadWorkspace("Legacy_Name.json")!!
            assertEquals("Renamed", disk.name)
            assertEquals("new tabs", disk.description)
            assertEquals(disk, manager.savedCopyOf(initial.id))
            assertEquals("Renamed", manager.currentWorkspace.value?.name)
            assertEquals(listOf("Legacy_Name.json"), files.listWorkspaces().map { it.fileName })
        }

    @Test
    fun `delete after a delayed save cannot be resurrected when that save finishes`() =
        runTest {
            val files = WorkspaceFileManager(directory.toString())
            val initial = layout()
            val fileName = WorkspaceFileManagerCommon.fileNameForId(initial.id)
            files.saveWorkspace(initial, fileName)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val manager =
                WorkspaceManager(files, backgroundScope, writeWorkspace = { workspace, name ->
                    entered.complete(Unit)
                    release.await()
                    files.saveWorkspace(workspace, name)
                })
            manager.awaitLoaded()
            manager.loadWorkspace(initial)
            var deleted = 0
            manager.setOnWorkspaceDeleted { deleted++ }
            val save = async(start = CoroutineStart.UNDISPATCHED) { manager.saveWorkspaceAwait(initial) }
            entered.await()
            val delete = async(start = CoroutineStart.UNDISPATCHED) { manager.deleteWorkspaceByIdAwait(initial.id) }
            runCurrent()
            assertFalse(delete.isCompleted)
            release.complete(Unit)
            assertTrue(save.await().isSuccess)
            assertTrue(delete.await().isSuccess)
            assertNull(files.loadWorkspace(fileName))
            assertNull(manager.savedCopyOf(initial.id))
            assertNull(manager.currentWorkspace.value)
            assertEquals(1, deleted)
        }

    @Test
    fun `delayed save must not reselect after A to B to same A object`() =
        runTest {
            val files = WorkspaceFileManager(directory.toString())
            val initial = layout()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val manager =
                WorkspaceManager(files, backgroundScope, writeWorkspace = { workspace, name ->
                    entered.complete(Unit)
                    release.await()
                    files.saveWorkspace(workspace, name)
                })
            manager.awaitLoaded()
            manager.loadWorkspace(initial)
            val save = async(start = CoroutineStart.UNDISPATCHED) { manager.saveWorkspaceAwait(initial, "Saved name") }
            entered.await()
            manager.loadWorkspace(initial.copy(id = "other", name = "Other"))
            manager.loadWorkspace(initial)
            release.complete(Unit)
            assertTrue(save.await().isSuccess)
            assertEquals(initial, manager.currentWorkspace.value)
            assertEquals("Saved name", manager.savedCopyOf(initial.id)?.name)
        }

    @Test
    fun `live edits during save and rename are not replaced by the persisted snapshot`() =
        runTest {
            val files = WorkspaceFileManager(directory.toString())
            val initial = layout()
            files.saveWorkspace(initial)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val manager =
                WorkspaceManager(files, backgroundScope, writeWorkspace = { workspace, name ->
                    entered.complete(Unit)
                    release.await()
                    files.saveWorkspace(workspace, name)
                })
            manager.awaitLoaded()
            manager.loadWorkspace(initial)
            val save = async(start = CoroutineStart.UNDISPATCHED) { manager.saveWorkspaceAwait(initial) }
            entered.await()
            val latest = initial.copy(description = "unsaved live edit")
            manager.updateCurrentWorkspace(latest)
            release.complete(Unit)
            assertTrue(save.await().isSuccess)
            assertEquals(latest, manager.currentWorkspace.value)
            assertTrue(manager.renameWorkspaceByIdAwait(initial.id, "Renamed").isSuccess)
            assertEquals("unsaved live edit", manager.currentWorkspace.value?.description)
            assertEquals("Renamed", manager.currentWorkspace.value?.name)
            assertEquals("old", manager.savedCopyOf(initial.id)?.description)
        }

    @Test
    fun `failed write and delete preserve committed disk and state and do not poison later requests`() =
        runTest {
            val files = WorkspaceFileManager(directory.toString())
            val initial = layout()
            val fileName = WorkspaceFileManagerCommon.fileNameForId(initial.id)
            files.saveWorkspace(initial, fileName)
            var failWrite = true
            var failDelete = true
            val manager =
                WorkspaceManager(
                    files,
                    backgroundScope,
                    writeWorkspace = { workspace, name ->
                        if (failWrite) null else files.saveWorkspace(workspace, name)
                    },
                    removeWorkspace = { name -> !failDelete && files.deleteWorkspace(name) },
                )
            manager.awaitLoaded()
            manager.loadWorkspace(initial)
            assertTrue(manager.saveWorkspaceAwait(initial.copy(description = "lost")).isFailure)
            assertTrue(manager.renameWorkspaceByIdAwait(initial.id, "Failed rename").isFailure)
            assertTrue(manager.deleteWorkspaceByIdAwait(initial.id).isFailure)
            assertEquals(initial, files.loadWorkspace(fileName))
            assertEquals(initial, manager.savedCopyOf(initial.id))
            assertEquals(initial, manager.currentWorkspace.value)
            failWrite = false
            assertTrue(manager.renameWorkspaceByIdAwait(initial.id, "Recovered").isSuccess)
            failDelete = false
            assertTrue(manager.deleteWorkspaceByIdAwait(initial.id).isSuccess)
            assertNull(files.loadWorkspace(fileName))
        }

    @Test
    fun `cancelling the save awaiter does not release the ordered writer early`() =
        runTest {
            val files = WorkspaceFileManager(directory.toString())
            val initial = layout()
            files.saveWorkspace(initial)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val manager =
                WorkspaceManager(files, backgroundScope, writeWorkspace = { workspace, name ->
                    entered.complete(Unit)
                    release.await()
                    files.saveWorkspace(workspace, name)
                })
            manager.awaitLoaded()
            val save =
                async(start = CoroutineStart.UNDISPATCHED) {
                    manager.saveWorkspaceAwait(initial.copy(description = "accepted"))
                }
            entered.await()
            save.cancelAndJoin()
            val rename =
                async(start = CoroutineStart.UNDISPATCHED) {
                    manager.renameWorkspaceByIdAwait(initial.id, "After")
                }
            runCurrent()
            assertFalse(rename.isCompleted)
            release.complete(Unit)
            assertTrue(rename.await().isSuccess)
            assertEquals("accepted", manager.savedCopyOf(initial.id)?.description)
            assertEquals("After", manager.savedCopyOf(initial.id)?.name)
        }

    @Test
    fun `save queued after rename preserves renamed metadata and its own new layout`() =
        runTest {
            val files = WorkspaceFileManager(directory.toString())
            val initial = layout()
            files.saveWorkspace(initial)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var writes = 0
            val manager =
                WorkspaceManager(files, backgroundScope, writeWorkspace = { workspace, name ->
                    if (++writes == 1) {
                        entered.complete(Unit)
                        release.await()
                    }
                    files.saveWorkspace(workspace, name)
                })
            manager.awaitLoaded()
            val rename =
                async(start = CoroutineStart.UNDISPATCHED) {
                    manager.renameWorkspaceByIdAwait(initial.id, "New name")
                }
            entered.await()
            val save =
                async(start = CoroutineStart.UNDISPATCHED) {
                    manager.saveWorkspaceAwait(initial.copy(description = "new layout"))
                }
            release.complete(Unit)
            assertTrue(rename.await().isSuccess)
            val committed = save.await().getOrThrow()
            assertEquals("New name", committed.name)
            assertEquals("new layout", committed.description)
            assertEquals(committed, manager.savedCopyOf(initial.id))
        }

    @Test
    fun `legacy save submitted before startup settles uses loaded filename and cannot undo delete`() =
        runTest {
            val files = WorkspaceFileManager(directory.toString())
            val initial = layout()
            files.saveWorkspace(initial, "Legacy.json")
            val manager = WorkspaceManager(files, backgroundScope)
            manager.loadWorkspace(initial.copy(description = "new layout"))
            assertTrue(manager.saveCurrentWorkspace() != null)
            // Both actions are admitted before the asynchronous seed can publish. They must
            // resolve that seed's legacy filename, not create a second id-derived file.
            assertTrue(manager.deleteWorkspaceByIdAwait(initial.id).isSuccess)
            assertEquals(emptyList(), files.listWorkspaces())
            assertNull(manager.savedCopyOf(initial.id))
            assertNull(manager.currentWorkspace.value)
        }

    @Test
    fun `delete remains successful when cleanup callback throws and later rename cannot resurrect it`() =
        runTest {
            val files = WorkspaceFileManager(directory.toString())
            val initial = layout()
            files.saveWorkspace(initial)
            val manager = WorkspaceManager(files, backgroundScope)
            manager.awaitLoaded()
            manager.loadWorkspace(initial)
            manager.setOnWorkspaceDeleted { error("cleanup failed") }
            assertTrue(manager.deleteWorkspaceByIdAwait(initial.id).isSuccess)
            assertTrue(manager.renameWorkspaceByIdAwait(initial.id, "Must not return").isFailure)
            assertNull(manager.savedCopyOf(initial.id))
            assertNull(manager.currentWorkspace.value)
            assertEquals(emptyList(), files.listWorkspaces())
        }

    @Test
    fun `two queued saves from a slot allocate names against earlier committed copies`() =
        runTest {
            val files = WorkspaceFileManager(directory.toString())
            val slot = layout().copy(id = LAST_SESSION_ID, name = LAST_SESSION_NAME)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var writes = 0
            val manager =
                WorkspaceManager(files, backgroundScope, writeWorkspace = { workspace, name ->
                    if (++writes == 1) {
                        entered.complete(Unit)
                        release.await()
                    }
                    files.saveWorkspace(workspace, name)
                })
            manager.awaitLoaded()
            val first = async(start = CoroutineStart.UNDISPATCHED) { manager.saveWorkspaceAwait(slot, "My Space") }
            entered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { manager.saveWorkspaceAwait(slot, "My Space") }
            release.complete(Unit)
            val copies = listOf(first.await().getOrThrow(), second.await().getOrThrow())
            assertEquals(setOf("My Space", "My Space 2"), copies.map { it.name }.toSet())
            assertEquals(2, copies.map { it.id }.toSet().size)
            assertEquals(2, files.listWorkspaces().size)
        }

    @Test
    fun `request cancellation does not shut down the writer for later mutations`() =
        runTest {
            val files = WorkspaceFileManager(directory.toString())
            val initial = layout()
            files.saveWorkspace(initial)
            var cancelWrite = true
            val manager =
                WorkspaceManager(files, backgroundScope, writeWorkspace = { workspace, name ->
                    if (cancelWrite) throw CancellationException("Request cancelled by backend")
                    files.saveWorkspace(workspace, name)
                })
            manager.awaitLoaded()
            assertFailsWith<CancellationException> { manager.saveWorkspaceAwait(initial) }
            assertEquals(initial, manager.savedCopyOf(initial.id))
            cancelWrite = false
            assertTrue(manager.renameWorkspaceByIdAwait(initial.id, "Retry").isSuccess)
            assertEquals("Retry", manager.savedCopyOf(initial.id)?.name)
        }

    @Test
    fun `blocking Last Session selection fences an older queued saved copy`() =
        runTest {
            val files = WorkspaceFileManager(directory.toString())
            val slot = layout().copy(id = LAST_SESSION_ID, name = LAST_SESSION_NAME)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val manager =
                WorkspaceManager(files, backgroundScope, writeWorkspace = { workspace, name ->
                    entered.complete(Unit)
                    release.await()
                    files.saveWorkspace(workspace, name)
                })
            manager.awaitLoaded()
            manager.loadWorkspace(slot)
            val save = async(start = CoroutineStart.UNDISPATCHED) { manager.saveWorkspaceAwait(slot, "Saved copy") }
            entered.await()
            assertTrue(manager.saveLastSessionBlocking(slot.copy(description = "new session")))
            val selected = manager.currentWorkspace.value
            release.complete(Unit)
            assertTrue(save.await().isSuccess)
            assertEquals(selected, manager.currentWorkspace.value)
            assertEquals(LAST_SESSION_ID, manager.currentWorkspace.value?.id)
        }
}
