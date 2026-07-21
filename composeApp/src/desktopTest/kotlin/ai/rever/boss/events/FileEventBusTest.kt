package ai.rever.boss.events

import ai.rever.boss.plugin.events.FileValidationResult
import ai.rever.boss.components.events.ParsedFileReference
import ai.rever.boss.components.events.parseFileReference
import ai.rever.boss.components.events.stripFilePrefix
import ai.rever.boss.components.events.validateFilePath
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for FileEventBus helper functions.
 *
 * Tests cover:
 * - File reference parsing with line:column (Issue #402)
 * - Windows path handling
 * - URL decoding
 * - File path validation
 * - stripFilePrefix utility
 */
class FileEventBusTest {

    // ==================== stripFilePrefix Tests ====================

    @Test
    fun `stripFilePrefix handles file triple slash prefix`() {
        val result = stripFilePrefix("file:///path/to/file.kt")
        assertEquals("/path/to/file.kt", result)
    }

    @Test
    fun `stripFilePrefix handles file double slash prefix`() {
        val result = stripFilePrefix("file://path/to/file.kt")
        assertEquals("path/to/file.kt", result)
    }

    @Test
    fun `stripFilePrefix handles file single slash prefix`() {
        val result = stripFilePrefix("file:/path/to/file.kt")
        assertEquals("/path/to/file.kt", result)
    }

    @Test
    fun `stripFilePrefix handles file colon only prefix`() {
        val result = stripFilePrefix("file:path/to/file.kt")
        assertEquals("path/to/file.kt", result)
    }

    @Test
    fun `stripFilePrefix returns path unchanged when no prefix`() {
        val result = stripFilePrefix("/path/to/file.kt")
        assertEquals("/path/to/file.kt", result)
    }

    // ==================== parseFileReference Tests - Basic Paths ====================

    @Test
    fun `parseFileReference handles path only`() {
        val result = parseFileReference("/path/to/file.kt")
        assertEquals(ParsedFileReference("/path/to/file.kt", 0, 0), result)
    }

    @Test
    fun `parseFileReference handles path with line`() {
        val result = parseFileReference("/path/to/file.kt:123")
        assertEquals(ParsedFileReference("/path/to/file.kt", 123, 0), result)
    }

    @Test
    fun `parseFileReference handles path with line and column`() {
        val result = parseFileReference("/path/to/file.kt:123:45")
        assertEquals(ParsedFileReference("/path/to/file.kt", 123, 45), result)
    }

    // ==================== parseFileReference Tests - Windows Paths ====================

    @Test
    fun `parseFileReference handles Windows path only`() {
        val result = parseFileReference("C:\\Users\\test\\file.kt")
        assertEquals(ParsedFileReference("C:\\Users\\test\\file.kt", 0, 0), result)
    }

    @Test
    fun `parseFileReference handles Windows path with line`() {
        val result = parseFileReference("C:\\Users\\test\\file.kt:123")
        assertEquals(ParsedFileReference("C:\\Users\\test\\file.kt", 123, 0), result)
    }

    @Test
    fun `parseFileReference handles Windows path with line and column`() {
        val result = parseFileReference("C:\\Users\\test\\file.kt:123:45")
        assertEquals(ParsedFileReference("C:\\Users\\test\\file.kt", 123, 45), result)
    }

    @Test
    fun `parseFileReference handles lowercase Windows drive letter`() {
        val result = parseFileReference("d:\\projects\\file.kt:100")
        assertEquals(ParsedFileReference("d:\\projects\\file.kt", 100, 0), result)
    }

    // ==================== parseFileReference Tests - URL Encoding ====================

    @Test
    fun `parseFileReference decodes URL-encoded spaces`() {
        val result = parseFileReference("/path/with%20spaces/file.kt:100")
        assertEquals(ParsedFileReference("/path/with spaces/file.kt", 100, 0), result)
    }

    @Test
    fun `parseFileReference decodes multiple URL-encoded characters`() {
        val result = parseFileReference("/path%2Fwith%2Fencoded%2Fslashes/file.kt")
        assertEquals(ParsedFileReference("/path/with/encoded/slashes/file.kt", 0, 0), result)
    }

    @Test
    fun `parseFileReference handles invalid URL encoding gracefully`() {
        // %ZZ is not valid URL encoding
        val result = parseFileReference("/path/invalid%ZZencoding/file.kt")
        // Should fall back to original string
        assertEquals("/path/invalid%ZZencoding/file.kt", result.path)
    }

    // ==================== parseFileReference Tests - Edge Cases ====================

    @Test
    fun `parseFileReference handles empty string`() {
        val result = parseFileReference("")
        assertEquals(ParsedFileReference("", 0, 0), result)
    }

    @Test
    fun `parseFileReference handles filename with colon but no line number`() {
        // Colon followed by non-numeric should not be parsed as line
        val result = parseFileReference("/path/to/file:name.kt")
        assertEquals(ParsedFileReference("/path/to/file:name.kt", 0, 0), result)
    }

    @Test
    fun `parseFileReference handles multiple colons with only last being line number`() {
        // file:with:colons.txt:50 - only the last :50 should be line
        val result = parseFileReference("/path/file:with:colons.txt:50")
        assertEquals(ParsedFileReference("/path/file:with:colons.txt", 50, 0), result)
    }

    @Test
    fun `parseFileReference handles large line numbers`() {
        val result = parseFileReference("/file.kt:999999")
        assertEquals(ParsedFileReference("/file.kt", 999999, 0), result)
    }

    @Test
    fun `parseFileReference handles zero line number`() {
        val result = parseFileReference("/file.kt:0")
        assertEquals(ParsedFileReference("/file.kt", 0, 0), result)
    }

    // ==================== validateFilePath Tests ====================

    @Test
    fun `validateFilePath returns Invalid for empty path`() {
        val result = validateFilePath("")
        assertIs<FileValidationResult.Invalid>(result)
        assertEquals("Empty file path", result.reason)
    }

    @Test
    fun `validateFilePath returns Invalid for blank path`() {
        val result = validateFilePath("   ")
        assertIs<FileValidationResult.Invalid>(result)
        assertEquals("Empty file path", result.reason)
    }

    @Test
    fun `validateFilePath returns Invalid for non-existent file`() {
        val result = validateFilePath("/non/existent/path/to/file.kt")
        assertIs<FileValidationResult.Invalid>(result)
        assertTrue(result.reason.contains("does not exist"))
    }

    @Test
    fun `validateFilePath returns Invalid for directory`(@TempDir tempDir: Path) {
        val dir = tempDir.toFile()
        val result = validateFilePath(dir.absolutePath)
        assertIs<FileValidationResult.Invalid>(result)
        assertTrue(result.reason.contains("Not a file"))
    }

    @Test
    fun `validateFilePath returns Valid for existing readable file`(@TempDir tempDir: Path) {
        val file = File(tempDir.toFile(), "test.kt")
        file.writeText("test content")

        val result = validateFilePath(file.absolutePath)
        assertIs<FileValidationResult.Valid>(result)
        assertEquals(file.canonicalPath, result.canonicalPath)
    }

    @Test
    fun `validateFilePath resolves path traversal sequences`(@TempDir tempDir: Path) {
        val file = File(tempDir.toFile(), "test.kt")
        file.writeText("test content")

        // Path with .. should resolve to canonical path
        val pathWithTraversal = "${tempDir}/subdir/../test.kt"
        val result = validateFilePath(pathWithTraversal)
        assertIs<FileValidationResult.Valid>(result)
        assertEquals(file.canonicalPath, result.canonicalPath)
    }
}
