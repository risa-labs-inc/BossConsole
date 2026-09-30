package ai.rever.boss.service.filesystem

import ai.rever.boss.ipc.auth.ProcessAuthority
import ai.rever.boss.ipc.proto.services.CreateFileRequest
import ai.rever.boss.ipc.proto.services.DeleteFileRequest
import ai.rever.boss.ipc.proto.services.RenameFileRequest
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FilePathStatusTest {
    @Test
    fun `traversal and root entry refusals retain actionable wire statuses`() {
        runBlocking {
            val root = Files.createTempDirectory("path-status-").toRealPath()
            try {
                AuthenticatedFileService(FileSystemServiceImpl()).use { transport ->
                    val stub =
                        AuthenticatedFileService.stub(transport.channelFor("status-host", ProcessAuthority.HOST))
                    val source = Files.writeString(root.resolve("source"), "retained")
                    val traversal = root.resolve("child/../source").toString()
                    val destination = root.resolve("destination")
                    val create =
                        assertFailsWith<StatusException> {
                            stub.createFile(CreateFileRequest.newBuilder().setPath(traversal).build())
                        }
                    assertEquals(Status.Code.INVALID_ARGUMENT, create.status.code)
                    assertEquals("Parent traversal components are not allowed: $traversal", create.status.description)
                    val rename =
                        assertFailsWith<StatusException> {
                            stub.renameFile(
                                RenameFileRequest
                                    .newBuilder()
                                    .setSourcePath(traversal)
                                    .setDestinationPath(destination.toString())
                                    .build(),
                            )
                        }
                    assertEquals(Status.Code.INVALID_ARGUMENT, rename.status.code)
                    assertEquals("retained", Files.readString(source))
                    assertFalse(Files.exists(destination))
                    val rootEntry =
                        assertFailsWith<StatusException> {
                            stub.deleteFile(DeleteFileRequest.newBuilder().setPath(root.root.toString()).build())
                        }
                    assertEquals(Status.Code.INVALID_ARGUMENT, rootEntry.status.code)
                    assertEquals("An entry must have a parent directory", rootEntry.status.description)
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }
}
