package ai.rever.boss.utils

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the atomic file-replacement helpers.
 *
 * These exist because of a platform split that hides itself during development: `File.renameTo`
 * replaces an existing destination on macOS and Linux (POSIX `rename(2)`) but fails on Windows
 * (`MoveFile` returns `ERROR_ALREADY_EXISTS`). The browser's favicon cache open-coded that call, so
 * on Windows it wrote each icon exactly once and every later save failed — and because the cache
 * survives restarts, that meant favicons stopped updating entirely.
 *
 * The overwrite tests below therefore only *fail* on Windows. They are worth keeping anyway: PR CI
 * runs `build-test (windows-latest)`, which is precisely the leg that would have caught it.
 */
class AtomicFileWriteTest {
    private val tempDir: File =
        File.createTempFile("atomic-write-", "").let {
            it.delete()
            it.mkdirs()
            it
        }

    @AfterTest
    fun cleanUp() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `atomicMoveFrom replaces a file that already exists`() {
        // The regression. On Windows this threw before the fix; on POSIX it always passed.
        val target = File(tempDir, "icon.png").apply { writeText("old") }
        val temp = File(tempDir, "icon.tmp").apply { writeText("new") }

        target.atomicMoveFrom(temp)

        assertEquals("new", target.readText())
        assertFalse(temp.exists(), "the source should have been moved, not copied")
    }

    @Test
    fun `atomicMoveFrom creates the file when it does not exist`() {
        val target = File(tempDir, "fresh.png")
        val temp = File(tempDir, "fresh.tmp").apply { writeText("content") }

        target.atomicMoveFrom(temp)

        assertEquals("content", target.readText())
    }

    @Test
    fun `atomicWriteText overwrites existing content`() {
        val target = File(tempDir, "registry.json")

        target.atomicWriteText("first")
        assertEquals("first", target.readText())

        target.atomicWriteText("second")
        assertEquals("second", target.readText())
    }

    @Test
    fun `atomicWriteText creates missing parent directories`() {
        val target = File(tempDir, "nested/deeper/registry.json")

        target.atomicWriteText("value")

        assertEquals("value", target.readText())
    }

    @Test
    fun `atomicWriteText leaves no temp files behind`() {
        // The temp file is a sibling of the target, so a leak would accumulate in the real cache
        // and config directories rather than in the OS temp dir.
        val target = File(tempDir, "clean.json")

        repeat(3) { target.atomicWriteText("write $it") }

        val strays = tempDir.listFiles()?.filter { it.name != target.name }.orEmpty()
        assertTrue(strays.isEmpty(), "unexpected leftovers: ${strays.map { it.name }}")
    }

