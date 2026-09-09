package ai.rever.boss.mcp

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class McpPolicyEngineTest {
    private val tempFiles = mutableListOf<File>()

    private fun createTempPolicyFile(): File {
        val dir =
            kotlin.io.path
                .createTempDirectory("mcp-policy-test")
                .toFile()
        return File(dir, "mcp-tool-policy.json").also { tempFiles.add(it) }
    }

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { it.parentFile?.deleteRecursively() }
        tempFiles.clear()
    }

    @Test
    fun `default mutating tools resolve to ASK while read-only tools resolve to ALLOW`() {
        val engine = McpPolicyEngine(policyFile = null)

        assertEquals(McpPolicyAction.ASK, engine.policyFor("k8s_delete"))
        assertEquals(McpPolicyAction.ASK, engine.policyFor("docker_rm"))
        assertEquals(McpPolicyAction.ASK, engine.policyFor("secret_get"))
        assertEquals(McpPolicyAction.ASK, engine.policyFor("codebase_write"))
        assertEquals(McpPolicyAction.ASK, engine.policyFor("run_command"))

        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("git_status"))
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("k8s_pods"))
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("codebase_read"))
    }

    @Test
    fun `explicit tool rule overrides default action`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)

        // Override mutating tool to ALLOW
        engine.setToolPolicy("docker_rm", McpPolicyAction.ALLOW)
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("docker_rm"))

        // Override read-only tool to DENY
        engine.setToolPolicy("git_status", McpPolicyAction.DENY)
        assertEquals(McpPolicyAction.DENY, engine.policyFor("git_status"))

        // Re-load engine from file and verify persistence
        val reloaded = McpPolicyEngine(policyFile = file)
        assertEquals(McpPolicyAction.ALLOW, reloaded.policyFor("docker_rm"))
        assertEquals(McpPolicyAction.DENY, reloaded.policyFor("git_status"))
    }

    @Test
    fun `session trust temporarily allows a mutating tool without disk modification`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)

        assertEquals(McpPolicyAction.ASK, engine.policyFor("k8s_delete"))

        engine.trustForSession("k8s_delete")
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("k8s_delete"))

        engine.revokeSessionTrust("k8s_delete")
        assertEquals(McpPolicyAction.ASK, engine.policyFor("k8s_delete"))

        // Ensure file was never written
        assertTrue(!file.exists())
    }

    @Test
    fun `corrupted policy file degrades to fail-closed configuration`() {
        val file = createTempPolicyFile()
        file.writeText("{ invalid json [")

        var reportedFault: McpPolicyFault? = null
        val engine = McpPolicyEngine(policyFile = file, onFault = { reportedFault = it })

        assertEquals(null, reportedFault)
        assertIs<McpPolicyFault.PersistedPolicyUnreadable>(engine.fault.value)

        // Defaults to fail-closed: all tools are denied
        assertEquals(McpPolicyAction.DENY, engine.policyFor("k8s_delete"))
        assertEquals(McpPolicyAction.DENY, engine.policyFor("run_command"))
        assertEquals(McpPolicyAction.DENY, engine.policyFor("git_status"))
        engine.setToolPolicy("run_command", McpPolicyAction.ASK)
        assertEquals(McpPolicyAction.DENY, engine.policyFor("git_status"))
    }

    @Test
    fun `explicit DENY rule overrides session trust`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)

        // Give tool session trust
        engine.trustForSession("danger_tool")
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("danger_tool"))

        // Set explicit rule to DENY
        engine.setToolPolicy("danger_tool", McpPolicyAction.DENY)

        // DENY always wins, even when session-trusted
        assertEquals(McpPolicyAction.DENY, engine.policyFor("danger_tool"))
    }
}
