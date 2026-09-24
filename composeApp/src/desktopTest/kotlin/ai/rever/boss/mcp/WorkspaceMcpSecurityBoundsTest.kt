package ai.rever.boss.mcp

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.abort
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.FileSystemException
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * Defensive path bounds tests ensuring system paths and directory traversal payloads
 * cannot be opened or created as workspaces or terminal working directories.
 */
class WorkspaceMcpSecurityBoundsTest {
    private fun createTestCore(): McpToolRegistryCore {
        val policyEngine = McpPolicyEngine(policyFile = null)
        policyEngine.setProviderPolicy("boss-workspace", McpPolicyAction.ALLOW)
        val core = McpToolRegistryCore(disabledFile = null, policyEngine = policyEngine)
        core.registerProvider(WorkspaceMcpToolProvider)
        return core
    }

    @BeforeEach
    fun setUp() {
        WorkspaceMcpToolProvider.windowCreator = { "test-window-1" }
        WorkspaceMcpToolProvider.splitViewStateResolver = { null }
        WorkspaceMcpToolProvider.splitViewWaitTimeoutMs = 50L
    }

    @AfterEach
    fun tearDown() {
        WorkspaceMcpToolProvider.windowCreator = null
        WorkspaceMcpToolProvider.splitViewStateResolver = null
        WorkspaceMcpToolProvider.splitViewWaitTimeoutMs = 5000L
    }

    @Test
    fun `open_workspace refuses restricted system paths`() =
        runBlocking {
            val core = createTestCore()
            val restrictedTargets =
                listOf(
                    "/etc",
                    "/sys",
                    "/proc",
                    "C:/Windows",
                    "C:\\Program Files",
                )

            for (target in restrictedTargets) {
                val escaped = target.replace("\\", "\\\\")
                val result = core.invoke("open_workspace", """{"projectPath":"$escaped"}""")
                assertTrue(result.isError, "Target '$target' should be rejected as error")
                assertTrue(
                    result.text.contains("restricted system directory"),
                    "Expected restriction error for '$target', got: ${result.text}",
                )
            }
        }

    @Test
    fun `open_terminal refuses restricted working directory`() =
        runBlocking {
            val core = createTestCore()
            val result = core.invoke("open_terminal", """{"workingDirectory":"/etc"}""")
            assertTrue(result.isError)
            assertTrue(
                result.text.contains("restricted system directory"),
                "Expected restriction error, got: ${result.text}",
            )
        }

    @Test
    fun `create_workspace refuses restricted project path`() =
        runBlocking {
            val core = createTestCore()
            val result = core.invoke("create_workspace", """{"name":"test","projectPath":"/etc"}""")
            assertTrue(result.isError)
            assertTrue(
                result.text.contains("restricted system directory"),
                "Expected restriction error, got: ${result.text}",
            )
        }

    @Test
    fun `open_terminal refuses symlink pointing to restricted system directory`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val target = File("/etc")
        assumeTrue(target.isDirectory, "/etc must exist and be a directory for POSIX symlink test")

        val link = File(tempDir, "symlink-to-etc")
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (e: UnsupportedOperationException) {
            abort("no symlink support: ${e.message}")
        } catch (e: FileSystemException) {
            abort("symlink creation refused: ${e.message}")
        }

        val core = createTestCore()
        val result = core.invoke("open_terminal", """{"workingDirectory":"${link.absolutePath}"}""")
        assertTrue(result.isError, "Symlink to restricted directory should be rejected")
        assertTrue(
            result.text.contains("restricted system directory"),
            "Expected restriction error, got: ${result.text}",
        )
        assertTrue(
            result.text.contains("canonical path"),
            "Expected refusal to specifically cite canonical path branch, got: ${result.text}",
        )
    }

    @Test
    fun `open_workspace refuses symlink pointing to restricted system directory`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val target = File("/etc")
        assumeTrue(target.isDirectory, "/etc must exist and be a directory for POSIX symlink test")

        val link = File(tempDir, "symlink-to-etc-workspace")
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (e: UnsupportedOperationException) {
            abort("no symlink support: ${e.message}")
        } catch (e: FileSystemException) {
            abort("symlink creation refused: ${e.message}")
        }

        val core = createTestCore()
        val result = core.invoke("open_workspace", """{"projectPath":"${link.absolutePath}"}""")
        assertTrue(result.isError, "Symlink to restricted directory should be rejected")
        assertTrue(
            result.text.contains("restricted system directory"),
            "Expected restriction error, got: ${result.text}",
        )
        assertTrue(
            result.text.contains("canonical path"),
            "Expected refusal to specifically cite canonical path branch, got: ${result.text}",
        )
    }
}
