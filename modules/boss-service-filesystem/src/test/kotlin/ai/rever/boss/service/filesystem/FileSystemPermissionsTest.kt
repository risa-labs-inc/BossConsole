package ai.rever.boss.service.filesystem

import ai.rever.boss.ipc.proto.services.CreateFileRequest
import ai.rever.boss.ipc.proto.services.ReadFileRequest
import ai.rever.boss.ipc.proto.services.ScanDirectoryRequest
import ai.rever.boss.ipc.proto.services.WriteFileRequest
import com.google.protobuf.ByteString
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileSystemPermissionsTest {
    @Test
    fun `a shallow scan lists inaccessible terminal directories without opening them`() =
        fixture { root ->
            val child = Files.createDirectory(root.resolve("child"))
            Files.setPosixFilePermissions(child, PosixFilePermissions.fromString("---------"))
            try {
                val result =
                    FileSystemServiceImpl().scanDirectory(
                        ScanDirectoryRequest.newBuilder().setPath(root.toString()).build(),
                    )
                assertEquals(listOf(child.toString()), result.entriesList.map { it.path })
            } finally {
                Files.setPosixFilePermissions(child, PosixFilePermissions.fromString("rwx------"))
            }
        }

    @Test
    fun `overwriting a write-only file does not require read permission`() =
        fixture { root ->
            val file = Files.writeString(root.resolve("file"), "original")
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("-w-------"))
            try {
                Files.writeString(file, "nio control")
                val result =
                    FileSystemServiceImpl().writeFile(
                        WriteFileRequest
                            .newBuilder()
                            .setPath(file.toString())
                            .setOverwrite(true)
                            .setContent(ByteString.copyFromUtf8("service write"))
                            .build(),
                    )
                assertTrue(result.success, result.errorMessage)
            } finally {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
            }
            assertEquals("service write", Files.readString(file))
        }

    @Test
    fun `known-file operations need search permission without listing the parent`() =
        fixture { root ->
            val parent = Files.createDirectory(root.resolve("parent"))
            val file = Files.writeString(parent.resolve("file"), "content")
            Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("-wx------"))
            try {
                assertEquals("content", Files.readString(file))
                val service = FileSystemServiceImpl()
                val result = service.readFile(ReadFileRequest.newBuilder().setPath(file.toString()).build())
                assertEquals("content", result.content.toStringUtf8(), result.errorMessage)
                service.createFile(CreateFileRequest.newBuilder().setPath(parent.resolve("created").toString()).build())
                assertTrue(Files.exists(parent.resolve("created")))
            } finally {
                Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"))
            }
        }

    private fun fixture(test: suspend (Path) -> Unit) =
        runBlocking {
            val root = Files.createTempDirectory("filesystem-permissions-")
            try {
                assumeTrue("POSIX permissions required", "posix" in root.fileSystem.supportedFileAttributeViews())
                test(root)
            } finally {
                Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            }
        }
}
