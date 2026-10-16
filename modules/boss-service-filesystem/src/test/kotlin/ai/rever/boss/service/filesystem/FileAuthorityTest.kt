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
import io.grpc.StatusException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** All protected targets are disposable fixtures. No test writes or deletes a system path. */
class FileAuthorityTest {
    private val root = Files.createTempDirectory("filesystem-authority-").toRealPath()
    private val blocked = Files.createDirectory(root.resolve("blocked"))
    private val policy = FileSystemPathPolicy(listOf(blocked))
    private val transports = mutableListOf<AuthenticatedFileService>()
    private val service = hosted(FileAccess(policy))

    @AfterTest
    fun cleanup() {
        transports.forEach { it.close() }
        transports.clear()
        // Files.walk never follows links, unlike Kotlin File.deleteRecursively.
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    @Test
    fun `reads follow allowed file links and refuse links to protected targets`() =
        runBlocking {
            val target = Files.writeString(root.resolve("readable"), "allowed")
            val allowedLink = Files.createSymbolicLink(root.resolve("read-link"), target)
            val response = service.readFile(ReadFileRequest.newBuilder().setPath(allowedLink.toString()).build())
            assertEquals("allowed", response.content.toStringUtf8(), response.errorMessage)
            val secret = Files.writeString(blocked.resolve("secret"), "protected")
            val deniedLink = Files.createSymbolicLink(root.resolve("denied-link"), secret)
            assertFailsWith<StatusException> {
                service.readFile(ReadFileRequest.newBuilder().setPath(deniedLink.toString()).build())
            }
            Unit
        }

    @Test
    fun `dangling final and parent links cannot write into protected missing targets`() =
        runBlocking {
            val final = Files.createSymbolicLink(root.resolve("final"), blocked.resolve("missing"))
            val parent = Files.createSymbolicLink(root.resolve("parent"), blocked.resolve("missing-directory"))
            for (path in listOf(final, parent.resolve("new"))) {
                assertFailsWith<StatusException> {
                    service.writeFile(
                        WriteFileRequest
                            .newBuilder()
                            .setPath(path.toString())
                            .setCreateParents(true)
                            .setOverwrite(true)
                            .setContent(ByteString.copyFromUtf8("forbidden"))
                            .build(),
                    )
                }
            }
            assertFalse(Files.exists(blocked.resolve("missing"), NOFOLLOW_LINKS))
            assertFalse(Files.exists(blocked.resolve("missing-directory"), NOFOLLOW_LINKS))
        }

    @Test
    fun `ordinary filenames containing two dots are not parent traversal`() =
        runBlocking {
            for (name in listOf("..hidden", "a..b")) {
                val path = root.resolve(name)
                service.createFile(CreateFileRequest.newBuilder().setPath(path.toString()).build())
                assertTrue(Files.exists(path))
            }
            assertFailsWith<StatusException> {
                val path = root.resolve("../escape").toString()
                service.createFile(CreateFileRequest.newBuilder().setPath(path).build())
            }
            Unit
        }

    @Test
    fun `recursive deletion handles wide directories and refuses excessive depth`() =
        runBlocking {
            val wide = Files.createDirectory(root.resolve("wide"))
            repeat(400) { Files.writeString(wide.resolve("file-$it"), "fixture") }
            service.deleteFile(
                DeleteFileRequest
                    .newBuilder()
                    .setPath(wide.toString())
                    .setRecursive(true)
                    .build(),
            )
            assertFalse(Files.exists(wide))
            val deep = Files.createDirectory(root.resolve("deep"))
            var leaf = deep
            repeat(FileSystemLimits.SCAN_DEPTH + 2) { leaf = Files.createDirectory(leaf.resolve("d")) }
            Files.writeString(leaf.resolve("sentinel"), "preserved")
            val failure =
                assertFailsWith<StatusException> {
                    service.deleteFile(
                        DeleteFileRequest
                            .newBuilder()
                            .setPath(deep.toString())
                            .setRecursive(true)
                            .build(),
                    )
                }
            assertEquals(io.grpc.Status.Code.RESOURCE_EXHAUSTED, failure.status.code)
            assertEquals("preserved", Files.readString(leaf.resolve("sentinel")))
        }

    @Test
    fun `create failures and path denials carry usable grpc status codes`() =
        runBlocking {
            val missing =
                assertFailsWith<io.grpc.StatusException> {
                    val path = root.resolve("missing/file").toString()
                    val request = CreateFileRequest.newBuilder().setPath(path).build()
                    service.createFile(request)
                }
            assertEquals(io.grpc.Status.Code.NOT_FOUND, missing.status.code)
            val denied =
                assertFailsWith<StatusException> {
                    val path = blocked.resolve("file").toString()
                    service.createFile(CreateFileRequest.newBuilder().setPath(path).build())
                }
            assertEquals(io.grpc.Status.Code.PERMISSION_DENIED, denied.status.code)
        }

    @Test
    fun `recursive deletion unlinks descendant directory links and preserves their targets`() =
        runBlocking {
            Files.writeString(blocked.resolve("sentinel"), "protected")
            val tree = Files.createDirectory(root.resolve("tree"))
            Files.createSymbolicLink(tree.resolve("escape"), blocked)
            Files.writeString(tree.resolve("ordinary"), "allowed")
            service.deleteFile(
                DeleteFileRequest
                    .newBuilder()
                    .setPath(tree.toString())
                    .setRecursive(true)
                    .build(),
            )
            assertFalse(Files.exists(tree, NOFOLLOW_LINKS))
            assertEquals("protected", Files.readString(blocked.resolve("sentinel")))
        }

    @Test
    fun `safe unlink rename and overwrite of protected-target links remain available`() =
        runBlocking {
            Files.writeString(blocked.resolve("sentinel"), "protected")
            val link = Files.createSymbolicLink(root.resolve("link"), blocked)
            val renamed = root.resolve("renamed")
            service.renameFile(
                RenameFileRequest
                    .newBuilder()
                    .setSourcePath(link.toString())
                    .setDestinationPath(renamed.toString())
                    .build(),
            )
            assertTrue(Files.isSymbolicLink(renamed))
            service.deleteFile(
                DeleteFileRequest
                    .newBuilder()
                    .setPath(renamed.toString())
                    .setRecursive(true)
                    .build(),
            )
            Files.createSymbolicLink(renamed, blocked)
            val source = Files.writeString(root.resolve("source"), "replacement")
            service.renameFile(
                RenameFileRequest
                    .newBuilder()
                    .setSourcePath(source.toString())
                    .setDestinationPath(renamed.toString())
                    .setOverwrite(true)
                    .build(),
            )
            assertEquals("replacement", Files.readString(renamed))
            assertEquals("protected", Files.readString(blocked.resolve("sentinel")))
        }

    @Test
    fun `scan omits blocked real descendants and preserves visible alias paths`() =
        runBlocking {
            Files.writeString(blocked.resolve("secret"), "protected")
            Files.writeString(root.resolve("ordinary"), "allowed")
            val alias = Files.createSymbolicLink(root.resolve("alias"), root)
            val result =
                service.scanDirectory(
                    ScanDirectoryRequest
                        .newBuilder()
                        .setPath(alias.toString())
                        .setRecursive(true)
                        .build(),
                )
            assertTrue(result.entriesList.any { it.path == alias.resolve("ordinary").toString() })
            assertFalse(result.entriesList.any { "blocked" in Path.of(it.path).map(Path::toString) })
        }

    @Test
    fun `read write create and delete remain on the anchored parent after a link swap`() =
        runBlocking {
            for (operation in listOf("read", "write", "create", "delete")) {
                val parent = Files.createDirectory(root.resolve(operation))
                val moved = root.resolve("moved-$operation")
                Files.writeString(parent.resolve("file"), "allowed")
                Files.writeString(blocked.resolve("file"), "protected")
                var swapped = false
                val anchored =
                    hosted(
                        FileAccess(policy) {
                            if (!swapped) {
                                swapped = true
                                Files.move(parent, moved)
                                Files.createSymbolicLink(parent, blocked)
                            }
                        },
                    )
                val path = parent.resolve("file").toString()
                when (operation) {
                    "read" -> {
                        assertEquals(
                            "allowed",
                            anchored
                                .readFile(ReadFileRequest.newBuilder().setPath(path).build())
                                .content
                                .toStringUtf8(),
                        )
                    }

                    "write" -> {
                        val result =
                            anchored.writeFile(
                                WriteFileRequest
                                    .newBuilder()
                                    .setPath(path)
                                    .setOverwrite(true)
                                    .setContent(ByteString.copyFromUtf8("changed"))
                                    .build(),
                            )
                        assertTrue(result.success, result.errorMessage)
                        assertEquals("changed", Files.readString(moved.resolve("file")))
                    }

                    "create" -> {
                        anchored.createFile(
                            CreateFileRequest.newBuilder().setPath(parent.resolve("new").toString()).build(),
                        )
                        assertTrue(Files.exists(moved.resolve("new")))
                        assertFalse(Files.exists(blocked.resolve("new")))
                    }

                    "delete" -> {
                        anchored.deleteFile(DeleteFileRequest.newBuilder().setPath(path).build())
                        assertFalse(Files.exists(moved.resolve("file")))
                    }
                }
                assertTrue(swapped)
                assertEquals("protected", Files.readString(blocked.resolve("file")))
            }
        }

    @Test
    fun `rename uses both anchored parents after destination path replacement`() =
        runBlocking {
            val sourceParent = Files.createDirectory(root.resolve("source-parent"))
            val destinationParent = Files.createDirectory(root.resolve("destination-parent"))
            Files.writeString(sourceParent.resolve("file"), "allowed")
            val moved = root.resolve("moved-destination")
            var anchors = 0
            val anchored =
                hosted(
                    FileAccess(policy) {
                        if (++anchors == 2) {
                            Files.move(destinationParent, moved)
                            Files.createSymbolicLink(destinationParent, blocked)
                        }
                    },
                )
            anchored.renameFile(
                RenameFileRequest
                    .newBuilder()
                    .setSourcePath(sourceParent.resolve("file").toString())
                    .setDestinationPath(destinationParent.resolve("new").toString())
                    .build(),
            )
            assertEquals("allowed", Files.readString(moved.resolve("new")))
            assertFalse(Files.exists(blocked.resolve("new")))
        }

    @Test
    fun `scan remains on the anchored parent after its original name is replaced`() =
        runBlocking {
            val parent = Files.createDirectory(root.resolve("parent"))
            val tree = Files.createDirectory(parent.resolve("tree"))
            Files.writeString(tree.resolve("allowed"), "allowed")
            Files.createDirectories(blocked.resolve("tree"))
            Files.writeString(blocked.resolve("tree/secret"), "protected")
            val anchored =
                hosted(
                    FileAccess(policy) {
                        Files.move(parent, root.resolve("moved"))
                        Files.createSymbolicLink(parent, blocked)
                    },
                )
            val result =
                anchored.scanDirectory(
                    ScanDirectoryRequest
                        .newBuilder()
                        .setPath(tree.toString())
                        .setRecursive(true)
                        .build(),
                )
            assertEquals(listOf(tree.resolve("allowed").toString()), result.entriesList.map { it.path })
        }

    @Test
    fun `watch authorizes at collection instead of when the cold flow is created`() =
        runBlocking {
            val allowed = Files.createDirectory(root.resolve("allowed"))
            Files.createDirectory(allowed.resolve("child"))
            Files.createDirectory(blocked.resolve("child"))
            val alias = Files.createSymbolicLink(root.resolve("alias"), allowed)
            val request = WatchFileChangesRequest.newBuilder().setPath(alias.resolve("child").toString()).build()
            val flow = service.watchFileChanges(request)
            Files.delete(alias)
            Files.createSymbolicLink(alias, blocked)
            assertFailsWith<StatusException> { flow.first() }
            Unit
        }

    @Test
    fun `watch registrations use the held parent after its original name is replaced`() =
        runBlocking {
            val parent = Files.createDirectory(root.resolve("parent"))
            val tree = Files.createDirectory(parent.resolve("tree"))
            val moved = root.resolve("moved")
            Files.createDirectory(blocked.resolve("tree"))
            val anchored =
                hosted(
                    FileAccess(policy) {
                        Files.move(parent, moved)
                        Files.createSymbolicLink(parent, blocked)
                    },
                )
            withTimeout(10_000) {
                val event =
                    async {
                        anchored
                            .watchFileChanges(
                                WatchFileChangesRequest.newBuilder().setPath(tree.toString()).build(),
                            ).first()
                    }
                delay(500)
                Files.writeString(blocked.resolve("tree/secret"), "protected")
                delay(500)
                assertFalse(event.isCompleted, "A watch must not observe the replacement target")
                Files.writeString(moved.resolve("tree/allowed"), "allowed")
                assertEquals(tree.resolve("allowed").toString(), event.await().path)
            }
        }

    @Test
    fun `recursive watch respects platform rename semantics and releases descendant handles`() =
        runBlocking {
            val watched = Files.createDirectory(root.resolve("watched"))
            val original = Files.createDirectory(watched.resolve("original"))
            Files.createDirectory(original.resolve("nested"))
            val renamed = watched.resolve("renamed")
            val windows = System.getProperty("os.name").startsWith("Windows")
            val expected = (if (windows) original else renamed).resolve("nested/after-rename")
            val events = Channel<FileChangeEvent>(Channel.UNLIMITED)
            val collector =
                async {
                    service
                        .watchFileChanges(
                            WatchFileChangesRequest
                                .newBuilder()
                                .setPath(watched.toString())
                                .setRecursive(true)
                                .build(),
                        ).collect { events.send(it) }
                }
            try {
                if (windows) {
                    // NTFS proves the descendant handles are held by refusing the rename.
                    waitUntilDescendantHandlesHeld(original, renamed)
                } else {
                    // Wait for real readiness: the probe is delivered only once the recursive
                    // scan is live, so the rename cannot race ahead of the watcher.
                    waitForWatchReadiness(events, watched)
                    Files.move(original, renamed)
                }
                // The registry rebinds the renamed subtree on its next poll, which is not
                // signalled; tick the write until it is delivered under the current name.
                val delivered = awaitDeliveryUnder(events, expected)
                assertTrue(delivered, "the write under the current name was never delivered")
                // Once delivery under the current name has been observed, the rebind is
                // complete: anything still reported under the pre-rename descendant path is
                // the defect this test exists to catch. (The rename's own event can only be
                // emitted in the same poll batch as the rebind, before this point.)
                val oldBase = watched.resolve(if (windows) "renamed" else "original").resolve("nested")
                assertFalse(reportsStalePath(events, oldBase), "events under the pre-rename path")
            } finally {
                collector.cancelAndJoin()
            }
            if (windows) {
                // The cancelled watch releases its server-side handles asynchronously; the
                // rename lands once the release completes.
                withTimeout(30_000) {
                    var moved = false
                    while (!moved) {
                        try {
                            Files.move(original, renamed)
                            moved = true
                        } catch (_: AccessDeniedException) {
                            delay(100)
                        }
                    }
                }
                assertTrue(
                    Files.readString(renamed.resolve("nested/after-rename")).startsWith("still watched-"),
                )
            }
        }

    /**
     * NTFS refuses this rename while descendant notification handles are open.
     * Registration reaches the server asynchronously over the transport, so retry
     * until the refusal proves the handles are held. A forward move that lands first
     * is moved back before retrying, and the move-back is retried on its own: while
     * the handles are held it too can be refused, and a lost race here must read as
     * fixture drift, never as a watch defect. Either exit leaves the fixture at
     * `original`.
     */
    private suspend fun waitUntilDescendantHandlesHeld(
        original: Path,
        renamed: Path,
    ) {
        withTimeout(30_000) {
            var refused = false
            while (!refused) {
                try {
                    Files.move(original, renamed)
                    var restored = false
                    while (!restored) {
                        try {
                            Files.move(renamed, original)
                            restored = true
                        } catch (_: AccessDeniedException) {
                            delay(100)
                        }
                    }
                    delay(100)
                } catch (_: AccessDeniedException) {
                    refused = true
                }
            }
        }
    }

    /**
     * The snapshot watcher baselines at registration time, so a write that lands
     * before the baseline is invisible. The probe is therefore ticked with fresh
     * content until it is delivered, however late the registration lands.
     */
    private suspend fun waitForWatchReadiness(
        events: Channel<FileChangeEvent>,
        watched: Path,
    ) {
        val probe = watched.resolve("probe")
        var tick = 0L
        val deadline = System.currentTimeMillis() + 30_000
        var probeSeen = false
        while (!probeSeen && System.currentTimeMillis() < deadline) {
            tick++
            Files.writeString(probe, "ready-$tick")
            try {
                val event = withTimeout(250) { events.receive() }
                if (event.path == probe.toString()) probeSeen = true
            } catch (_: TimeoutCancellationException) {
                // No event this cycle; tick again.
            }
        }
        assertTrue(probeSeen, "the probe write was never delivered by the watch")
        Files.delete(probe)
    }

    /** Ticks a write under [target] with fresh content until the watch delivers it. */
    private suspend fun awaitDeliveryUnder(
        events: Channel<FileChangeEvent>,
        target: Path,
    ): Boolean {
        var tick = 0L
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            tick++
            Files.writeString(target, "still watched-$tick")
            try {
                val event = withTimeout(250) { events.receive() }
                if (event.path == target.toString()) return true
            } catch (_: TimeoutCancellationException) {
                // No event this cycle; tick again.
            }
        }
        return false
    }