    @Test
    fun `atomicWriteText restricts file permissions to owner only on posix filesystems`() {
        val target = File(tempDir, "owner-only.json")
        target.atomicWriteText("sensitive-content")

        assertEquals("sensitive-content", target.readText())

        val path = target.toPath()
        if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) == null) {
            return
        }

        val expected = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        val actual = Files.getPosixFilePermissions(path)
        assertEquals(expected, actual, "File permissions must be owner-only (0600), got: $actual")
    }

    @Test
    fun `atomicWriteText preserves owner only permissions across overwrites`() {
        val target = File(tempDir, "owner-only-overwrite.json")
        target.atomicWriteText("initial-content")
        target.atomicWriteText("updated-content")

        assertEquals("updated-content", target.readText())

        val path = target.toPath()
        if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) == null) {
            return
        }

        val expected = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        val actual = Files.getPosixFilePermissions(path)
        assertEquals(expected, actual, "File permissions must remain owner-only (0600) on overwrite, got: $actual")
    }

    @Test
    fun `atomicWriteText refuses a symlinked parent`() {
        // The redirect shape: the declared parent is a link to another directory, so an
        // unverified write lands the temp file and the moved-in target on the far side.
        val attackerDir = File(tempDir, "attacker").apply { mkdirs() }
        val link = File(tempDir, "linked-parent")
        try {
            Files.createSymbolicLink(link.toPath(), attackerDir.toPath())
        } catch (e: UnsupportedOperationException) {
            assumeTrue(false, "Filesystem does not support symlinks: ${e.message}")
        } catch (e: IOException) {
            assumeTrue(false, "Could not create a symlink (Windows needs privileges): ${e.message}")
        }

        assertFailsWith<IOException> { File(link, "stolen.json").atomicWriteText("payload") }

        assertFalse(
            File(attackerDir, "stolen.json").exists(),
            "the write must not land in the directory the link points at",
        )
        assertTrue(
            attackerDir.listFiles().orEmpty().none { it.name.endsWith(".tmp") },
            "no temp file may be created through the link either",
        )
    }

    @Test
    fun `ownership validation does not trust the overridable user name`() {
        val previous = System.getProperty("user.name")
        try {
            System.setProperty("user.name", "not-the-process-owner")
            val target = File(tempDir, "numeric-owner.json")
            target.atomicWriteText("owned")
            assertEquals("owned", target.readText())
        } finally {
            if (previous == null) System.clearProperty("user.name") else System.setProperty("user.name", previous)
        }
    }

    @Test
    fun `atomicWriteText flushes the temp bytes before publishing them`() {
        // The durability contract, observed from inside the flush step: the temp sibling must
        // already hold the new bytes while the live file still holds the previous ones. Reversed,
        // this order is exactly the window where power loss recovers the new file name over
        // zero-length contents, and the next launch silently resets the manager to defaults.
        val target = File(tempDir, "startup-settings.json").apply { writeText("previous") }
        var liveFileAtFlush: String? = null
        var tempBytesAtFlush: String? = null
        var tempNameAtFlush: String? = null

        target.atomicWriteText("durable") { flushing ->
            liveFileAtFlush = target.readText()
            tempBytesAtFlush = flushing.readText()
            tempNameAtFlush = flushing.name
        }

        assertEquals("durable", target.readText())
        assertEquals("previous", liveFileAtFlush, "the live file must outlive the flush")
        assertEquals("durable", tempBytesAtFlush, "the flush must run on the new bytes")
        assertTrue(
            tempNameAtFlush?.startsWith("startup-settings.json.") == true,
            "the flush must run on the temp sibling, got: $tempNameAtFlush",
        )
    }

    @Test
    fun `atomicWriteText still writes when an ancestor is a symlink`() {
        // macOS temp dirs live under /var -> /private/var, and users legitimately symlink
        // their config home elsewhere; only the immediate parent is refused as a link.
        val real = File(tempDir, "real-ancestor").apply { mkdirs() }
        val link = File(tempDir, "linked-ancestor")
        try {
            Files.createSymbolicLink(link.toPath(), real.toPath())
        } catch (e: UnsupportedOperationException) {
            assumeTrue(false, "Filesystem does not support symlinks: ${e.message}")
        } catch (e: IOException) {
            assumeTrue(false, "Could not create a symlink (Windows needs privileges): ${e.message}")
        }

        File(link, "nested").let { it.mkdirs() }
        val target = File(link, "nested/state.json")
        target.atomicWriteText("value")

        assertEquals("value", File(real, "nested/state.json").readText())
    }

    @Test
    fun `a refused flush keeps the previous file and leaves no temp behind`() {
        // Fail closed: a flush refusal means the bytes were never proven durable, so they must
        // not replace the live file. The next launch then reads the previous settings instead of
        // discovering a zero-length file and resetting every value to defaults.
        val target = File(tempDir, "zoom-settings.json").apply { writeText("previous") }

        val refusal =
            assertFailsWith<IOException> {
                target.atomicWriteText("never-published") {
                    throw IOException("disk refused the fsync")
                }
            }
        assertEquals("disk refused the fsync", refusal.message)

        assertEquals("previous", target.readText())
        val strays = tempDir.listFiles()?.filter { it.name != target.name }.orEmpty()
        assertTrue(strays.isEmpty(), "unexpected leftovers: ${strays.map { it.name }}")
    }

    @Test
    fun `concurrent writers publish whole snapshots, never interleaved bytes`() {
        // Two windows or a migration racing a UI save land on this same helper. Unique temp
        // siblings plus the atomic move mean a losing writer is discarded whole, so the target
        // always holds exactly one writer's snapshot. Interleaved bytes here are the
        // torn-settings-file bug in its raw form.
        val target = File(tempDir, "raced-settings.json")
        val writerCount = 8
        val roundsPerWriter = 25

        val writers =
            (0 until writerCount).map { writer ->
                thread {
                    repeat(roundsPerWriter) { round ->
                        target.atomicWriteText("writer-$writer-round-$round")
                    }
                }
            }
        writers.forEach { it.join() }

        val published = target.readText()
        assertTrue(
            Regex("writer-\\d+-round-\\d+").matches(published),
            "expected one writer's whole snapshot, got torn bytes: <$published>",
        )
    }

    @Test
    fun `renameAsideCorrupt preserves the bad file under a distinct name and empties the original path`() {
        val target = File(tempDir, "settings.json").apply { writeText("{ not valid json") }

        assertTrue(target.renameAsideCorrupt())

        assertFalse(target.exists(), "the original path must be free for a fresh write")
        val survivors = tempDir.listFiles().orEmpty().toList()
        assertEquals(1, survivors.size, "the corrupt bytes must not simply vanish")
        val corrupt = survivors.single()
        assertTrue(corrupt.name.startsWith("settings.json.corrupt-"), "unexpected name: ${corrupt.name}")
        assertEquals("{ not valid json", corrupt.readText(), "content must survive the rename untouched")
    }

    // Two recoveries of the same path must never replace the earlier aside, however close together
    // they land (the millisecond stamp alone would collide, and silently overwrite on POSIX).
    @Test
    fun `renameAsideCorrupt keeps every earlier aside when called repeatedly on the same name`() {
        val target = File(tempDir, "settings.json")
        val bodies = (1..5).map { "corrupt #$it" }

        bodies.forEach { body ->
            target.writeText(body)
            assertTrue(target.renameAsideCorrupt())
        }

        val kept =
            tempDir
                .listFiles()
                .orEmpty()
                .map { it.readText() }
                .sorted()
        assertEquals(bodies.sorted(), kept, "every corrupt copy must survive under its own name")
    }

    @Test
    fun `renameAsideCorrupt narrows the aside to owner-only where the filesystem has POSIX modes`() {
        val target = File(tempDir, "settings.json").apply { writeText("{ torn") }
        val posix = Files.getFileAttributeView(target.toPath(), PosixFileAttributeView::class.java)
        assumeTrue(posix != null, "needs POSIX file permissions")
        Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString("rw-r--r--"))

        assertTrue(target.renameAsideCorrupt())

        val aside = tempDir.listFiles().orEmpty().single()
        assertEquals(
            PosixFilePermissions.fromString("rw-------"),
            Files.getPosixFilePermissions(aside.toPath()),
        )
    }

    @Test
    fun `renameAsideCorrupt reports failure rather than throwing when there is nothing to rename`() {
        assertFalse(File(tempDir, "missing.json").renameAsideCorrupt())
    }

    // #1693: the repeated-call test above reaches the suffix branch only when two calls share a
    // millisecond. A fixed stamp and an aside already at that name make the collision certain.
    @Test
    fun `renameAsideCorrupt retries a taken name with a suffix and keeps both copies`() {
        val target = File(tempDir, "settings.json").apply { writeText("second corruption") }
        val earlier = File(tempDir, "settings.json.corrupt-1234").apply { writeText("first corruption") }

        val preserved = target.renameAsideCorruptFile(stamp = 1234)

        assertEquals("first corruption", earlier.readText(), "the earlier aside must not be replaced")
        assertEquals(File(tempDir, "settings.json.corrupt-1234-1"), preserved)
        assertEquals("second corruption", File(tempDir, "settings.json.corrupt-1234-1").readText())
        assertFalse(target.exists())
    }

    @Test
    fun `renameAsideCorrupt gives up without overwriting anything once every suffix is taken`() {
        val target = File(tempDir, "settings.json").apply { writeText("torn") }
        val taken =
            (0 until MAX_ASIDE_ATTEMPTS).map { attempt ->
                val suffix = if (attempt == 0) "" else "-$attempt"
                File(tempDir, "settings.json.corrupt-1234$suffix").apply { writeText("taken $attempt") }
            }

        assertEquals(null, target.renameAsideCorruptFile(stamp = 1234))

        assertEquals("torn", target.readText(), "the file stays where it was")
        taken.forEachIndexed { attempt, file -> assertEquals("taken $attempt", file.readText()) }
    }

    // #1692: the managers call this from recovery paths their object initializers reach, so
    // anything it throws fails the whole manager instead of leaving it on defaults. A NUL in the
    // name is a path java.nio refuses to represent; toPath() throws InvalidPathException for it.
    @Test
    fun `renameAsideCorrupt reports a name the filesystem cannot represent instead of throwing`() {
        assertFalse(File(tempDir, "bad\u0000name.json").renameAsideCorrupt())
    }
}
