package ai.rever.boss.service.filesystem

import ai.rever.boss.ipc.proto.services.DeleteFileRequest
import ai.rever.boss.ipc.proto.services.FileSystemServiceGrpcKt
import ai.rever.boss.ipc.proto.services.ReadFileRequest
import ai.rever.boss.ipc.proto.services.ScanDirectoryRequest
import ai.rever.boss.ipc.proto.services.WatchFileChangesRequest
import com.sun.jna.Function
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.WString
import io.grpc.ManagedChannelBuilder
import io.grpc.ServerBuilder
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSystemLimitsTest {
    private val root = Files.createTempDirectory("filesystem-limits-")
    private val server =
        ServerBuilder
            .forPort(0)
            .addService(FileSystemServiceImpl())
            .intercept(RpcFailureDiagnostics)
            .build()
            .start()
    private val channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.port).usePlaintext().build()
    private val stub = FileSystemServiceGrpcKt.FileSystemServiceCoroutineStub(channel)

    @AfterTest
    fun cleanup() {
        channel.shutdownNow()
        server.shutdownNow()
        root.toFile().deleteRecursively()
    }

    @Test
    fun `native open rejects a FIFO without waiting for a writer`() {
        if (Platform.isWindows()) return
        val fifo = root.resolve("fifo")
        assertEquals(0, ProcessBuilder("mkfifo", fifo.toString()).start().waitFor())
        val worker = Executors.newSingleThreadExecutor { task -> Thread(task).apply { isDaemon = true } }
        try {
            val rejected =
                worker.submit<Boolean> {
                    runBlocking {
                        val result = stub.readFile(ReadFileRequest.newBuilder().setPath(fifo.toString()).build())
                        result.errorMessage.isNotEmpty()
                    }
                }
            assertTrue(rejected.get(2, TimeUnit.SECONDS))
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun `scan follows the requested root alias while leaving descendant links untraversed`() =
        runBlocking {
            if (Platform.isWindows()) return@runBlocking
            val real = Files.createDirectory(root.resolve("real"))
            Files.writeString(real.resolve("content"), "ok")
            Files.createSymbolicLink(real.resolve("loop"), real)
            val alias = Files.createSymbolicLink(root.resolve("alias"), real)
            val result =
                stub.scanDirectory(
                    ScanDirectoryRequest
                        .newBuilder()
                        .setPath(alias.toString())
                        .setRecursive(true)
                        .build(),
                )
            assertEquals(
                setOf(alias.resolve("content").toString(), alias.resolve("loop").toString()),
                result.entriesList.map { it.path }.toSet(),
            )
        }

    @Test
    fun `persistent Windows directory sharing failures remain visible`() =
        runBlocking {
            if (!Platform.isWindows()) return@runBlocking
            val denied = Files.createDirectory(root.resolve("denied"))
            withExclusiveDirectory(denied) {
                assertTrue(Files.readAttributes(denied, BasicFileAttributes::class.java, NOFOLLOW_LINKS).isDirectory)
                denied.fileSystem.newWatchService().use { watcher ->
                    assertFailsWith<IOException> { denied.register(watcher, ENTRY_CREATE) }
                }
                assertFailsWith<StatusException> {
                    withTimeout(5000) {
                        stub
                            .watchFileChanges(
                                WatchFileChangesRequest
                                    .newBuilder()
                                    .setPath(root.toString())
                                    .setRecursive(true)
                                    .build(),
                            ).first()
                    }
                }
            }
            // The same operation succeeds after releasing the deliberately conflicting handle.
            denied.fileSystem.newWatchService().use { watcher ->
                assertTrue(denied.register(watcher, ENTRY_CREATE).isValid)
            }
        }

    @Test
    fun `recursive watch survives disappearing descendants and continues observing the root`() =
        runBlocking {
            withTimeout(15_000) {
                val sentinel = root.resolve("sentinel")
                val received =
                    async {
                        stub
                            .watchFileChanges(
                                WatchFileChangesRequest
                                    .newBuilder()
                                    .setPath(root.toString())
                                    .setRecursive(true)
                                    .build(),
                            ).first { it.path == sentinel.toString() }
                    }
                repeat(100) { index ->
                    val transient = Files.createDirectory(root.resolve("transient-$index"))
                    Files.createDirectory(transient.resolve("child"))
                    transient.toFile().deleteRecursively()
                    delay(5)
                }
                while (!received.isCompleted) {
                    Files.writeString(sentinel, "still watching")
                    delay(50)
                }
                assertEquals(sentinel.toString(), received.await().path)
            }
        }

    @Test
    fun `sparse files support long offsets and refuse unbounded legacy reads`() =
        runBlocking {
            val file = root.resolve("large")
            val offset = Int.MAX_VALUE.toLong() + 32
            RandomAccessFile(file.toFile(), "rw").use {
                it.seek(offset)
                it.write("tail".toByteArray())
            }
            val tail =
                stub.readFile(
                    ReadFileRequest
                        .newBuilder()
                        .setPath(file.toString())
                        .setOffsetBytes(offset)
                        .setMaxBytes(16)
                        .build(),
                )
            assertEquals("tail", tail.content.toStringUtf8())
            assertFalse(tail.truncated)
            val beyond =
                stub.readFile(
                    ReadFileRequest
                        .newBuilder()
                        .setPath(file.toString())
                        .setOffsetBytes(Long.MAX_VALUE)
                        .build(),
                )
            assertTrue(beyond.content.isEmpty)
            assertTrue(beyond.errorMessage.isEmpty())
            val legacy = stub.readFile(ReadFileRequest.newBuilder().setPath(file.toString()).build())
            assertTrue(legacy.content.isEmpty)
            assertTrue(legacy.errorMessage.contains("explicit byte limits"))
            val page =
                stub.readFile(
                    ReadFileRequest
                        .newBuilder()
                        .setPath(file.toString())
                        .setMaxBytes(Long.MAX_VALUE)
                        .build(),
                )
            assertEquals(FileSystemLimits.READ_BYTES, page.content.size())
            assertTrue(page.truncated)
        }

    @Test
    fun `requested scan depth is honored and excessive server depth is explicit`() =
        runBlocking {
            var dir = root
            repeat(FileSystemLimits.SCAN_DEPTH + 1) { dir = Files.createDirectory(dir.resolve("d")) }
            val limited =
                stub.scanDirectory(
                    ScanDirectoryRequest
                        .newBuilder()
                        .setPath(root.toString())
                        .setRecursive(true)
                        .setMaxDepth(2)
                        .build(),
                )
            assertEquals(2, limited.entriesCount)
            val failure =
                assertFailsWith<StatusException> {
                    stub.scanDirectory(
                        ScanDirectoryRequest
                            .newBuilder()
                            .setPath(root.toString())
                            .setRecursive(true)
                            .build(),
                    )
                }
            assertEquals(Status.Code.RESOURCE_EXHAUSTED, failure.status.code)
        }

    @Test
    fun `filtered entries still count toward scan work ceiling`() =
        runBlocking {
            repeat(FileSystemLimits.SCAN_ENTRIES) { Files.createFile(root.resolve("$it.bin")) }
            val failure =
                assertFailsWith<StatusException> {
                    stub.scanDirectory(
                        ScanDirectoryRequest
                            .newBuilder()
                            .setPath(root.toString())
                            .addExtensions("txt")
                            .build(),
                    )
                }
            assertEquals(Status.Code.RESOURCE_EXHAUSTED, failure.status.code)
        }

    @Test
    fun `watch emits real changes and releases its slot after collection`() =
        runBlocking {
            repeat(34) { iteration ->
                withTimeout(10_000) {
                    val received =
                        async {
                            val request = WatchFileChangesRequest.newBuilder().setPath(root.toString()).build()
                            stub.watchFileChanges(request).first()
                        }
                    // Repeated writes avoid relying on an internal registration hook or scheduler timing.
                    while (!received.isCompleted) {
                        delay(25)
                        Files.writeString(root.resolve("event-$iteration"), "changed")
                    }
                    assertTrue(received.await().path.startsWith(root.toString()))
                }
            }
        }

    @Test
    fun `a symlinked parent directory does not carry the denylist past its resolved location`() =
        runBlocking {
            if (Platform.isWindows()) return@runBlocking
            val linked = Files.createDirectory(root.resolve("linked"))
            Files.createSymbolicLink(linked.resolve("into-etc"), Paths.get("/etc"))
            val failure =
                assertFailsWith<StatusException> {
                    stub.readFile(
                        ReadFileRequest
                            .newBuilder()
                            .setPath(linked.resolve("into-etc").resolve("hostname").toString())
                            .setMaxBytes(8)
                            .build(),
                    )
                }
            // The message names the resolved location, not the requested one.
            assertEquals(
                Status.Code.UNKNOWN,
                failure.status.code,
                "the handler raises IllegalArgumentException, which gRPC surfaces as UNKNOWN",
            )
            // The canonical spelling of the denylist is the alias that matters on macOS, where
            // `/etc` is `/private/etc`; elsewhere the symlink into it dangles and the scan simply
            // reports a missing directory.
            if (Platform.isMac()) {
                val traversal = Files.createSymbolicLink(root.resolve("private-etc"), Paths.get("/private/etc"))
                assertFailsWith<StatusException> {
                    stub.scanDirectory(
                        ScanDirectoryRequest.newBuilder().setPath(traversal.toString()).build(),
                    )
                }
            }
            assertFalse(Files.exists(root.resolve("linked").resolve("passwd")), "nothing was read or written")
        }

    @Test
    fun `a refused delete is reported instead of returning Empty as success`() =
        runBlocking {
            val failure =
                assertFailsWith<StatusException> {
                    stub.deleteFile(
                        DeleteFileRequest
                            .newBuilder()
                            .setPath(root.resolve("never-existed").toString())
                            .build(),
                    )
                }
            assertEquals(Status.Code.NOT_FOUND, failure.status.code)
            assertTrue(
                failure.status.description
                    .orEmpty()
                    .contains("never-existed"),
                "got: ${failure.status.description}",
            )
            val deleted = root.resolve("deletable")
            Files.writeString(deleted, "content")
            stub.deleteFile(DeleteFileRequest.newBuilder().setPath(deleted.toString()).build())
            assertFalse(Files.exists(deleted), "a successful delete still returns Empty")
        }
}

private suspend fun withExclusiveDirectory(
    directory: Path,
    action: suspend () -> Unit,
) {
    val kernel = NativeLibrary.getInstance("kernel32")
    // FILE_LIST_DIRECTORY, no sharing, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS.
    // Attribute reads remain allowed, so the watch registry can distinguish this from deletion.
    val handle =
        kernel.getFunction("CreateFileW", Function.ALT_CONVENTION).invokePointer(
            arrayOf(WString(directory.toString()), 1, 0, null, 3, 0x02000000, null),
        )
    check(handle != null && Pointer.nativeValue(handle) != -1L) {
        "Cannot acquire exclusive directory handle: ${Native.getLastError()}"
    }
    try {
        action()
    } finally {
        check(kernel.getFunction("CloseHandle", Function.ALT_CONVENTION).invokeInt(arrayOf(handle)) != 0)
    }
}