    /** True when an event still carries the pre-rename descendant path inside the window. */
    private suspend fun reportsStalePath(
        events: Channel<FileChangeEvent>,
        oldBase: Path,
    ): Boolean {
        val deadline = System.currentTimeMillis() + 500
        while (System.currentTimeMillis() < deadline) {
            try {
                val event = withTimeout(50) { events.receive() }
                if (event.path == oldBase.toString() ||
                    event.path.startsWith(oldBase.toString() + File.separator)
                ) {
                    return true
                }
            } catch (_: TimeoutCancellationException) {
                // Observation window elapsed without a stale event.
            }
        }
        return false
    }

    @Test
    fun `dangling links to allowed targets retain normal write behavior`() =
        runBlocking {
            val target = root.resolve("allowed-missing")
            val link = Files.createSymbolicLink(root.resolve("allowed-link"), target)
            val result =
                service.writeFile(
                    WriteFileRequest
                        .newBuilder()
                        .setPath(link.toString())
                        .setContent(ByteString.copyFromUtf8("allowed"))
                        .build(),
                )
            assertTrue(result.success, result.errorMessage)
            assertEquals("allowed", Files.readString(target))
        }

    @Test
    fun `an ancestor move cannot relocate a protected subtree outside the denylist`() =
        runBlocking {
            val destination = root.resolveSibling(root.fileName.toString() + "-moved")
            Files.writeString(blocked.resolve("sentinel"), "protected")
            assertFailsWith<StatusException> {
                service.renameFile(
                    RenameFileRequest
                        .newBuilder()
                        .setSourcePath(root.toString())
                        .setDestinationPath(destination.toString())
                        .build(),
                )
            }
            assertFalse(Files.exists(destination))
            assertEquals("protected", Files.readString(blocked.resolve("sentinel")))
        }

