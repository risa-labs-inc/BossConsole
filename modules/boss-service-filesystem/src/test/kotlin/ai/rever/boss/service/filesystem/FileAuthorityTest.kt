package ai.rever.boss.service.filesystem

import ai.rever.boss.ipc.proto.services.CreateFileRequest
import ai.rever.boss.ipc.proto.services.DeleteFileRequest
import ai.rever.boss.ipc.proto.services.ReadFileRequest
import ai.rever.boss.ipc.proto.services.RenameFileRequest
import ai.rever.boss.ipc.proto.services.ScanDirectoryRequest
import ai.rever.boss.ipc.proto.services.WatchFileChangesRequest
import ai.rever.boss.ipc.proto.services.WriteFileRequest
import com.google.protobuf.ByteString
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    private val policy = FilePathPolicy(listOf(blocked))
    private val service = FileSystemServiceImpl(FileAccess(policy))

    @AfterTest
    fun cleanup() {
        // Files.walk never follows links, unlike Kotlin File.deleteRecursively.
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    @Test
    fun `dangling final and parent links cannot write into protected missing targets`() =
        runBlocking {
            val final = Files.createSymbolicLink(root.resolve("final"), blocked.resolve("missing"))
            val parent = Files.createSymbolicLink(root.resolve("parent"), blocked.resolve("missing-directory"))
            for (path in listOf(final, parent.resolve("new"))) {
                assertFailsWith<FilePathDeniedException> {
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
                    FileSystemServiceImpl(
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
                FileSystemServiceImpl(
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
                FileSystemServiceImpl(
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
            assertFailsWith<FilePathDeniedException> { flow.first() }
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
                FileSystemServiceImpl(
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
            assertFailsWith<FilePathDeniedException> {
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
            assertFailsWith<FilePathDeniedException> {
                service.renameFile(
                    RenameFileRequest
                        .newBuilder()
                        .setSourcePath(alias.toString())
                        .setDestinationPath(root.resolve("moved").toString())
                        .build(),
                )
            }
            assertFailsWith<FilePathDeniedException> {
                service.deleteFile(
                    DeleteFileRequest
                        .newBuilder()
                        .setPath(alias.toString())
                        .setRecursive(true)
                        .build(),
                )
            }
            assertFailsWith<FilePathDeniedException> {
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
}
