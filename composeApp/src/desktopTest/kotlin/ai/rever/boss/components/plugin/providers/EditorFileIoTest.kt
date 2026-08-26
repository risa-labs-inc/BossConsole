package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.FileReadResult
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The host's whole surviving file-I/O surface, exercised through the provider
 * the editor-tab plugin actually calls -- so the mapping onto the plugin-api
 * [FileReadResult] is covered too, not just the internal [FileReadOutcome].
 *
 * `editor_write_file` had no test, and stack-overflowed: the override called
 * itself, because Kotlin resolves a member of the implicit receiver before a
 * top-level function of the same name and signature. A round trip is all it
 * took to see it.
 */
class EditorFileIoTest {
    private val provider = EditorContentProviderImpl()

    private fun tempFile(name: String): File =
        File.createTempFile("editor-file-io-", "-$name").also { it.deleteOnExit() }

    @Test
    fun `write then read round-trips`() {
        val file = tempFile("round-trip.txt")
        assertTrue(provider.writeFileContent(file.absolutePath, "hello editor"))
        val result = provider.readFileContent(file.absolutePath)
        assertIs<FileReadResult.Success>(result)
        assertEquals("hello editor", result.content)
    }

    @Test
    fun `write creates missing parent directories`() {
        val root = File(tempFile("parents.txt").parentFile, "editor-io-${System.nanoTime()}")
        val nested = File(root, "a/b/c.txt")
        assertTrue(provider.writeFileContent(nested.absolutePath, "nested"))
        assertTrue(nested.exists())
        root.deleteRecursively()
    }

    @Test
    fun `read of an absent path is FileNotFound`() {
        val missing = File(tempFile("gone.txt").parentFile, "definitely-absent-${System.nanoTime()}")
        assertIs<FileReadResult.FileNotFound>(provider.readFileContent(missing.absolutePath))
    }

    @Test
    fun `read past maxSize is FileTooLarge rather than a load`() {
        val file = tempFile("large.txt")
        file.writeText("more than one byte")
        val result = provider.readFileContent(file.absolutePath, maxSize = 1)
        assertIs<FileReadResult.FileTooLarge>(result)
        assertEquals(file.length(), result.sizeBytes)
        assertEquals(1, result.maxSizeBytes)
    }
}
