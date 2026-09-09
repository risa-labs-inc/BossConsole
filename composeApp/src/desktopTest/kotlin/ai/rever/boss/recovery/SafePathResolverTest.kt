package ai.rever.boss.recovery

import ai.rever.boss.recovery.paths.SafePathResolver
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafePathResolverTest {

    private lateinit var tempProjectRoot: File

    @BeforeTest
    fun setup() {
        tempProjectRoot = kotlin.io.path.createTempDirectory("safe-path-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempProjectRoot.deleteRecursively()
    }

    @Test
    fun `resolves valid child path within project root`() {
        val child = SafePathResolver.resolveSafeChild(tempProjectRoot, "src/main/App.kt")
        assertTrue(child.path.contains("src"))
        assertTrue(child.canonicalPath.startsWith(tempProjectRoot.canonicalPath))
    }

    @Test
    fun `rejects directory traversal escape with double dots`() {
        assertFailsWith<SecurityException> {
            SafePathResolver.resolveSafeChild(tempProjectRoot, "../outside.txt")
        }
    }

    @Test
    fun `rejects embedded directory traversal sequence`() {
        assertFailsWith<SecurityException> {
            SafePathResolver.resolveSafeChild(tempProjectRoot, "src/../../outside.txt")
        }
    }

    @Test
    fun `rejects null byte in path`() {
        assertFailsWith<SecurityException> {
            SafePathResolver.resolveSafeChild(tempProjectRoot, "src/main\u0000/App.kt")
        }
    }

    @Test
    fun `normalizes backslashes to forward slashes`() {
        val normalized = SafePathResolver.normalizeRelativePath("src\\test\\Sample.kt")
        assertEquals("src/test/Sample.kt", normalized)
    }

    @Test
    fun `correctly filters default excluded directories`() {
        val exclusions = setOf(".git", "node_modules", "build")
        assertTrue(SafePathResolver.isExcluded(".git", exclusions))
        assertTrue(SafePathResolver.isExcluded("node_modules", exclusions))
        assertTrue(SafePathResolver.isExcluded("build", exclusions))
        assertFalse(SafePathResolver.isExcluded("src", exclusions))
        assertFalse(SafePathResolver.isExcluded("README.md", exclusions))
    }

    @Test
    fun `isSafeDirectoryToRecurse accepts contained valid directories`() {
        val validSubdir = File(tempProjectRoot, "src/main").also { it.mkdirs() }
        val rootCanonical = SafePathResolver.canonicalRoot(tempProjectRoot)

        assertTrue(SafePathResolver.isSafeDirectoryToRecurse(validSubdir, rootCanonical))
        assertTrue(SafePathResolver.isSafeDirectoryToRecurse(rootCanonical, rootCanonical))
    }

    @Test
    fun `isSafeDirectoryToRecurse rejects directory outside project root`() {
        val externalDir = kotlin.io.path.createTempDirectory("outside-dir").toFile()
        try {
            val rootCanonical = SafePathResolver.canonicalRoot(tempProjectRoot)
            assertFalse(SafePathResolver.isSafeDirectoryToRecurse(externalDir, rootCanonical))
        } finally {
            externalDir.deleteRecursively()
        }
    }

    @Test
    fun `isContainedFile validates contained files and rejects external files`() {
        val internalFile = File(tempProjectRoot, "src/Main.kt").also {
            it.parentFile.mkdirs()
            it.writeText("code")
        }
        val externalDir = kotlin.io.path.createTempDirectory("outside-file-dir").toFile()
        val externalFile = File(externalDir, "Secret.kt").also { it.writeText("secret") }

        try {
            val rootCanonical = SafePathResolver.canonicalRoot(tempProjectRoot)
            assertTrue(SafePathResolver.isContainedFile(internalFile, rootCanonical))
            assertFalse(SafePathResolver.isContainedFile(externalFile, rootCanonical))
        } finally {
            externalDir.deleteRecursively()
        }
    }
}
