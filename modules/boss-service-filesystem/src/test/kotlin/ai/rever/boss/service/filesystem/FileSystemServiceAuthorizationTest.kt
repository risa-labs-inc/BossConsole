package ai.rever.boss.service.filesystem

import ai.rever.boss.ipc.auth.ProcessAuthority
import ai.rever.boss.ipc.proto.services.CreateFileRequest
import ai.rever.boss.ipc.proto.services.DeleteFileRequest
import ai.rever.boss.ipc.proto.services.FileChangeEvent
import ai.rever.boss.ipc.proto.services.FileSystemServiceGrpcKt
import ai.rever.boss.ipc.proto.services.ReadFileRequest
import ai.rever.boss.ipc.proto.services.RenameFileRequest
import ai.rever.boss.ipc.proto.services.ScanDirectoryRequest
import ai.rever.boss.ipc.proto.services.WatchFileChangesRequest
import ai.rever.boss.ipc.proto.services.WriteFileRequest
import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises the host-only service boundary and credential revocation over the real IPC transport. */
class FileSystemServiceAuthorizationTest {
    private val root = Files.createTempDirectory("filesystem-auth-")
    private val service = AuthenticatedFileService(FileSystemServiceImpl())
    private val host = AuthenticatedFileService.stub(service.channelFor("host", ProcessAuthority.HOST))
    private val nonHostCallers =
        listOf(ProcessAuthority.PROCESS, ProcessAuthority.SUPERVISOR).map { authority ->
            AuthenticatedFileService.stub(service.channelFor(authority.name, authority))
        }

    @AfterTest
    fun cleanup() {
        service.close()
        root.toFile().deleteRecursively()
    }

    @Test
    fun `host file operations succeed while every non-host authority is refused`() =
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
            val created = root.resolve("created-by-host")
            host.createFile(CreateFileRequest.newBuilder().setPath(created.toString()).build())
            assertTrue(Files.isRegularFile(created))
            host.deleteFile(DeleteFileRequest.newBuilder().setPath(created.toString()).build())
            assertFalse(Files.exists(created))

            val declared =
                FileSystemServiceImpl()
                    .bindService()
                    .methods
                    .map { it.methodDescriptor.bareMethodName }
                    .toSet()
            assertEquals(declared, refusedCalls(host, file).keys + "WatchFileChanges")
            for ((method, call) in nonHostCallers.flatMap { refusedCalls(it, file).entries }) {
                val failure = assertFailsWith<StatusException> { call() }
                assertEquals(
                    Status.Code.PERMISSION_DENIED,
                    failure.status.code,
                    "$method must refuse an authenticated non-host caller",
                )
            }
            // Nothing the refused caller attempted may have landed.
            assertEquals("updated", Files.readString(file))
            assertFalse(Files.exists(root.resolve("created-by-plugin")))
            assertFalse(Files.exists(root.resolve("renamed-by-plugin")))
        }

    @Test
    fun `watching refuses every non-host authority`() =
        runBlocking {
            for (caller in nonHostCallers) {
                assertFailsWith<StatusException> {
                    withTimeout(10_000) {
                        caller.watchFileChanges(watchRequest()).first()
                    }
                }.let { assertEquals(Status.Code.PERMISSION_DENIED, it.status.code) }
            }
        }

    @Test
    fun `the host's own watch stream admits and reports a change`() =
        runBlocking {
            withTimeout(30_000) {
                coroutineScope {
                    val first = async { host.watchFileChanges(watchRequest()).first() }
                    val event = awaitWatchEvent(first)
                    assertTrue(
                        Path
                            .of(event.path)
                            .fileName
                            .toString()
                            .startsWith("event-"),
                        "unexpected event: $event",
                    )
                }
            }
        }

    @Test
    fun `revoking an admitted idle watch closes it and a new host credential remains usable`() =
        runBlocking {
            withTimeout(30_000) {
                val first = CompletableDeferred<FileChangeEvent>()
                val activity = Channel<Unit>(Channel.CONFLATED)
                val completion =
                    async {
                        assertFailsWith<StatusException> {
                            host.watchFileChanges(watchRequest()).collect {
                                activity.trySend(Unit)
                                first.complete(it)
                            }
                        }
                    }
                val event = awaitWatchEvent(first)
                // Drain queued events after writes stop, then observe quiet.
                while (withTimeoutOrNull(300) { activity.receive() } != null) { /* wait for quiescence */ }
                assertFalse(completion.isCompleted, "The watch must remain open before revocation")
                service.registry.revoke("host")
                val failure = withTimeout(5_000) { completion.await() }
                assertEquals(Status.Code.UNAUTHENTICATED, failure.status.code)
                assertFailsWith<StatusException> {
                    host.watchFileChanges(watchRequest()).first()
                }.let { assertEquals(Status.Code.UNAUTHENTICATED, it.status.code) }

                val replacement = AuthenticatedFileService.stub(service.channelFor("host", ProcessAuthority.HOST))
                assertEquals("changed", replacement.readFile(readRequest(Path.of(event.path))).content.toStringUtf8())
            }
        }

    private suspend fun awaitWatchEvent(first: Deferred<FileChangeEvent>): FileChangeEvent {
        var sequence = 0
        // Produce until an event proves registration completed, including a slow TLS handshake.
        while (!first.isCompleted) {
            Files.writeString(root.resolve("event-${sequence++}.txt"), "changed")
            delay(50)
        }
        return first.await()
    }

    private fun refusedCalls(
        plugin: FileSystemServiceGrpcKt.FileSystemServiceCoroutineStub,
        file: Path,
    ): Map<String, suspend () -> Any> =
        mapOf(
            "ReadFile" to { plugin.readFile(readRequest(file)) },
            "ScanDirectory" to {
                plugin.scanDirectory(ScanDirectoryRequest.newBuilder().setPath(root.toString()).build())
            },
            "WriteFile" to { plugin.writeFile(writeRequest(file, "no")) },
            "CreateFile" to {
                plugin.createFile(
                    CreateFileRequest.newBuilder().setPath(root.resolve("created-by-plugin").toString()).build(),
                )
            },
            "DeleteFile" to {
                plugin.deleteFile(
                    DeleteFileRequest.newBuilder().setPath(file.toString()).build(),
                )
            },
            "RenameFile" to {
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
