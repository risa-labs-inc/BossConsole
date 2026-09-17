package ai.rever.boss.service.workspace

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.DeleteWorkspaceRequest
import ai.rever.boss.ipc.proto.services.SaveWorkspaceRequest
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceMutationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test(timeout = 30_000)
    fun queuedCancellationDoesNotMutateStorageOrMemory() =
        runBlocking {
            val root = temporary.newFolder()
            val service = WorkspaceServiceImpl(root).apply { mutationDispatcher = Dispatchers.Unconfined }
            service.mutations.lock()
            val queued = launch(start = CoroutineStart.UNDISPATCHED) { service.saveWorkspace(save("queued")) }
            assertTrue(queued.isActive)
            queued.cancelAndJoin()
            service.mutations.unlock()
            assertFalse(root.resolve("queued.json").exists())
            assertEquals(0, service.getWorkspaces(Empty.getDefaultInstance()).workspacesCount)
            service.saveWorkspace(save("next"))
            assertTrue(root.resolve("next.json").isFile)
        }

    @Test(timeout = 30_000)
    fun admittedCancellationCommitsConsistentlyAndReleasesMutex() =
        runBlocking {
            val root = temporary.newFolder()
            val service = WorkspaceServiceImpl(root).apply { mutationDispatcher = Dispatchers.Unconfined }
            val admitted = launch(start = CoroutineStart.LAZY) { service.saveWorkspace(save("admitted")) }
            service.afterMutationAdmission = { admitted.cancel() }
            admitted.start()
            admitted.join()
            assertFalse(service.mutations.isLocked)
            assertTrue(admitted.isCancelled)
            val reloaded = WorkspaceServiceImpl(root).getWorkspaces(Empty.getDefaultInstance()).workspacesList
            assertEquals(reloaded, service.getWorkspaces(Empty.getDefaultInstance()).workspacesList)
            assertEquals("admitted", reloaded.single().id)
            service.afterMutationAdmission = {}
            service.saveWorkspace(save("next"))
            assertTrue(root.resolve("next.json").isFile)
        }

    @Test
    fun hardLinkedStoredAliasIsRefusedWithExactStatuses() =
        runBlocking {
            val root = temporary.newFolder()
            val service = WorkspaceServiceImpl(root)
            service.saveWorkspace(save("original"))
            val original = root.resolve("original.json")
            val bytes = original.readBytes()
            try {
                Files.createLink(root.resolve("alias.json").toPath(), original.toPath())
            } catch (_: java.io.IOException) {
                org.junit.Assume.assumeTrue("Hard-link fixtures unavailable", false)
            }
            val saveError = assertFailsWith<StatusRuntimeException> { service.saveWorkspace(save("alias")) }
            assertEquals(Status.Code.ALREADY_EXISTS, saveError.status.code)
            val deleteError =
                assertFailsWith<StatusRuntimeException> {
                    service.deleteWorkspace(DeleteWorkspaceRequest.newBuilder().setWorkspaceId("alias").build())
                }
            assertEquals(Status.Code.FAILED_PRECONDITION, deleteError.status.code)
            assertTrue(bytes.contentEquals(original.readBytes()))
            assertTrue(bytes.contentEquals(root.resolve("alias.json").readBytes()))
            val ids = service.getWorkspaces(Empty.getDefaultInstance()).workspacesList.map { it.id }
            assertEquals(listOf("original"), ids)
        }

    @Test
    fun savingExistingRecordPreservesDescriptionAndTabCount() =
        runBlocking {
            val root = temporary.newFolder()
            val saved = """{"id":"saved","name":"old","description":"keep","tabCount":7}"""
            root.resolve("saved.json").writeText(saved)
            val service = WorkspaceServiceImpl(root)
            service.saveWorkspace(save("saved"))
            val record = WorkspaceServiceImpl(root).getWorkspaces(Empty.getDefaultInstance()).workspacesList.single()
            assertEquals("keep", record.description)
            assertEquals(7, record.tabCount)
        }

    private fun save(id: String) =
        SaveWorkspaceRequest
            .newBuilder()
            .setWorkspaceId(id)
            .setName(id)
            .build()
}
