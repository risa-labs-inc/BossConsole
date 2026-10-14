package ai.rever.boss.search

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The index walk is confined to the project root, and the relative path it reports is keyed
 * to the spelling the walk uses. Both are pinned here because both failed silently: a
 * confinement check that passes everything, and a display path cut at the wrong offset, look
 * exactly like a working indexer until you compare the output against the tree.
 *
 * Every case needs a directory link. Symlink creation is a privileged operation on Windows,
 * so these fall back to a junction (`mklink /J`), which is not - and skip only when the host
 * supports neither, rather than failing for a reason that is not the code under test.
 */
class FileIndexerConfinementTest {
    /**
     * Link [link] to [target], by symlink where that is permitted and by junction otherwise.
     *
     * Returns false when the host allows neither, which is the signal to skip: on Windows a
     * symlink needs SeCreateSymbolicLinkPrivilege, which an ordinary developer account and
     * most CI agents do not hold.
     */
    private fun linkDirectory(
        link: Path,
        target: Path,
    ): Boolean {
        val created = symbolicLink(link, target) || junctionDirectory(link, target)
        if (created) assertTrue(Files.exists(link), "created link must resolve: $link")
        return created
    }

    private fun symbolicLink(
        link: Path,
        target: Path,
    ): Boolean =
        try {
            Files.createSymbolicLink(link, target)
            true
        } catch (_: UnsupportedOperationException) {
            false
        } catch (_: FileSystemException) {
            false
        }

    /** `mklink /J`, which unlike a symlink needs no privilege, and unlike it is Windows only. */
    private fun junctionDirectory(
        link: Path,
        target: Path,
    ): Boolean {
        if (!System.getProperty("os.name").lowercase().contains("win")) return false
        val output = Files.createTempFile("junction-setup", ".log")
        try {
            val process =
                ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start()
            try {
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "mklink timed out")
                assumeTrue(process.exitValue() == 0, "mklink failed: ${Files.readString(output)}")
                return true
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        } finally {
            Files.deleteIfExists(output)
        }
    }

    private fun index(projectPath: String): List<IndexedFile> {
        val indexer = FileIndexer()
        runBlocking { indexer.indexProject(projectPath) }
        return indexer.indexedFiles.value
    }

    /**
     * The link target is named so that its path also begins with the project root's own path
     * (`<base>/proj` and `<base>/proj-secrets`), which is the second half of the defect: the
     * check compared path strings, so a sibling whose name merely starts with the root's was
     * treated as inside it. On Windows the junction is not resolved by canonicalPath at all,
     * so the same case covers both platforms' versions of the escape.
     */
    @Test
    fun `a directory link out of the project does not put outside files in the index`(
        @TempDir base: File,
    ) {
        val proj = File(base, "proj").apply { mkdirs() }
        File(proj, "Inside.kt").writeText("fun inside() {}")
        val outside = File(base, "proj-secrets").apply { mkdirs() }
        File(outside, "credentials.env").writeText("TOKEN=value")

        assumeTrue(
            linkDirectory(proj.toPath().resolve("link"), outside.toPath()),
            "host supports neither a symlink nor a junction",
        )

        val indexed = index(proj.absolutePath)

        assertTrue(indexed.any { it.name == "Inside.kt" }, "the project's own file is still indexed")
        assertFalse(
            indexed.any { it.name == "credentials.env" },
            "a file outside the project root must not be indexed: ${indexed.map { it.path }}",
        )
    }

    /**
     * Indexing through a link to the project exercises the relative-path offset, because the
     * root's absolute and resolved spellings then differ in length by a known amount. The
     * two names are deliberately different lengths: equal-length names hide the bug.
     */
    @Test
    fun `a root reached through a link still reports relative paths under that root`(
        @TempDir base: File,
    ) {
        val real = File(base, "realproject").apply { mkdirs() }
        File(real, "src").mkdirs()
        File(real, "src/Main.kt").writeText("fun main() {}")

        val link = base.toPath().resolve("ln")
        assumeTrue(
            linkDirectory(link, real.toPath()),
            "host supports neither a symlink nor a junction",
        )

        val indexed = index(link.toFile().absolutePath)

        assertEquals(1, indexed.size, "exactly the one source file: ${indexed.map { it.path }}")
        assertEquals("src${File.separatorChar}Main.kt", indexed.single().relativePath)
        assertEquals(link.resolve("src/Main.kt").toFile().absolutePath, indexed.single().path)
    }

    @Test
    fun `a directory link inside the project remains indexed`(
        @TempDir base: File,
    ) {
        val proj = File(base, "proj").apply { mkdirs() }
        val target = File(proj, "src").apply { mkdirs() }
        File(target, "Inside.kt").writeText("fun inside() {}")
        val link = proj.toPath().resolve("alias")
        assumeTrue(linkDirectory(link, target.toPath()), "host supports neither symlink nor junction")

        val indexed = index(proj.absolutePath)

        assertTrue(indexed.any { it.relativePath == "alias${File.separator}Inside.kt" })
        assertTrue(indexed.any { it.path == link.resolve("Inside.kt").toFile().absolutePath })
    }

    @Test
    fun `a file link outside the project is excluded`(
        @TempDir base: File,
    ) {
        val proj = File(base, "proj").apply { mkdirs() }
        File(proj, "Inside.kt").writeText("fun inside() {}")
        val outside = File(base, "proj-secret.txt").apply { writeText("outside") }
        val link = proj.toPath().resolve("linked.txt")
        assumeTrue(symbolicLink(link, outside.toPath()), "host cannot create file symlinks")
        assertTrue(Files.exists(link))

        assertEquals(listOf("Inside.kt"), index(proj.absolutePath).map { it.name })
    }

    @Test
    fun `an ordinary tree indexes with relative paths rooted at the project`(
        @TempDir base: File,
    ) {
        val proj = File(base, "proj").apply { mkdirs() }
        File(proj, "Root.kt").writeText("fun root() {}")
        File(proj, "a/b").mkdirs()
        File(proj, "a/b/Nested.kt").writeText("fun nested() {}")

        val byName = index(proj.absolutePath).associateBy { it.name }

        assertEquals(setOf("Root.kt", "Nested.kt"), byName.keys)
        assertEquals("Root.kt", byName.getValue("Root.kt").relativePath)
        assertEquals(
            listOf("a", "b", "Nested.kt").joinToString(File.separator),
            byName.getValue("Nested.kt").relativePath,
        )
    }
}