    @Test
    fun `case aliases of a protected entry cannot be deleted renamed or watched`() =
        runBlocking {
            val alias = blocked.resolveSibling(blocked.fileName.toString().uppercase())
            org.junit.Assume.assumeTrue(
                "Case-insensitive volume required",
                Files.exists(alias) && Files.isSameFile(alias, blocked),
            )
            Files.writeString(blocked.resolve("sentinel"), "protected")
            assertFailsWith<StatusException> {
                service.renameFile(
                    RenameFileRequest
                        .newBuilder()
                        .setSourcePath(alias.toString())
                        .setDestinationPath(root.resolve("moved").toString())
                        .build(),
                )
            }
            assertFailsWith<StatusException> {
                service.deleteFile(
                    DeleteFileRequest
                        .newBuilder()
                        .setPath(alias.toString())
                        .setRecursive(true)
                        .build(),
                )
            }
            assertFailsWith<StatusException> {
                service.watchFileChanges(WatchFileChangesRequest.newBuilder().setPath(alias.toString()).build()).first()
            }
            assertEquals("protected", Files.readString(blocked.resolve("sentinel")))
        }

    @Test
    fun `ordinary creation overwrite and missing parents retain their behavior`() =
        runBlocking {
            val path = root.resolve("parents/child/file")
            val request =
                WriteFileRequest
                    .newBuilder()
                    .setPath(path.toString())
                    .setCreateParents(true)
                    .setContent(ByteString.copyFromUtf8("ordinary"))
                    .build()
            assertTrue(service.writeFile(request).success)
            assertFalse(service.writeFile(request).success)
            assertTrue(
                service
                    .writeFile(
                        request
                            .toBuilder()
                            .setOverwrite(true)
                            .setContent(ByteString.copyFromUtf8("new"))
                            .build(),
                    ).success,
            )
            assertEquals("new", Files.readString(path))
            if ("posix" in root.fileSystem.supportedFileAttributeViews()) {
                val control = Files.writeString(root.resolve("control"), "control")
                assertEquals(Files.getPosixFilePermissions(control), Files.getPosixFilePermissions(path))
            }
        }

    private fun hosted(access: FileAccess): FileSystemServiceGrpcKt.FileSystemServiceCoroutineStub {
        val transport = AuthenticatedFileService(FileSystemServiceImpl(access))
        transports += transport
        return AuthenticatedFileService.stub(transport.channelFor("authority-host", ProcessAuthority.HOST))
    }
}
