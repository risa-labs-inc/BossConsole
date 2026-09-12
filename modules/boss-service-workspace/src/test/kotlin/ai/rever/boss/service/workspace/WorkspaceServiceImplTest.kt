package ai.rever.boss.service.workspace

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.DeleteWorkspaceRequest
import ai.rever.boss.ipc.proto.services.LoadWorkspaceRequest
import ai.rever.boss.ipc.proto.services.SaveWorkspaceRequest
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceServiceImplTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun rejectsUnsafeIdsBeforeChangingMemoryOrDisk() =
        runBlocking {
            val root = temporary.newFolder("workspaces")
            val outside = temporary.newFile("outside.json").also { it.writeText("sentinel") }
            val service = WorkspaceServiceImpl(root)
            val unsafeIds =
                listOf(
                    "",
                    "../outside",
                    "..",
                    ".",
                    "a/b",
                    "a\\b",
                    "/absolute",
                    "C:outside",
                    "a\u0000b",
                    "trailing.",
                    " ",
                    "a".repeat(201),
                    "\\\\server\\share",
                    "CON",
                    "nul.txt",
                    "LPT9",
                )
            for (id in unsafeIds) {
                val save =
                    assertFailsWith<StatusRuntimeException> {
                        service.saveWorkspace(SaveWorkspaceRequest.newBuilder().setWorkspaceId(id).build())
                    }
                assertEquals(Status.Code.INVALID_ARGUMENT, save.status.code)
                assertFailsWith<StatusRuntimeException> {
                    service.deleteWorkspace(DeleteWorkspaceRequest.newBuilder().setWorkspaceId(id).build())
                }
                if (id.isNotEmpty()) {
                    assertFailsWith<StatusRuntimeException> {
                        service.loadWorkspace(
                            LoadWorkspaceRequest
                                .newBuilder()
                                .setWorkspaceId(id)
                                .setProjectPath("/project")
                                .build(),
                        )
                    }
                }
            }
            assertEquals("sentinel", outside.readText())
            assertEquals(0, root.listFiles()!!.size)
            assertEquals(0, service.getWorkspaces(Empty.getDefaultInstance()).workspacesCount)
        }

    @Test
    fun savesReloadsAndDeletesExistingIdFormats() =
        runBlocking {
            val root = temporary.newFolder("workspaces")
            var service = WorkspaceServiceImpl(root)
            for (id in listOf("workspace-123456789", "workspace-code-review", "custom.name_1")) {
                assertTrue(
                    service
                        .saveWorkspace(
                            SaveWorkspaceRequest
                                .newBuilder()
                                .setWorkspaceId(id)
                                .setName("Example")
                                .build(),
                        ).found,
                )
            }
            service = WorkspaceServiceImpl(root)
            assertEquals(3, service.getWorkspaces(Empty.getDefaultInstance()).workspacesCount)
            service.deleteWorkspace(DeleteWorkspaceRequest.newBuilder().setWorkspaceId("workspace-code-review").build())
            assertEquals(2, WorkspaceServiceImpl(root).getWorkspaces(Empty.getDefaultInstance()).workspacesCount)
            val created = service.loadWorkspace(LoadWorkspaceRequest.newBuilder().setProjectPath("/project").build())
            assertTrue(created.found)
            assertTrue(created.workspace.id.startsWith("workspace-"))
        }

    @Test
    fun ignoresPersistedAliasesAndUnsafeIds() =
        runBlocking {
            val root = temporary.newFolder("workspaces")
            root.resolve("alias.json").writeText("""{"id":"other","name":"Alias"}""")
            root.resolve("unsafe.json").writeText("""{"id":"../outside","name":"Escape"}""")
            assertEquals(0, WorkspaceServiceImpl(root).getWorkspaces(Empty.getDefaultInstance()).workspacesCount)
        }

    @Test
    fun rejectsReplacedStorageDirectory() =
        runBlocking {
            val root = temporary.newFolder("workspaces")
            val service = WorkspaceServiceImpl(root)
            Files.move(root.toPath(), root.toPath().resolveSibling("original"))
            root.mkdir()
            root.resolve("safe.json").writeText("sentinel")
            assertFailsWith<IllegalStateException> {
                service.saveWorkspace(SaveWorkspaceRequest.newBuilder().setWorkspaceId("safe").build())
            }
            assertEquals("sentinel", root.resolve("safe.json").readText())
            assertEquals(0, service.getWorkspaces(Empty.getDefaultInstance()).workspacesCount)
        }

    @Test
    fun preservesDistinctIdsAndRejectsFilesystemAliases() =
        runBlocking {
            val root = temporary.newFolder("workspaces")
            val service = WorkspaceServiceImpl(root)
            service.saveWorkspace(
                SaveWorkspaceRequest
                    .newBuilder()
                    .setWorkspaceId("Project")
                    .setName("First")
                    .build(),
            )
            val alternate = root.resolve("project.json")
            if (alternate.exists()) {
                val failure =
                    assertFailsWith<StatusRuntimeException> {
                        service.saveWorkspace(SaveWorkspaceRequest.newBuilder().setWorkspaceId("project").build())
                    }
                assertEquals(Status.Code.ALREADY_EXISTS, failure.status.code)
                assertTrue(root.resolve("Project.json").readText().contains("First"))
            } else {
                service.saveWorkspace(SaveWorkspaceRequest.newBuilder().setWorkspaceId("project").build())
                assertEquals(2, service.getWorkspaces(Empty.getDefaultInstance()).workspacesCount)
            }
        }

    @Test
    fun rejectsSymlinkedRecordsWithoutTouchingTheirTargets() =
        runBlocking {
            val root = temporary.newFolder("workspaces")
            val outside =
                temporary.newFile("outside.json").also {
                    it.writeText("""{"id":"linked","name":"Outside"}""")
                }
            val link = root.resolve("linked.json").toPath()
            Files.createSymbolicLink(link, outside.toPath())
            val service = WorkspaceServiceImpl(root)
            assertEquals(0, service.getWorkspaces(Empty.getDefaultInstance()).workspacesCount)
            assertFailsWith<IllegalStateException> {
                service.saveWorkspace(SaveWorkspaceRequest.newBuilder().setWorkspaceId("linked").build())
            }
            assertFailsWith<IllegalStateException> {
                service.deleteWorkspace(DeleteWorkspaceRequest.newBuilder().setWorkspaceId("linked").build())
            }
            assertTrue(outside.readText().contains("Outside"))
            assertFalse(service.getCurrentWorkspace(Empty.getDefaultInstance()).found)
        }
}
