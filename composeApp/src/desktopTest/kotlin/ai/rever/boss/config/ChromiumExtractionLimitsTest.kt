package ai.rever.boss.config

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [ChromiumAutoDownloader.extractWithJava] already refused a zip entry that resolves outside
 * the target directory (zip-slip). It did not refuse a well-formed archive that simply streams
 * far more bytes than its central directory suggests (zip-bomb), one padded with an
 * unreasonable number of entries, or one that spends its budget on directories and long paths
 * instead of content. All of that is enforced against what is actually read from the stream and
 * named in the entries, never against `ZipEntry.getSize()`, which is unreliable in a streaming
 * zip and is attacker-controlled data either way.
 *
 * The limits are parameters of the function, so a test passes small ones and nothing global is
 * shared or restored.
 */
class ChromiumExtractionLimitsTest {
    private val tempDirs = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { runCatching { it.toFile().deleteRecursively() } }
        tempDirs.clear()
    }

    private fun zipOf(entries: List<Pair<String, ByteArray>>): Path {
        val dir = Files.createTempDirectory("chromium-extract-test")
        tempDirs.add(dir)
        val zipPath = dir.resolve("archive.zip")
        ZipOutputStream(Files.newOutputStream(zipPath)).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return zipPath
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): Path = zipOf(entries.toList())

    private fun targetDirFor(zipPath: Path): Path {
        val target = zipPath.parent.resolve("out")
        Files.createDirectories(target)
        return target
    }

    private fun overhead(name: String): Long = ChromiumAutoDownloader.entryOverheadBytes(name)

    @Test
    fun `a well-formed small archive extracts normally with the default limits`() {
        val zipPath = zipOf("hello.txt" to "hi".toByteArray())
        val target = targetDirFor(zipPath)

        ChromiumAutoDownloader.extractWithJava(zipPath, target)

        assertTrue(target.resolve("hello.txt").exists())
    }

    @Test
    fun `an archive streaming past the byte budget is refused, based on actual bytes not declared size`() {
        val name = "big.bin"
        val zipPath = zipOf(name to ByteArray(1000) { 'a'.code.toByte() })
        val target = targetDirFor(zipPath)

        assertFailsWith<SecurityException> {
            ChromiumAutoDownloader.extractWithJava(zipPath, target, maxBytes = overhead(name) + 100)
        }
    }

    @Test
    fun `bytes under separate entries are counted cumulatively against the same budget`() {
        val zipPath = zipOf("a.bin" to ByteArray(100), "b.bin" to ByteArray(100))
        val target = targetDirFor(zipPath)
        val budget = overhead("a.bin") + overhead("b.bin") + 150

        assertFailsWith<SecurityException> {
            ChromiumAutoDownloader.extractWithJava(zipPath, target, maxBytes = budget)
        }
    }

    @Test
    fun `content exactly at the byte budget extracts and one byte more is refused`() {
        val name = "edge.bin"
        val budget = overhead(name) + 100

        val atBudget = zipOf(name to ByteArray(100))
        val target = targetDirFor(atBudget)
        ChromiumAutoDownloader.extractWithJava(atBudget, target, maxBytes = budget)
        assertEquals(100L, Files.size(target.resolve(name)))

        val overBudget = zipOf(name to ByteArray(101))
        assertFailsWith<SecurityException> {
            ChromiumAutoDownloader.extractWithJava(overBudget, targetDirFor(overBudget), maxBytes = budget)
        }
    }

    @Test
    fun `an archive with more entries than the cap allows is refused before the extra entry is created`() {
        val entries = (1..5).map { "file$it.txt" to "x".toByteArray() }
        val zipPath = zipOf(entries)
        val target = targetDirFor(zipPath)

        assertFailsWith<SecurityException> {
            ChromiumAutoDownloader.extractWithJava(zipPath, target, maxEntries = 3)
        }
        assertTrue(target.resolve("file3.txt").exists())
        assertFalse(target.resolve("file4.txt").exists())
    }

    @Test
    fun `an archive with exactly the allowed number of entries extracts`() {
        val entries = (1..3).map { "file$it.txt" to "x".toByteArray() }
        val zipPath = zipOf(entries)
        val target = targetDirFor(zipPath)

        ChromiumAutoDownloader.extractWithJava(zipPath, target, maxEntries = 3)

        assertTrue((1..3).all { target.resolve("file$it.txt").exists() })
    }

    @Test
    fun `directory entries spend the same budget and cannot create directories past it`() {
        val first = "one/two/three/"
        val second = "four/five/six/"
        val zipPath = zipOf(first to ByteArray(0), second to ByteArray(0))
        val target = targetDirFor(zipPath)

        assertFailsWith<SecurityException> {
            ChromiumAutoDownloader.extractWithJava(zipPath, target, maxBytes = overhead(first) + overhead(second) - 1)
        }
        assertTrue(target.resolve(first).exists())
        assertFalse(target.resolve("four").exists())
    }

    @Test
    fun `directory entries exactly at the budget extract`() {
        val first = "one/two/three/"
        val second = "four/five/six/"
        val zipPath = zipOf(first to ByteArray(0), second to ByteArray(0))
        val target = targetDirFor(zipPath)

        ChromiumAutoDownloader.extractWithJava(zipPath, target, maxBytes = overhead(first) + overhead(second))

        assertTrue(target.resolve(second).exists())
    }

    @Test
    fun `a deeper path costs more than a shallow one with the same content`() {
        val shallow = "a.bin"
        val deep = "a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a/a.bin"

        assertTrue(overhead(deep) >= overhead(shallow) + 20 * ChromiumAutoDownloader.PATH_COMPONENT_COST_BYTES)
        val zipPath = zipOf(deep to ByteArray(1))
        assertFailsWith<SecurityException> {
            ChromiumAutoDownloader.extractWithJava(zipPath, targetDirFor(zipPath), maxBytes = overhead(deep) - 1)
        }
    }

    @Test
    fun `path bytes are charged, so a long name cannot ride for free`() {
        val name = "n".repeat(200)
        val zipPath = zipOf(name to ByteArray(0))
        assertEquals(200 + ChromiumAutoDownloader.PATH_COMPONENT_COST_BYTES, overhead(name))

        assertFailsWith<SecurityException> {
            ChromiumAutoDownloader.extractWithJava(zipPath, targetDirFor(zipPath), maxBytes = overhead(name) - 1)
        }
        val target = targetDirFor(zipPath).resolveSibling("out-ok")
        Files.createDirectories(target)
        ChromiumAutoDownloader.extractWithJava(zipPath, target, maxBytes = overhead(name))
        assertTrue(target.resolve(name).exists())
    }

    @Test
    fun `backslash separators count as path components, so a Windows-style path cannot dodge the depth charge`() {
        val slashed = "a/b/c/d.bin"
        val backslashed = "a\\b\\c\\d.bin"

        assertEquals(overhead(slashed), overhead(backslashed))
        assertEquals(
            backslashed.length + 4 * ChromiumAutoDownloader.PATH_COMPONENT_COST_BYTES,
            overhead(backslashed),
        )
        assertEquals(overhead("a/b/c/d/"), overhead("a\\b\\c\\d\\"))
        assertEquals(overhead("a/b\\c/d"), overhead("a/b/c/d"))
    }

    @Test
    fun `an archive of deep backslash paths is refused by the budget before anything is created`() {
        val first = "one\\two\\three\\file.bin"
        val second = "four\\five\\six\\file.bin"
        val zipPath = zipOf(first to ByteArray(0), second to ByteArray(0))
        val target = targetDirFor(zipPath)
        // Room for one four-component entry and its path bytes, but not for two. Counting a
        // backslash path as a single component would let both through.
        val budget = 4 * ChromiumAutoDownloader.PATH_COMPONENT_COST_BYTES + 200

        assertFailsWith<SecurityException> {
            ChromiumAutoDownloader.extractWithJava(zipPath, target, maxBytes = budget)
        }
        // The second entry is the one that tips the budget, so nothing of it may exist.
        assertFalse(target.resolve("four").exists())
    }

    @Test
    fun `an entry resolving outside the target directory is still refused (zip-slip)`() {
        val zipPath = zipOf("../escape.txt" to "gotcha".toByteArray())
        val target = targetDirFor(zipPath)

        assertFailsWith<SecurityException> {
            ChromiumAutoDownloader.extractWithJava(zipPath, target)
        }
        assertFalse(target.parent.resolve("escape.txt").exists())
    }
}
