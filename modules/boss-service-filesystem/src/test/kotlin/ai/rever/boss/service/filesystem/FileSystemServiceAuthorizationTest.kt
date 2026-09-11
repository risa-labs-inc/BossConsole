package ai.rever.boss.service.filesystem

import ai.rever.boss.ipc.auth.ProcessAuthority
import ai.rever.boss.ipc.proto.services.CreateFileRequest
import ai.rever.boss.ipc.proto.services.DeleteFileRequest
import ai.rever.boss.ipc.proto.services.ReadFileRequest
import ai.rever.boss.ipc.proto.services.RenameFileRequest
import ai.rever.boss.ipc.proto.services.ScanDirectoryRequest
import ai.rever.boss.ipc.proto.services.WatchFileChangesRequest
import ai.rever.boss.ipc.proto.services.WriteFileRequest
import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The caller authority the filesystem service accepts.
 *
 * Every rpc previously served whoever authenticated. The only credential the service's registry
 * holds in production is the kernel's per-spawn host token, so this pins the boundary in code:
 * an authenticated caller that is not the host is refused on every operation, which is what stops
 * a later token - a plugin's, a supervisor's - silently inheriting the kernel's whole-disk
 * service instead of being relayed through the host that knows what it was granted.
 */
class FileSystemServiceAuthorizationTest {
    private val root = Files.createTempDirectory("filesystem-auth-")
    private val service = AuthenticatedFileService(FileSystemServiceImpl())
    private val host = AuthenticatedFileService.stub(service.channelFor("host", ProcessAuthority.HOST))
    private val plugin = AuthenticatedFileService.stub(service.channelFor("plugin"))

    @AfterTest
    fun cleanup() {
        service.close()
        root.toFile().deleteRecursively()
    }

    @Test
    fun `the kernel reads writes scans while a plugin caller is refused on every operation`() =
        runBlocking {
            val file = root.resolve("visible.txt")
            Files.writeString(file, "host content")

            assertEquals(
                "host content",
                host.readFile(readRequest(file)).content.toStringUtf8(),
            )
            val scanned =
                host.scanDirectory(
                    ScanDirectoryRequest.newBuilder().setPath(root.toString()).build(),
                )
            assertTrue(scanned.entriesList.any { it.path == file.toString() })
            assertTrue(host.writeFile(writeRequest(file, "updated")).success)
            assertEquals("updated", Files.readString(file))

            for (call in refusedPluginCalls(file)) {
                val failure = assertFailsWith<StatusException> { call() }
                assertEquals(
                    Status.Code.PERMISSION_DENIED,
                    failure.status.code,
                    "an authenticated non-host caller must be refused",
                )
            }
            // Nothing the refused caller attempted may have landed.
            assertEquals("updated", Files.readString(file))
            assertTrue(!Files.exists(root.resolve("created-by-plugin")))
            assertTrue(!Files.exists(root.resolve("renamed-by-plugin")))
        }

    @Test
    fun `watching requires the host authority at collection time, not when the flow was built`() =
        runBlocking {
            assertFailsWith<StatusException> {
                withTimeout(10_000) {
                    plugin.watchFileChanges(watchRequest()).first()
                }
            }.let { assertEquals(Status.Code.PERMISSION_DENIED, it.status.code) }
        }

    @Test
    fun `the host's own watch stream admits and reports a change`() =
        runBlocking {
            withTimeout(30_000) {
                coroutineScope {
                    val first = async { host.watchFileChanges(watchRequest()).first() }
                    // Registration polls on a 500ms tick; give it room before creating the event.
                    delay(2_000)
                    Files.writeString(root.resolve("event.txt"), "changed")
                    val event = first.await()
                    assertTrue(event.path.endsWith("event.txt"), "unexpected event: $event")
                }
            }
        }

    private fun refusedPluginCalls(file: Path): List<suspend () -> Any> =
        listOf(
            { plugin.readFile(readRequest(file)) },
            { plugin.scanDirectory(ScanDirectoryRequest.newBuilder().setPath(root.toString()).build()) },
            { plugin.writeFile(writeRequest(file, "no")) },
            {
                plugin.createFile(
                    CreateFileRequest.newBuilder().setPath(root.resolve("created-by-plugin").toString()).build(),
                )
            },
            {
                plugin.deleteFile(
                    DeleteFileRequest.newBuilder().setPath(file.toString()).build(),
                )
            },
            {
                plugin.renameFile(
                    RenameFileRequest
                        .newBuilder()
                        .setSourcePath(file.toString())
                        .setDestinationPath(root.resolve("renamed-by-plugin").toString())
                        .build(),
                )
            },
        )

    private fun watchRequest(): WatchFileChangesRequest =
        WatchFileChangesRequest
            .newBuilder()
            .setPath(root.toString())
            .setRecursive(false)
            .build()

    private fun readRequest(file: Path): ReadFileRequest = ReadFileRequest.newBuilder().setPath(file.toString()).build()

    private fun writeRequest(
        file: Path,
        content: String,
    ): WriteFileRequest =
        WriteFileRequest
            .newBuilder()
            .setPath(file.toString())
            .setContent(ByteString.copyFromUtf8(content))
            .setOverwrite(true)
            .build()
}
