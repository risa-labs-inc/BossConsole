package ai.rever.boss.recovery

import ai.rever.boss.recovery.baseline.WorkspaceBaselineCapturer
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkspaceBaselineCapturerTest {

    private lateinit var tempProjectRoot: File

    @BeforeTest
    fun setup() {
        tempProjectRoot = kotlin.io.path.createTempDirectory("baseline-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempProjectRoot.deleteRecursively()
    }

    @Test
    fun `captureBaseline accurately captures tracked files and metadata`() = runBlocking {
        val srcDir = File(tempProjectRoot, "src").also { it.mkdirs() }
        val mainFile = File(srcDir, "Main.kt").also { it.writeText("fun main() { println(\"hello\") }") }
        val readmeFile = File(tempProjectRoot, "README.md").also { it.writeText("# Project") }

        val baseline = WorkspaceBaselineCapturer.captureBaseline(
            missionId = "mission-101",
            projectRoot = tempProjectRoot,
            gitBranch = "main",
            gitHeadCommit = "abc1234",
            gitUncommittedFiles = listOf("README.md")
        )

        assertEquals("mission-101", baseline.missionId)
        assertEquals("main", baseline.gitBranch)
        assertEquals("abc1234", baseline.gitHeadCommit)
        assertEquals(listOf("README.md"), baseline.gitUncommittedFiles)
        assertEquals(2, baseline.baselineFiles.size)

        assertTrue(baseline.baselineFiles.containsKey("src/Main.kt"))
        assertTrue(baseline.baselineFiles.containsKey("README.md"))

        val mainMeta = baseline.baselineFiles["src/Main.kt"]
        assertNotNull(mainMeta)
        assertEquals("src/Main.kt", mainMeta.relativePath)
        assertEquals(mainFile.length(), mainMeta.sizeBytes)
        assertTrue(mainMeta.sha256.isNotBlank())
    }

    @Test
    fun `captureBaseline excludes default ignored directories like git and node_modules`() = runBlocking {
        File(tempProjectRoot, "src").also { it.mkdirs() }
        File(tempProjectRoot, "src/App.kt").writeText("class App")

        // Excluded folders
        val gitDir = File(tempProjectRoot, ".git").also { it.mkdirs() }
        File(gitDir, "config").writeText("[core]")

        val nodeModulesDir = File(tempProjectRoot, "node_modules/pkg").also { it.mkdirs() }
        File(nodeModulesDir, "index.js").writeText("module.exports = {}")

        val buildDir = File(tempProjectRoot, "build").also { it.mkdirs() }
        File(buildDir, "output.bin").writeText("binary")

        val baseline = WorkspaceBaselineCapturer.captureBaseline(
            missionId = "mission-exclude-test",
            projectRoot = tempProjectRoot
        )

        assertEquals(1, baseline.baselineFiles.size)
        assertTrue(baseline.baselineFiles.containsKey("src/App.kt"))
        assertFalse(baseline.baselineFiles.containsKey(".git/config"))
        assertFalse(baseline.baselineFiles.containsKey("node_modules/pkg/index.js"))
        assertFalse(baseline.baselineFiles.containsKey("build/output.bin"))
    }

    @Test
    fun `calculateSha256 produces deterministic sha256 digest`() {
        val sampleFile = File(tempProjectRoot, "sample.txt")
        sampleFile.writeText("Deterministic Content Test")

        val hash1 = WorkspaceBaselineCapturer.calculateSha256(sampleFile)
        val hash2 = WorkspaceBaselineCapturer.calculateSha256(sampleFile)

        assertEquals(hash1, hash2)
        assertEquals(64, hash1.length) // 64 hex characters for SHA-256
    }
}
