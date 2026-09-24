package ai.rever.boss.performance

import ai.rever.boss.utils.atomicWriteText
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the property `File.atomicWriteText` provides - the same property the
 * performance monitor's export, the workspace settings save, and several other
 * state writes rely on. A crash mid-write must leave at most a stray temp
 * sibling, never a truncated file at the destination.
 */
class AtomicWriteTextTest {
    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("atomic-write-text-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    /**
     * Successful atomic write: target ends up with the new bytes and no temp
     * file remains.
     */
    @Test
    fun `atomic write moves new bytes into place and removes the temp`() {
        val target = File(tempDir, "target.json")
        target.atomicWriteText("new content")

        assertEquals("new content", target.readText())

        val siblings = tempDir.listFiles()?.map { it.name }.orEmpty()
        assertTrue(
            siblings.none { it.endsWith(".tmp") },
            "no temp sibling should survive a successful write: $siblings",
        )
    }

    /**
     * When the destination already has bytes, the write replaces them
     * rather than appending or failing.
     */
    @Test
    fun `atomic write replaces existing bytes`() {
        val target =
            File(tempDir, "target.json").apply {
                writeText("stale content")
            }
        target.atomicWriteText("fresh content")

        assertEquals("fresh content", target.readText())
    }
}
