package ai.rever.boss.service.filesystem

import ai.rever.boss.ipc.proto.services.RenameFileRequest
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSystemCrossVolumeTest {
    @Test
    fun `rename across volumes preserves regular files links and empty directories`() =
        runBlocking {
            val root = Files.createTempDirectory("filesystem-move-source-").toRealPath()
            try {
                val other = secondVolume(root)
                assumeTrue("Cross-volume integration needs a second writable filesystem", other != null)
                val destination = Files.createTempDirectory(checkNotNull(other), "filesystem-move-destination-")
                try {
                    val service = FileSystemServiceImpl()
                    val source = Files.writeString(root.resolve("file"), "cross-volume content")
                    val modified = FileTime.fromMillis(1_750_000_000_123)
                    Files.setLastModifiedTime(source, modified)
                    move(service, source, destination.resolve("file"))
                    assertFalse(Files.exists(source))
                    assertEquals("cross-volume content", Files.readString(destination.resolve("file")))
                    assertEquals(modified, Files.getLastModifiedTime(destination.resolve("file")))
                    val protected = Files.writeString(root.resolve("protected"), "unchanged")
                    val link = Files.createSymbolicLink(root.resolve("link"), protected)
                    move(service, link, destination.resolve("link"))
                    assertEquals(protected, Files.readSymbolicLink(destination.resolve("link")))
                    assertEquals("unchanged", Files.readString(protected))
                    move(service, Files.createDirectory(root.resolve("empty")), destination.resolve("empty"))
                    assertTrue(Files.isDirectory(destination.resolve("empty")))
                    val replacement = Files.writeString(root.resolve("replacement"), "replacement")
                    val refused =
                        assertFailsWith<StatusException> {
                            move(service, replacement, destination.resolve("file"))
                        }
                    assertEquals(Status.Code.ALREADY_EXISTS, refused.status.code)
                    assertEquals("cross-volume content", Files.readString(destination.resolve("file")))
                    move(service, replacement, destination.resolve("file"), overwrite = true)
                    assertEquals("replacement", Files.readString(destination.resolve("file")))
                    assertFalse(Files.exists(replacement))
                    Files.list(destination).use { names ->
                        assertFalse(names.anyMatch { it.fileName.toString().startsWith(".boss-") })
                    }
                } finally {
                    removeTree(destination)
                }
            } finally {
                removeTree(root)
            }
        }

    private fun secondVolume(root: Path): Path? {
        val candidates =
            listOfNotNull(
                System.getenv("BOSS_TEST_SECOND_VOLUME"),
                System.getenv("GITHUB_WORKSPACE"),
                System.getProperty("user.home"),
                "/dev/shm",
            ).map(Path::of)
        return candidates.firstOrNull {
            Files.isDirectory(it) && Files.isWritable(it) && Files.getFileStore(it) != Files.getFileStore(root)
        }
    }

    private suspend fun move(
        service: FileSystemServiceImpl,
        source: Path,
        destination: Path,
        overwrite: Boolean = false,
    ) {
        service.renameFile(
            RenameFileRequest
                .newBuilder()
                .setSourcePath(source.toString())
                .setDestinationPath(destination.toString())
                .setOverwrite(overwrite)
                .build(),
        )
    }

    private fun removeTree(root: Path) {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}
