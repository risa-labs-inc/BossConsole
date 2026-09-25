package ai.rever.boss.service.filesystem

import ai.rever.boss.ipc.auth.ProcessAuthority
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
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
    private val transport = AuthenticatedFileService(FileSystemServiceImpl())
    private val channel = transport.channelFor("limits-host", ProcessAuthority.HOST)
    private val stub = FileSystemServiceGrpcKt.FileSystemServiceCoroutineStub(channel)

    @AfterTest
    fun cleanup() {
        transport.close()
        // Files.walk does not follow symlinks; fixture aliases must never widen cleanup.
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `scan refuses an alias into a blocked system root`() =
        runBlocking<Unit> {
            org.junit.Assume.assumeTrue(Platform.isLinux())
            val alias = Files.createSymbolicLink(root.resolve("system-alias"), Path.of("/proc"))
            try {
                assertFailsWith<StatusException> {
                    stub.scanDirectory(ScanDirectoryRequest.newBuilder().setPath(alias.toString()).build())
                }
            } finally {
                Files.deleteIfExists(alias)
            }
        }

    @Test
    fun `native POSIX open refuses a replaced leaf link`() {
        if (Platform.isWindows()) return
        val target = Files.writeString(root.resolve("native-target"), "content")
        openRegularFile(target).use { assertEquals(7L, it.size) }
        val link = Files.createSymbolicLink(root.resolve("native-link"), target)
        assertFailsWith<IOException> { openRegularFile(link).use { } }
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
                    try {
                        openRegularFile(fifo).use { false }
                    } catch (_: IllegalArgumentException) {
                        true
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
            // Windows CI spent 55 seconds in synchronous fixture churn. Give fixture I/O
            // its own budget; retain a separate 15-second deadline for actual watch delivery.
            withTimeout(90_000) {
                val sentinel = root.resolve("sentinel")
                val readiness = root.resolve("watch-ready")
                val ready = CompletableDeferred<Unit>()
                val received =
                    async {
                        stub
                            .watchFileChanges(
                                WatchFileChangesRequest
                                    .newBuilder()
                                    .setPath(root.toString())
                                    .setRecursive(true)
                                    .build(),
                            ).first {
                                if (it.path == readiness.toString()) ready.complete(Unit)
                                it.path == sentinel.toString()
                            }
                    }
                withTimeout(15_000) {
                    while (!ready.isCompleted) {
                        withContext(Dispatchers.IO) { Files.writeString(readiness, "ready") }
                        delay(25)
                    }
                }
                withContext(Dispatchers.IO) {
                    repeat(100) { index ->
                        val transient = Files.createDirectory(root.resolve("transient-$index"))
                        Files.createDirectory(transient.resolve("child"))
                        transient.toFile().deleteRecursively()
                        delay(5)
                    }
                }
                withTimeout(15_000) {
                    while (!received.isCompleted) {
                        withContext(Dispatchers.IO) { Files.writeString(sentinel, "still watching") }
                        delay(50)
                    }
                    assertEquals(sentinel.toString(), received.await().path)
                }
            }
        }

    @Test
    fun `reads through file links resolve the target before the bounded open`() =
        runBlocking {
            val target = Files.writeString(root.resolve("target"), "linked content")
            val link = Files.createSymbolicLink(root.resolve("file-link"), target)
            val response = stub.readFile(ReadFileRequest.newBuilder().setPath(link.toString()).build())
            assertTrue(response.errorMessage.isEmpty(), response.errorMessage)
            assertEquals("linked content", response.content.toStringUtf8())
        }

    @Test
    fun `Windows reads retain support for paths beyond MAX_PATH`() =
        runBlocking {
            if (!Platform.isWindows()) return@runBlocking
            var directory = root
            while (directory.toString().length < 300) directory = directory.resolve("long-directory-component")
            Files.createDirectories(directory)
            val file = Files.writeString(directory.resolve("content.txt"), "long path content")
            val response = stub.readFile(ReadFileRequest.newBuilder().setPath(file.toString()).build())
            assertTrue(response.errorMessage.isEmpty(), response.errorMessage)
            assertEquals("long path content", response.content.toStringUtf8())
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
            // Capture the process-wide baseline up front: cancellation reaches the server
            // asynchronously, so wait for these slots back before the next suite in the same
            // JVM starts counting on them.
            val streamsBefore = WatchResources.streams.availablePermits()
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
            withTimeout(30_000) {
                while (WatchResources.streams.availablePermits() != streamsBefore) delay(10)
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
                Status.Code.PERMISSION_DENIED,
                failure.status.code,
                "denied paths must remain distinguishable over the wire",
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
            val occupied = Files.createDirectory(root.resolve("occupied"))
            Files.writeString(occupied.resolve("child"), "preserved")
            val failure =
                assertFailsWith<StatusException> {
                    stub.deleteFile(
                        DeleteFileRequest
                            .newBuilder()
                            .setPath(occupied.toString())
                            .build(),
                    )
                }
            assertEquals(Status.Code.FAILED_PRECONDITION, failure.status.code)
            assertTrue(
                failure.status.description
                    .orEmpty()
                    .contains("occupied"),
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
