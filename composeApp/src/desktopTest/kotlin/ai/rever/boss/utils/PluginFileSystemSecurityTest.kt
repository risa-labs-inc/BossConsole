package ai.rever.boss.utils

import org.junit.Assume.assumeNoException
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Comprehensive security tests for PluginFileSystemSecurity.
 *
 * Tests cover:
 * - Path traversal attacks (../, encoded variants)
 * - Path normalization and canonicalization
 * - Boundary enforcement (user home directory)
 * - Symlink escape prevention
 * - Allowed and denied paths
 * - Edge cases (null bytes, excessive length, etc.)
 */
class PluginFileSystemSecurityTest {
    private val homeDir = System.getProperty("user.home")
    private val testDir = File(homeDir, "plugin-security-test").apply { mkdirs() }

    @Test
    fun `allows access to files within user home directory`() {
        // Initialize with test directory
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        val testFile = File(testDir, "test.txt").apply { writeText("test content") }
        val result = PluginFileSystemSecurity.validateAndNormalizePath(testFile.absolutePath, "test")
        assertEquals(testFile.canonicalPath, result)
    }

    @Test
    fun `denies access to files outside user home directory`() {
        // Initialize with test directory only
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        // Remove home directory from allowed roots to force denial
        val homeFile = File(homeDir)
        PluginFileSystemSecurity.removeAllowedRoot(homeFile)

        // Test system directory access
        val systemPath = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "C:\\Windows\\System32"
        } else {
            "/etc/passwd"
        }

