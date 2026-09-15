package ai.rever.boss.service.filesystem

import ai.rever.boss.ipc.proto.services.CreateFileRequest
import ai.rever.boss.ipc.proto.services.DeleteFileRequest
import ai.rever.boss.ipc.proto.services.FileSystemServiceGrpcKt
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.netty.NettyServerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSystemServiceMutationTest {
    private val service = FileSystemServiceImpl()
    private val testDirectory =
        File.createTempFile("filesystem-service-mutation-", "").let { file ->
            file.delete()
            file.mkdirs()
            file
        }

    @AfterTest
    fun cleanUp() {
        testDirectory.deleteRecursively()
    }

    @Test
    fun `create regular file succeeds`() {
        val file = testDirectory.resolve("new-file.txt")

        createFile(file)

        assertTrue(file.isFile)
    }

    @Test
    fun `create existing regular file is refused`() {
        val file = testDirectory.resolve("existing-file.txt").apply { createNewFile() }

        val error = assertFailsWith<StatusException> { createFile(file) }

        assertEquals(Status.Code.ALREADY_EXISTS, error.status.code)
        assertEquals("File already exists: ${file.absolutePath}", error.status.description)
        assertTrue(file.isFile)
    }

    @Test
    fun `create file at directory path is refused`() {
        val directory = testDirectory.resolve("occupied-by-directory").apply { mkdir() }

        val error = assertFailsWith<StatusException> { createFile(directory) }

        assertEquals(Status.Code.ALREADY_EXISTS, error.status.code)
        assertEquals("File already exists: ${directory.absolutePath}", error.status.description)
        assertTrue(directory.isDirectory)
    }

    @Test
    fun `gRPC client receives non-recursive delete refusal status and description`() {
        val directory = testDirectory.resolve("non-empty-over-grpc").apply { mkdir() }
        directory.resolve("child.txt").createNewFile()

        withGrpcService { stub ->
            val error =
                assertFailsWith<StatusException> {
                    runBlocking {
                        stub.deleteFile(
                            DeleteFileRequest
                                .newBuilder()
                                .setPath(directory.absolutePath)
                                .setRecursive(false)
                                .build(),
                        )
                    }
                }

            assertEquals(Status.Code.FAILED_PRECONDITION, error.status.code)
            assertEquals(
                "Cannot delete non-empty directory without recursive=true: ${directory.absolutePath}",
                error.status.description,
            )
            assertTrue(directory.isDirectory)
            assertTrue(directory.resolve("child.txt").isFile)
        }
    }

    @Test
    fun `create with a missing parent reports NOT_FOUND`() {
        val file = testDirectory.resolve("missing-parent/file.txt")

        val error = assertFailsWith<StatusException> { createFile(file) }
        assertEquals(Status.Code.NOT_FOUND, error.status.code)

        assertFalse(file.exists())
    }

    @Test
    fun `create with a regular-file parent reports a structured error`() {
        val parent = testDirectory.resolve("regular-file-parent").apply { createNewFile() }
        val file = parent.resolve("child.txt")

        val error = assertFailsWith<StatusException> { createFile(file) }
        assertEquals(Status.Code.INTERNAL, error.status.code)

        assertFalse(file.exists())
    }

    @Test
    fun `delete regular file succeeds`() {
        val file = testDirectory.resolve("delete-me.txt").apply { createNewFile() }

        deleteFile(file, recursive = false)

        assertFalse(file.exists())
    }

    @Test
    fun `delete empty directory succeeds`() {
        val directory = testDirectory.resolve("empty-directory").apply { mkdir() }

        deleteFile(directory, recursive = false)

        assertFalse(directory.exists())
    }

    @Test
    fun `delete non-empty directory without recursion is refused`() {
        val directory = testDirectory.resolve("non-empty-directory").apply { mkdir() }
        val child = directory.resolve("child.txt").apply { createNewFile() }

        val error = assertFailsWith<StatusException> { deleteFile(directory, recursive = false) }

        assertEquals(Status.Code.FAILED_PRECONDITION, error.status.code)
        assertEquals(
            "Cannot delete non-empty directory without recursive=true: ${directory.absolutePath}",
            error.status.description,
        )
        assertTrue(directory.isDirectory)
        assertTrue(child.isFile)
    }

    @Test
    fun `non-recursive delete reports permission denial for a protected dangling symlink`() {
        val parent = testDirectory.resolve("protected-parent").apply { mkdir() }
        val link = parent.resolve("dangling-link")
        val permissions = createProtectedDanglingSymlink(parent, link)

        try {
            // Establish the environment before invoking the service; never turn a failed assertion into a skip.
            assumeTrue("The parent remains writable despite the permission fixture", !Files.isWritable(parent.toPath()))
            val error = assertFailsWith<StatusException> { deleteFile(link, recursive = false) }

            assertEquals(Status.Code.PERMISSION_DENIED, error.status.code)
            assertEquals("Access denied: ${link.absolutePath}", error.status.description)
            assertTrue(Files.exists(link.toPath(), NOFOLLOW_LINKS))
        } finally {
            Files.setPosixFilePermissions(parent.toPath(), permissions)
        }
    }

    @Test
    fun `non-recursive delete removes a symlink without deleting its target`() {
        val target = testDirectory.resolve("retained-target.txt").apply { writeText("keep me") }
        val link = testDirectory.resolve("removable-link")
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (_: UnsupportedOperationException) {
            assumeTrue("Symbolic links are unavailable on this platform", false)
        } catch (_: IOException) {
            assumeTrue("The environment cannot create the symbolic-link fixture", false)
        }

        deleteFile(link, recursive = false)

        assertFalse(Files.exists(link.toPath(), NOFOLLOW_LINKS))
        assertEquals("keep me", target.readText())
    }

    @Test
    fun `gRPC create with a missing parent reports NOT_FOUND`() {
        val file = testDirectory.resolve("missing-grpc-parent/file.txt")
        withGrpcService { stub ->
            // Held-handle creation now maps missing parents explicitly at the wire boundary.
            val error =
                assertFailsWith<StatusException> {
                    runBlocking {
                        stub.createFile(CreateFileRequest.newBuilder().setPath(file.absolutePath).build())
                    }
                }
            assertEquals(Status.Code.NOT_FOUND, error.status.code)
            assertTrue(
                error.status.description
                    .orEmpty()
                    .contains("Create failed"),
            )
            assertFalse(file.exists())
        }
    }

    @Test
    fun `delete missing target remains successful`() {
        val missingFile = testDirectory.resolve("missing.txt")

        deleteFile(missingFile, recursive = false)

        assertFalse(missingFile.exists())
    }

    @Test
    fun `recursive delete of a missing target remains successful`() {
        val missingFile = testDirectory.resolve("missing-recursively.txt")

        deleteFile(missingFile, recursive = true)

        assertFalse(missingFile.exists())
    }

    @Test
    fun `recursive delete succeeds`() {
        val directory = testDirectory.resolve("recursive-directory").apply { mkdir() }
        val nestedDirectory = directory.resolve("nested").apply { mkdir() }
        nestedDirectory.resolve("child.txt").createNewFile()

        deleteFile(directory, recursive = true)

        assertFalse(directory.exists())
    }

    private fun createFile(file: File) {
        runBlocking {
            service.createFile(
                CreateFileRequest
                    .newBuilder()
                    .setPath(file.absolutePath)
                    .setIsDirectory(false)
                    .build(),
            )
        }
    }

    private fun deleteFile(
        file: File,
        recursive: Boolean,
    ) {
        runBlocking {
            service.deleteFile(
                DeleteFileRequest
                    .newBuilder()
                    .setPath(file.absolutePath)
                    .setRecursive(recursive)
                    .build(),
            )
        }
    }

    private fun createProtectedDanglingSymlink(
        parent: File,
        link: File,
    ): Set<PosixFilePermission> {
        val permissions =
            try {
                Files.getPosixFilePermissions(parent.toPath())
            } catch (_: UnsupportedOperationException) {
                assumeTrue("POSIX permissions are unavailable on this platform", false)
                return emptySet()
            }

        try {
            Files.createSymbolicLink(link.toPath(), parent.resolve("missing-target").toPath())
            Files.setPosixFilePermissions(parent.toPath(), permissions - PosixFilePermission.OWNER_WRITE)
        } catch (_: UnsupportedOperationException) {
            assumeTrue("Symbolic links are unavailable on this platform", false)
        } catch (_: IOException) {
            assumeTrue("The environment cannot create the dangling-symlink fixture", false)
        }
        return permissions
    }

    private fun withGrpcService(block: (FileSystemServiceGrpcKt.FileSystemServiceCoroutineStub) -> Unit) {
        val server: Server =
            NettyServerBuilder
                .forAddress(InetSocketAddress("127.0.0.1", 0))
                .addService(service)
                .build()
                .start()
        val channel: ManagedChannel =
            ManagedChannelBuilder
                .forAddress("127.0.0.1", server.port)
                .usePlaintext()
                .build()
        try {
            block(FileSystemServiceGrpcKt.FileSystemServiceCoroutineStub(channel))
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
