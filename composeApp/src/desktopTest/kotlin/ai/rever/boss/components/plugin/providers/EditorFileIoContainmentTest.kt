package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.FileReadResult
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Adversarial spellings for the editor's plugin file doors - readFileContentSafe and
 * writeFileContentSafe, which serve the editor-tab plugin's editor_read_file and
 * editor_write_file MCP tools. Until this gate these were the one programmatic file
 * surface with no containment at all: /etc/passwd was readable and writable straight up,
 * and a symlink could aim the door anywhere. Every payload below is refused through the
 * provider the plugin actually calls, while ordinary files keep flowing.
 */
class EditorFileIoContainmentTest {
    private val provider = EditorContentProviderImpl()

    private fun tempDir(prefix: String) = Files.createTempDirectory(prefix).toFile()

    @Test
    fun `direct system paths are refused by the read door`() {
        val outcome = readFileContentSafe("/etc/passwd")
        assertIs<FileReadOutcome.Error>(outcome)
        assertTrue(outcome.message.contains("system"), outcome.message)
    }

    @Test
    fun `spelling variants of a system root are refused too`() {
        // Redundant separators collapse and a trailing separator names the same
        // component, so neither spelling can dodge the root check. The nonexistent
        // target pins the lexical layer itself: on a platform with no /etc to resolve,
        // only the spelling can refuse it.
        listOf(
            "///etc/passwd",
            "///etc/definitely-not-there",
            "/etc/",
            "/etc/./passwd",
        ).forEach { spelling ->
            assertIs<FileReadOutcome.Error>(readFileContentSafe(spelling), spelling)
        }
    }

    @Test
    fun `traversal sequences are refused by both doors`() {
        assertIs<FileReadOutcome.Error>(readFileContentSafe("../../etc/passwd"))
        assertFalse(writeFileContentSafe("../../etc/passwd", "nope"))
    }

    @Test
    fun `a symlink escape from an allowed directory is refused by the read door`() {
        val allowed = tempDir("editor-io-contain-link-")
        try {
            val link = allowed.toPath().resolve("innocent.txt")
            val linked = runCatching { Files.createSymbolicLink(link, Path.of("/etc/passwd")) }
            assumeTrue(linked.isSuccess, "platform refuses symlink creation")
            val outcome = readFileContentSafe(link.toString())
            assertIs<FileReadOutcome.Error>(outcome)
            assertTrue(outcome.message.contains("system"), outcome.message)
        } finally {
            allowed.deleteRecursively()
        }
    }

    @Test
    fun `a write through a symlinked system directory is refused`() {
        val allowed = tempDir("editor-io-contain-dirlink-")
        try {
            val dirLink = allowed.toPath().resolve("etcdir")
            val linked = runCatching { Files.createSymbolicLink(dirLink, Path.of("/etc")) }
            assumeTrue(linked.isSuccess, "platform refuses symlink creation")
            assertFalse(
                writeFileContentSafe(dirLink.resolve("boss-contain-probe.conf").toString(), "nope"),
            )
            assertFalse(File("/etc/boss-contain-probe.conf").exists(), "the write escaped the link")
        } finally {
            allowed.deleteRecursively()
        }
    }

    @Test
    fun `a direct write to a system path is refused even for a privileged process`() {
        val probe = "/etc/boss-contain-probe-${System.nanoTime()}.conf"
        assertFalse(writeFileContentSafe(probe, "nope"))
        assertFalse(File(probe).exists(), "the system path was created despite the refusal")
    }

    @Test
    fun `unparseable paths fail closed on both doors`() {
        assertIs<FileReadOutcome.Error>(readFileContentSafe("bad\u0000path"))
        assertFalse(writeFileContentSafe("bad\u0000path", "nope"))
    }

    @Test
    fun `the provider surfaces the refusal to the plugin api as an Error`() {
        val result = provider.readFileContent("/etc/passwd")
        assertIs<FileReadResult.Error>(result)
    }

    @Test
    fun `ordinary files keep reading and writing through the editor doors`() {
        val file = File.createTempFile("editor-io-contain-ok-", ".txt")
        file.deleteOnExit()
        assertTrue(writeFileContentSafe(file.absolutePath, "still allowed"), file.absolutePath)
        val outcome = readFileContentSafe(file.absolutePath)
        assertIs<FileReadOutcome.Success>(outcome)
        assertEquals("still allowed", outcome.content)
    }
}
