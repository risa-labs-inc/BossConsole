package ai.rever.boss.mcp

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `revoking a persisted ALLOW returns the tool to ASK and survives reload`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)

        engine.setToolPolicy("helm_upgrade", McpPolicyAction.ALLOW)
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("helm_upgrade"))

        assertTrue(engine.revokePersistedPolicy("helm_upgrade"))
        assertEquals(McpPolicyAction.ASK, engine.policyFor("helm_upgrade"))

        // The reset itself is a persisted write, not just an in-memory clear - a fresh engine
        // reading the same file must see ASK too, or a restart would quietly resurrect the ALLOW
        // this operator just revoked.
        val reloaded = McpPolicyEngine(policyFile = file)
        assertEquals(McpPolicyAction.ASK, reloaded.policyFor("helm_upgrade"))
    }

    @Test
    fun `revoking a persisted DENY also returns the tool to ASK`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)

        engine.setToolPolicy("k8s_delete", McpPolicyAction.DENY)
        assertEquals(McpPolicyAction.DENY, engine.policyFor("k8s_delete"))

        assertTrue(engine.revokePersistedPolicy("k8s_delete"))
        assertEquals(McpPolicyAction.ASK, engine.policyFor("k8s_delete"))
    }

    @Test
    fun `revoking clears session trust too, so the call asks again even with both grants held`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)

        // A tool that ended up with both a persisted ALLOW and separate session trust - policyFor
        // checks session trust before a non-DENY configured rule, so without also clearing trust
        // here, revoking only the persisted half would leave the call silently still auto-allowed.
        engine.setToolPolicy("run_command", McpPolicyAction.ALLOW)
        engine.trustForSession("run_command")
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command"))

        assertTrue(engine.revokePersistedPolicy("run_command"))
        assertEquals(McpPolicyAction.ASK, engine.policyFor("run_command"))
    }

    @Test
    fun `revoking one tool's policy leaves an unrelated DENY rule untouched`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)

        engine.setToolPolicy("docker_rm", McpPolicyAction.DENY)
        engine.setToolPolicy("helm_upgrade", McpPolicyAction.ALLOW)

        engine.revokePersistedPolicy("helm_upgrade")

        assertEquals(McpPolicyAction.ASK, engine.policyFor("helm_upgrade"))
        assertEquals(McpPolicyAction.DENY, engine.policyFor("docker_rm"))
    }

    @Test
    fun `a failed revocation write is reported, not silently swallowed`() {
        val file = createTempPolicyFile()
        var reportedFault: McpPolicyFault? = null
        // Constructed while the path is still absent, so this load sees no fault at all - the
        // write failure below has to be the only thing this test is actually exercising.
        val engine = McpPolicyEngine(policyFile = file, onFault = { reportedFault = it })
        engine.trustForSession("helm_upgrade")

        // atomicWriteText creates missing parent directories, so the failure has to be at the
        // target itself: turning the policy file's own path into a directory means the atomic
        // move at the end of the write has nothing it can replace.
        file.mkdirs()

        assertFalse(engine.revokePersistedPolicy("helm_upgrade"))
        assertIs<McpPolicyFault.PolicyPersistFailed>(reportedFault)
        // Session trust still cleared (in-memory, cannot fail); with no persisted rule and no
        // trust left, the tool falls back to its ordinary mutating default - ASK, not the DENY a
        // stuck PersistedPolicyUnreadable fault would force, which this test deliberately avoids.
        assertEquals(McpPolicyAction.ASK, engine.policyFor("helm_upgrade"))
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
