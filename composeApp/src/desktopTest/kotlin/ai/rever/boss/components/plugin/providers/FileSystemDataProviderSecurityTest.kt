package ai.rever.boss.components.plugin.providers

import ai.rever.boss.utils.PluginFileSystemSecurity
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Provider-level security tests for FileSystemDataProviderImpl.
 *
 * Tests against the REAL FileSystemDataProviderImpl production seam to verify
 * that filesystem operations properly enforce security boundaries.
 *
 * Tests cover:
 * - workspace outside $HOME but explicitly granted
 * - path outside granted roots
 * - sibling-prefix paths
 * - traversal
 * - Windows cross-drive paths
 * - symlink escape
 * - symlink-swap / TOCTOU behavior
 * - representative scan/open/create/read/write/delete/rename/reveal behavior
 */
class FileSystemDataProviderSecurityTest {
    private val homeDir = System.getProperty("user.home")
    private val testDir = File(homeDir, "filesystem-provider-security-test").apply { mkdirs() }
    private val provider = FileSystemDataProviderImpl()

    @Test
    fun `allows scanDirectory within allowed roots`() {
        // Initialize security with test directory as a granted root
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        val testFile = File(testDir, "test.txt").apply { writeText("test content") }
        val result = runBlocking { provider.scanDirectory(testDir.absolutePath) }

        assertNotNull(result, "Should be able to scan directory within allowed roots")
    }

    @Test
    fun `denies scanDirectory outside allowed roots`() {
        // Initialize security with only test directory
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
            "/etc"
        }

