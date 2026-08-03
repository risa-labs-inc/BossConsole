package ai.rever.boss.service.filesystem

import ai.rever.boss.ipc.proto.services.RenameFileRequest
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.NoSuchFileException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [FileSystemServiceImpl.renameFile].
 *
 * Two defects, one of which only shows on one platform:
 *
 *  - `overwrite = true` used `File.renameTo`, which does not replace an existing destination on
 *    Windows (`MoveFile` → `ERROR_ALREADY_EXISTS`) but does on macOS and Linux (`rename(2)`). The
 *    request the API explicitly offers was the one that could not work there.
 *  - The boolean result was discarded and `Empty` returned unconditionally, so every failure —
 *    on every platform — was reported to the caller as success.
 *
 * The overwrite test can therefore only fail on `build-test (windows-latest)`; the rest fail
 * everywhere.
 */
class FileSystemServiceRenameTest {
    private val service = FileSystemServiceImpl()

    private val dir: File =
        File.createTempFile("rename-svc-", "").let {
            it.delete()
            it.mkdirs()
            it
        }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun rename(
        from: File,
        to: File,
        overwrite: Boolean,
    ) = runBlocking {
        service.renameFile(
            RenameFileRequest
                .newBuilder()
                .setSourcePath(from.absolutePath)
                .setDestinationPath(to.absolutePath)
                .setOverwrite(overwrite)
                .build(),
        )
    }

    @Test
    fun `overwrite replaces an existing destination`() {
        val source = File(dir, "source.txt").apply { writeText("new") }
        val dest = File(dir, "dest.txt").apply { writeText("old") }

        rename(source, dest, overwrite = true)

        assertEquals("new", dest.readText())
        assertFalse(source.exists(), "the source should have been moved, not copied")
    }

    @Test
    fun `a plain rename moves the file`() {
        val source = File(dir, "source.txt").apply { writeText("content") }
        val dest = File(dir, "moved.txt")

        rename(source, dest, overwrite = false)

        assertEquals("content", dest.readText())
        assertFalse(source.exists())
    }

    @Test
    fun `without overwrite an existing destination is refused and left alone`() {
        val source = File(dir, "source.txt").apply { writeText("new") }
        val dest = File(dir, "dest.txt").apply { writeText("old") }

        assertFailsWith<IllegalStateException> { rename(source, dest, overwrite = false) }

        assertEquals("old", dest.readText(), "the destination must survive a refused rename")
        assertTrue(source.exists(), "the source must survive a refused rename")
    }

    @Test
    fun `a missing source fails instead of reporting success`() {
        // The whole-platform half of the bug: renameTo returned false, the boolean was dropped,
        // and the caller was told the rename had happened.
        val source = File(dir, "does-not-exist.txt")
        val dest = File(dir, "dest.txt")

        assertFailsWith<NoSuchFileException> { rename(source, dest, overwrite = true) }

        assertFalse(dest.exists())
    }

    @Test
    fun `path traversal is still rejected`() {
        // validatePath runs before any I/O; pinned so the rewrite cannot have moved it.
        assertFailsWith<IllegalArgumentException> {
            runBlocking {
                service.renameFile(
                    RenameFileRequest
                        .newBuilder()
                        .setSourcePath("${dir.absolutePath}/../escape.txt")
                        .setDestinationPath(File(dir, "dest.txt").absolutePath)
                        .setOverwrite(true)
                        .build(),
                )
            }
        }
    }
}
