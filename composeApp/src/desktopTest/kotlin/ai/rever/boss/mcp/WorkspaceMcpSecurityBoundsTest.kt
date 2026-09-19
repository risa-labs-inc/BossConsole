package ai.rever.boss.mcp

import ai.rever.boss.cli.CLISecurityValidator
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `normalizePath resolves redundant slashes dots and traversals`() {
        assertEquals("/etc/passwd", CLISecurityValidator.normalizePath("/etc/./passwd"))
        assertEquals("/etc/shadow", CLISecurityValidator.normalizePath("/var/log/../../etc/shadow"))
        assertEquals("C:/Windows/System32", CLISecurityValidator.normalizePath("c:\\Windows\\System32"))
        assertEquals("C:/windows/system32", CLISecurityValidator.normalizePath("c:\\windows\\system32"))
        assertEquals("C:/Windows", CLISecurityValidator.normalizePath("C:/Windows/System32/.."))
        assertEquals("/", CLISecurityValidator.normalizePath("/"))
        assertEquals("C:/", CLISecurityValidator.normalizePath("c:\\"))
    }

    @Test
    fun `isRestrictedSystemPath flags sensitive operating system paths`() {
        // POSIX roots
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("/etc"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("/etc/shadow"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("/proc"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("/sys"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("/root"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("/dev"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("/boot"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("/bin"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("/usr/bin"))

        // Windows roots
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("C:\\Windows"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("c:/windows/system32"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("C:\\Program Files"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("C:\\Program Files (x86)"))

        // Bare roots
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("/"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("C:\\"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("c:/"))

        // Traversals into restricted roots
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("/var/log/../../etc/passwd"))
        assertTrue(CLISecurityValidator.isRestrictedSystemPath("C:\\Users\\..\\Windows\\System32"))

        // Safe user paths
        assertFalse(CLISecurityValidator.isRestrictedSystemPath("/home/user/workspace/repo"))
        assertFalse(CLISecurityValidator.isRestrictedSystemPath("C:\\Users\\developer\\projects\\boss"))
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
                    "/root",
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
}
