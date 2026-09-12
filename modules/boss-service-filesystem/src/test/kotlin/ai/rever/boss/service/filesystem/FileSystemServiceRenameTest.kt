package ai.rever.boss.service.filesystem

import ai.rever.boss.ipc.auth.ProcessAuthority
import ai.rever.boss.ipc.proto.services.RenameFileRequest
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import java.io.File
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
 *
 * These now run over the production authenticated transport: [FileSystemServiceImpl] refuses
 * callers without the kernel's host authority, so a direct call would fail before it ever
 * reached the rename.
 */
class FileSystemServiceRenameTest {
    private val dir: File =
        File.createTempFile("rename-svc-", "").let {
            it.delete()
            it.mkdirs()
            it
        }
    private val service = AuthenticatedFileService(FileSystemServiceImpl())
    private val stub = AuthenticatedFileService.stub(service.channelFor("host", ProcessAuthority.HOST))

    @AfterTest
    fun cleanUp() {
        service.close()
        dir.deleteRecursively()
    }

    private fun rename(
        from: File,
        to: File,
        overwrite: Boolean,
    ) = runBlocking {
        stub.renameFile(
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

        val failure = assertFailsWith<StatusException> { rename(source, dest, overwrite = false) }

        assertEquals(Status.Code.ALREADY_EXISTS, failure.status.code)
        assertEquals("old", dest.readText(), "the destination must survive a refused rename")
        assertTrue(source.exists(), "the source must survive a refused rename")
    }

    @Test
    fun `a missing source fails instead of reporting success`() {
        // The whole-platform half of the bug: renameTo returned false, the boolean was dropped,
        // and the caller was told the rename had happened.
        val source = File(dir, "does-not-exist.txt")
        val dest = File(dir, "dest.txt")

        val failure = assertFailsWith<StatusException> { rename(source, dest, overwrite = true) }

        assertEquals(Status.Code.NOT_FOUND, failure.status.code)
        assertFalse(dest.exists())
    }

    @Test
    fun `failures carry a description, because the status is the only channel there is`() {
        // gRPC does not leak exception messages: an IOException out of a handler reaches the caller
        // as a bare UNKNOWN. `Empty` has no errorMessage field to fall back on - unlike readFile and
        // writeFile here - so without an explicit status the plugin sees the same opaque failure for
        // a missing source as for a refused overwrite.
        val source = File(dir, "source.txt").apply { writeText("new") }
        val dest = File(dir, "dest.txt").apply { writeText("old") }

        val refused = assertFailsWith<StatusException> { rename(source, dest, overwrite = false) }
        val missing = assertFailsWith<StatusException> { rename(File(dir, "gone.txt"), dest, overwrite = true) }

        assertTrue(
            refused.status.description
                .orEmpty()
                .contains("dest.txt"),
            "got: ${refused.status.description}",
        )
        assertTrue(
            missing.status.description
                .orEmpty()
                .contains("gone.txt"),
            "got: ${missing.status.description}",
        )
        assertTrue(refused.status.code != missing.status.code, "distinct failures need distinct codes")
    }

    @Test
    fun `source path traversal is rejected before moving the file`() {
        val nested = File(dir, "nested").apply { mkdirs() }
        assertTraversalRejected(File(nested, "../source.txt"), File(dir, "dest.txt"))
    }

    @Test
    fun `destination path traversal is rejected before moving the file`() {
        val nested = File(dir, "nested").apply { mkdirs() }
        assertTraversalRejected(File(dir, "source.txt"), File(nested, "../dest.txt"))
    }

    private fun assertTraversalRejected(
        requestedSource: File,
        requestedDestination: File,
    ) {
        // Both paths resolve to valid files in this temporary directory. A missing validation
        // check would therefore move the source, rather than fail for an unrelated I/O reason.
        val source = File(dir, "source.txt").apply { writeText("content") }
        val dest = File(dir, "dest.txt")

        val failure =
            assertFailsWith<StatusException> {
                rename(requestedSource, requestedDestination, overwrite = true)
            }

        // The existing path validator throws IllegalArgumentException, mapped by gRPC to UNKNOWN.
        assertEquals(Status.Code.UNKNOWN, failure.status.code)
        assertEquals("content", source.readText(), "the source must survive a refused rename")
        assertFalse(dest.exists())
    }
}