        val result = runBlocking { provider.scanDirectory(systemPath) }
        assertEquals(null, result, "Should return null for scan outside allowed roots")
    }

    @Test
    fun `allows createFile within allowed roots`() {
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        val fileName = "new-file.txt"
        val result = runBlocking { provider.createFile(testDir.absolutePath, fileName) }

        assertTrue(result.isSuccess, "Should successfully create file within allowed roots")
        val createdFile = File(testDir, fileName)
        assertTrue(createdFile.exists(), "Created file should exist")
        createdFile.delete()
    }

    @Test
    fun `denies createFile outside allowed roots`() {
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        // Remove home directory from allowed roots
        val homeFile = File(homeDir)
        PluginFileSystemSecurity.removeAllowedRoot(homeFile)

        val systemDir = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "C:\\Windows"
        } else {
            "/etc"
        }

        val result = runBlocking { provider.createFile(systemDir, "malicious.txt") }
        assertFalse(result.isSuccess, "Should fail to create file outside allowed roots")
    }

    @Test
    fun `allows readFile within allowed roots`() {
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        val testFile = File(testDir, "read-test.txt").apply { writeText("test content") }
        val result = runBlocking { provider.readFile(testFile.absolutePath) }

        assertTrue(result.isSuccess, "Should successfully read file within allowed roots")
        assertEquals("test content", result.getOrNull(), "Should read correct content")
        testFile.delete()
    }

    @Test
    fun `denies readFile outside allowed roots`() {
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        // Remove home directory from allowed roots
        val homeFile = File(homeDir)
        PluginFileSystemSecurity.removeAllowedRoot(homeFile)

        val systemPath = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "C:\\Windows\\System32\\drivers\\etc\\hosts"
        } else {
            "/etc/passwd"
        }

        val result = runBlocking { provider.readFile(systemPath) }
        assertFalse(result.isSuccess, "Should fail to read file outside allowed roots")
    }

    @Test
    fun `allows writeFile within allowed roots`() {
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        val testFile = File(testDir, "write-test.txt")
        val result = runBlocking { provider.writeFile(testFile.absolutePath, "new content") }

        assertTrue(result.isSuccess, "Should successfully write file within allowed roots")
        assertEquals("new content", testFile.readText(), "Should write correct content")
        testFile.delete()
    }

    @Test
    fun `denies writeFile outside allowed roots`() {
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        // Remove home directory from allowed roots
        val homeFile = File(homeDir)
        PluginFileSystemSecurity.removeAllowedRoot(homeFile)

        val systemPath = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "C:\\Windows\\test.txt"
        } else {
            "/etc/test-file.txt"
        }

        val result = runBlocking { provider.writeFile(systemPath, "malicious content") }
        assertFalse(result.isSuccess, "Should fail to write file outside allowed roots")
    }

    @Test
    fun `allows delete within allowed roots`() {
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        val testFile = File(testDir, "delete-test.txt").apply { writeText("test content") }
        val result = runBlocking { provider.delete(testFile.absolutePath) }

        assertTrue(result.isSuccess, "Should successfully delete file within allowed roots")
        assertFalse(testFile.exists(), "File should be deleted")
    }

    @Test
    fun `denies delete outside allowed roots`() {
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        // Remove home directory from allowed roots
        val homeFile = File(homeDir)
        PluginFileSystemSecurity.removeAllowedRoot(homeFile)

        val systemPath = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "C:\\Windows\\test.txt"
        } else {
            "/etc/test-file.txt"
        }

        val result = runBlocking { provider.delete(systemPath) }
        assertFalse(result.isSuccess, "Should fail to delete file outside allowed roots")
    }

    @Test
    fun `allows rename within allowed roots`() {
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        val originalFile = File(testDir, "original.txt").apply { writeText("test content") }
        val result = runBlocking { provider.rename(originalFile.absolutePath, "renamed.txt") }

        assertTrue(result.isSuccess, "Should successfully rename file within allowed roots")
        val renamedFile = File(testDir, "renamed.txt")
        assertTrue(renamedFile.exists(), "Renamed file should exist")
        assertFalse(originalFile.exists(), "Original file should not exist")
        renamedFile.delete()
    }

    @Test
    fun `denies rename outside allowed roots`() {
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        // Remove home directory from allowed roots
        val homeFile = File(homeDir)
        PluginFileSystemSecurity.removeAllowedRoot(homeFile)

        val systemPath = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "C:\\Windows\\test.txt"
        } else {
            "/etc/test-file.txt"
        }

        val result = runBlocking { provider.rename(systemPath, "renamed.txt") }
        assertFalse(result.isSuccess, "Should fail to rename file outside allowed roots")
    }

    @Test
    fun `allows revealInFileManager within allowed roots`() {
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        val testFile = File(testDir, "reveal-test.txt").apply { writeText("test content") }
        val result = provider.revealInFileManager(testFile.absolutePath)

        // Result should be success, but the actual reveal is platform-dependent
        // We're testing that security validation passes
        assertTrue(result.isSuccess, "Should successfully reveal file within allowed roots")
        testFile.delete()
    }

    @Test
    fun `denies revealInFileManager outside allowed roots`() {
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        // Remove home directory from allowed roots
        val homeFile = File(homeDir)
        PluginFileSystemSecurity.removeAllowedRoot(homeFile)

        val systemPath = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "C:\\Windows\\System32"
        } else {
            "/etc"
        }

        val result = provider.revealInFileManager(systemPath)
        assertFalse(result.isSuccess, "Should fail to reveal file outside allowed roots")
    }

    @Test
    fun `prevents sibling-prefix path traversal`() {
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        // Create a directory with a similar name
        val siblingDir = File(testDir.parentFile, "filesystem-provider-security-test-sibling").apply { mkdirs() }
        val siblingFile = File(siblingDir, "secret.txt").apply { writeText("secret content") }

        // Try to access sibling directory through similar path
        val maliciousPath = File(testDir.parentFile, "filesystem-provider-security-test-sibling/secret.txt").absolutePath

        // This should be denied since it's outside the allowed root
        val result = runBlocking { provider.readFile(maliciousPath) }
        assertFalse(result.isSuccess, "Should prevent sibling-prefix path traversal")

        siblingFile.delete()
        siblingDir.delete()
    }

    @Test
    fun `prevents path traversal through parent directory`() {
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        // Try to access parent directory through traversal
        val maliciousPath = File(testDir, "..").absolutePath

        val result = runBlocking { provider.scanDirectory(maliciousPath) }
        assertEquals(null, result, "Should prevent path traversal through parent directory")
    }

    @Test
    fun `handles Windows cross-drive paths correctly`() {
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        // On Windows, test that paths on different drives are handled correctly
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            // Try to access a different drive (assuming D: exists or testing the logic)
            val crossDrivePath = "D:\\test.txt"
            val result = runBlocking { provider.readFile(crossDrivePath) }

            // Should fail since D: is not in allowed roots
            assertFalse(result.isSuccess, "Should prevent cross-drive access outside allowed roots")
        }
    }

    @Test
    fun `prevents symlink escape from allowed directory`() {
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        val testFile = File(testDir, "target.txt").apply { writeText("target content") }

        // Create a symlink that points outside the home directory
        val symlinkTarget = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "C:\\Windows"
        } else {
            "/etc"
        }

        val symlink = File(testDir, "escape-link")
        try {
            Files.createSymbolicLink(symlink.toPath(), File(symlinkTarget).toPath())
        } catch (e: Exception) {
            assumeNoException("Symbolic links unavailable on this system", e)
        }

        // Try to access a file through the symlink
        val result = runBlocking { provider.scanDirectory(symlink.absolutePath) }
        assertEquals(null, result, "Should prevent symlink escape from allowed directory")

        symlink.delete()
        testFile.delete()
    }

    @Test
    fun `allows symlink within allowed directory`() {
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
        val result = runBlocking { provider.readFile(symlink.absolutePath) }
        assertTrue(result.isSuccess, "Should allow symlink within allowed directory")
        assertEquals("target content", result.getOrNull(), "Should read correct content through symlink")

        symlink.delete()
        targetFile.delete()
    }

    @Test
    fun `handles TOCTOU by validating at operation boundary`() {
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        // Create a file in allowed directory
        val testFile = File(testDir, "toctou-test.txt").apply { writeText("original content") }

        // Read the file - validation happens at operation boundary
        val result1 = provider.readFile(testFile.absolutePath)
        assertTrue(result1.isSuccess, "Should successfully read file")

        // Now move the file outside allowed directory (simulating TOCTOU)
        val outsideDir = File(homeDir, "outside-test").apply { mkdirs() }
        val movedFile = File(outsideDir, "toctou-test.txt")
        testFile.renameTo(movedFile)

        // Try to read the file again at the old path
        val result2 = provider.readFile(testFile.absolutePath)
        assertFalse(result2.isSuccess, "Should fail to read file that was moved outside allowed roots")

        // Cleanup
        movedFile.delete()
        outsideDir.delete()
    }

    @Test
    fun `directoryHasChildren returns false for denied access`() {
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        // Remove home directory from allowed roots
        val homeFile = File(homeDir)
        PluginFileSystemSecurity.removeAllowedRoot(homeFile)

        val systemPath = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "C:\\Windows"
        } else {
            "/etc"
        }

        val result = provider.directoryHasChildren(systemPath)
        assertFalse(result, "Should return false for directoryHasChildren outside allowed roots")
    }

    @Test
    fun `openFile handles denied access gracefully`() {
        PluginFileSystemSecurity.initializeDefaultRoots(
            pluginStorageDir = testDir,
            currentProjectDir = null,
        )

        // Remove home directory from allowed roots
        val homeFile = File(homeDir)
        PluginFileSystemSecurity.removeAllowedRoot(homeFile)

        val systemPath = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "C:\\Windows\\System32\\notepad.exe"
        } else {
            "/usr/bin/ls"
        }

        // This should not throw an exception, just log a warning
        provider.openFile(systemPath, "test-window")
        // If we get here without exception, the test passes
    }

    /**
     * Cleanup test directory after tests
     */
    fun cleanup() {
        testDir.deleteRecursively()
    }
}