        val exception = assertFailsWith<SecurityException> {
            PluginFileSystemSecurity.validateAndNormalizePath(systemPath, "test")
        }
        assertTrue(exception.message!!.contains("outside all allowed filesystem roots"))
    }

    @Test
    fun `prevents simple path traversal with double dot`() {
        // Initialize with test directory only
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        // Remove home directory from allowed roots to force denial
        val homeFile = File(homeDir)
        PluginFileSystemSecurity.removeAllowedRoot(homeFile)

        val maliciousPath = "$homeDir/../../../etc/passwd"
        val exception = assertFailsWith<SecurityException> {
            PluginFileSystemSecurity.validateAndNormalizePath(maliciousPath, "test")
        }
        assertTrue(exception.message!!.contains("outside all allowed filesystem roots"))
    }

    @Test
    fun `prevents path traversal with encoded variants`() {
        // Initialize with test directory only
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        // Remove home directory from allowed roots to force denial
        val homeFile = File(homeDir)
        PluginFileSystemSecurity.removeAllowedRoot(homeFile)

        // Test various traversal patterns
        val traversalPatterns = listOf(
            "$homeDir/./../etc/passwd",
            "$homeDir/.../etc/passwd",
            "$homeDir/..//etc/passwd",
            "$homeDir/../../etc/passwd",
        )

        traversalPatterns.forEach { pattern ->
            val exception = assertFailsWith<SecurityException> {
                PluginFileSystemSecurity.validateAndNormalizePath(pattern, "test")
            }
            assertTrue(
                exception.message!!.contains("outside all allowed filesystem roots"),
                "Failed for pattern: $pattern",
            )
        }
    }

    @Test
    fun `rejects null bytes in path`() {
        val maliciousPath = "$homeDir/test\u0000file.txt"
        val exception = assertFailsWith<SecurityException> {
            PluginFileSystemSecurity.validateAndNormalizePath(maliciousPath, "test")
        }
        assertTrue(exception.message!!.contains("null byte"))
    }

    @Test
    fun `rejects excessively long paths`() {
        val longPath = "$homeDir/" + "a".repeat(100_000)
        val exception = assertFailsWith<SecurityException> {
            PluginFileSystemSecurity.validateAndNormalizePath(longPath, "test")
        }
        assertTrue(exception.message!!.contains("exceeds maximum length"))
    }

    @Test
    fun `rejects blank paths`() {
        val exception = assertFailsWith<SecurityException> {
            PluginFileSystemSecurity.validateAndNormalizePath("", "test")
        }
        assertTrue(exception.message!!.contains("cannot be blank"))
    }

    @Test
    fun `normalizes relative paths correctly`() {
        // Initialize with test directory
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        val testFile = File(testDir, "test.txt").apply { writeText("test content") }
        val relativePath = "./plugin-security-test/test.txt"
        val result = PluginFileSystemSecurity.validateAndNormalizePath(relativePath, "test")
        assertEquals(testFile.canonicalPath, result)
    }

    @Test
    fun `normalizes paths with current directory references`() {
        // Initialize with test directory
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        val testFile = File(testDir, "test.txt").apply { writeText("test content") }
        val pathWithDots = "$homeDir/./plugin-security-test/test.txt"
        val result = PluginFileSystemSecurity.validateAndNormalizePath(pathWithDots, "test")
        assertEquals(testFile.canonicalPath, result)
    }

    @Test
    fun `prevents symlink escape from allowed directory`() {
        // Initialize with test directory only
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        // Remove home directory from allowed roots to force denial
        val homeFile = File(homeDir)
        PluginFileSystemSecurity.removeAllowedRoot(homeFile)

        val testFile = File(testDir, "test.txt").apply { writeText("test content") }

        // Create a symlink that points outside the home directory
        val symlinkTarget = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "C:\\Windows"
        } else {
            "/etc"
        }

        val symlink = File(testDir, "escape-link")
        try {
            Files.createSymbolicLink(symlink.toPath(), Paths.get(symlinkTarget))
        } catch (e: Exception) {
            assumeNoException("Symbolic links unavailable on this system", e)
        }

        // Try to access a file through the symlink
        val maliciousPath = symlink.absolutePath
        val exception = assertFailsWith<SecurityException> {
            PluginFileSystemSecurity.validateAndNormalizePath(maliciousPath, "test")
        }
        assertTrue(exception.message!!.contains("outside all allowed filesystem roots"))
    }

    @Test
    fun `allows symlink within allowed directory`() {
        // Initialize with test directory
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        val targetFile = File(testDir, "target.txt").apply { writeText("target content") }
        val symlink = File(testDir, "safe-link")

        try {
            Files.createSymbolicLink(symlink.toPath(), targetFile.toPath())
        } catch (e: Exception) {
            assumeNoException("Symbolic links unavailable on this system", e)
        }

        // Access through symlink should be allowed since target is within boundary
        val result = PluginFileSystemSecurity.validateAndNormalizePath(symlink.absolutePath, "test")
        assertEquals(targetFile.canonicalPath, result)
    }

    @Test
    fun `validates child path within parent directory`() {
        // Initialize with test directory
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        val parentPath = testDir.absolutePath
        val childName = "child.txt"
        val result = PluginFileSystemSecurity.validateChildPath(parentPath, childName, "test")
        assertTrue(result.endsWith("child.txt"))
    }

    @Test
    fun `prevents child path traversal in child name`() {
        val parentPath = testDir.absolutePath
        val maliciousNames = listOf(
            "../escape.txt",
            "..\\escape.txt",
            "/etc/passwd",
            "\\Windows\\System32",
        )

        maliciousNames.forEach { name ->
            val exception = assertFailsWith<SecurityException> {
                PluginFileSystemSecurity.validateChildPath(parentPath, name, "test")
            }
            assertTrue(exception.message!!.contains("path traversal"), "Failed for name: $name")
        }
    }

    @Test
    fun `rejects null bytes in child name`() {
        val parentPath = testDir.absolutePath
        val maliciousName = "test\u0000file.txt"
        val exception = assertFailsWith<SecurityException> {
            PluginFileSystemSecurity.validateChildPath(parentPath, maliciousName, "test")
        }
        assertTrue(exception.message!!.contains("null byte"))
    }

    @Test
    fun `rejects blank child name`() {
        val parentPath = testDir.absolutePath
        val exception = assertFailsWith<SecurityException> {
            PluginFileSystemSecurity.validateChildPath(parentPath, "", "test")
        }
        assertTrue(exception.message!!.contains("cannot be blank"))
    }

    @Test
    fun `prevents child path escape from parent directory`() {
        val parentPath = testDir.absolutePath
        // Try to create a child that would escape the parent
        val escapeName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "..\\Windows\\System32"
        } else {
            "../etc/passwd"
        }

        val exception = assertFailsWith<SecurityException> {
            PluginFileSystemSecurity.validateChildPath(parentPath, escapeName, "test")
        }
        assertTrue(exception.message!!.contains("path traversal"))
    }

    @Test
    fun `handles platform-specific path separators correctly`() {
        // Initialize with test directory
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        val testFile = File(testDir, "test.txt").apply { writeText("test content") }

        // Test with forward slashes (should work on all platforms)
        val forwardSlashPath = testFile.absolutePath.replace("\\", "/")
        val result = PluginFileSystemSecurity.validateAndNormalizePath(forwardSlashPath, "test")
        assertEquals(testFile.canonicalPath, result)

        // Test with backslashes (Windows-specific)
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            val backslashPath = testFile.absolutePath.replace("/", "\\")
            val result2 = PluginFileSystemSecurity.validateAndNormalizePath(backslashPath, "test")
            assertEquals(testFile.canonicalPath, result2)
        }
    }

    @Test
    fun `allows access to home directory itself`() {
        // Initialize with test directory
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        val result = PluginFileSystemSecurity.validateAndNormalizePath(homeDir, "test")
        assertEquals(File(homeDir).canonicalPath, result)
    }

    @Test
    fun `prevents case-sensitivity bypass attempts`() {
        // Initialize with test directory
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        // On case-insensitive filesystems (Windows, macOS), ensure case variations
        // don't bypass security checks
        val testFile = File(testDir, "Test.txt").apply { writeText("test content") }

        // Try different case variations
        val variations = listOf(
            testDir.absolutePath.replace("plugin-security-test", "PLUGIN-SECURITY-TEST"),
            testDir.absolutePath.replace("plugin-security-test", "Plugin-Security-Test"),
        )

        variations.forEach { path ->
            val result = PluginFileSystemSecurity.validateAndNormalizePath(path + "/Test.txt", "test")
            // Should normalize to the same canonical path
            assertEquals(testFile.canonicalPath, result)
        }
    }

    @Test
    fun `provides clear error messages for security violations`() {
        // Initialize with test directory only
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        // Remove home directory from allowed roots to force denial
        val homeFile = File(homeDir)
        PluginFileSystemSecurity.removeAllowedRoot(homeFile)

        val systemPath = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "C:\\Windows\\System32"
        } else {
            "/etc/passwd"
        }

        val exception = assertFailsWith<SecurityException> {
            PluginFileSystemSecurity.validateAndNormalizePath(systemPath, "readFile")
        }

        // Verify error message contains helpful information
        assertTrue(exception.message!!.contains("Access denied"))
        assertTrue(exception.message!!.contains("allowed filesystem roots"))
        assertTrue(exception.message!!.contains("FilePickerProvider"))
    }

    @Test
    fun `handles unicode characters in paths correctly`() {
        // Initialize with test directory
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        val unicodeFileName = "test-файл.txt"
        val testFile = File(testDir, unicodeFileName).apply { writeText("test content") }

        val result = PluginFileSystemSecurity.validateAndNormalizePath(testFile.absolutePath, "test")
        assertEquals(testFile.canonicalPath, result)
    }

    @Test
    fun `prevents directory traversal through parent path`() {
        // Initialize with test directory only
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        // Remove home directory from allowed roots to force denial
        val homeFile = File(homeDir)
        PluginFileSystemSecurity.removeAllowedRoot(homeFile)

        val parentPath = testDir.absolutePath
        val maliciousParent = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "$homeDir\\..\\Windows"
        } else {
            "$homeDir/../etc"
        }

        val exception = assertFailsWith<SecurityException> {
            PluginFileSystemSecurity.validateChildPath(maliciousParent, "test.txt", "test")
        }
        assertTrue(exception.message!!.contains("outside all allowed filesystem roots"))
    }

    /**
     * Cleanup test directory after tests
     */
    fun cleanup() {
        testDir.deleteRecursively()
    }
}
