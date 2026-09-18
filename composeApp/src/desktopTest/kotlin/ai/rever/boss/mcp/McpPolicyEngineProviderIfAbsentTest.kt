package ai.rever.boss.mcp

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * [McpPolicyEngine.setProviderPolicyIfAbsent]: a provider rule written by something the operator did
 * not answer directly, such as a plugin pack, must never replace a rule the operator chose.
 */
class McpPolicyEngineProviderIfAbsentTest {
    private val dirs = mutableListOf<File>()

    private fun policyFile(): File {
        val dir =
            kotlin.io.path
                .createTempDirectory("mcp-provider-absent-test")
                .toFile()
                .also { dirs += it }
        return File(dir, "mcp-tool-policy.json")
    }

    @AfterTest
    fun cleanup() {
        dirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `writes when the provider has no rule, and it survives reload`() {
        val file = policyFile()
        val engine = McpPolicyEngine(policyFile = file)

        val outcome = engine.setProviderPolicyIfAbsent("com.example", McpPolicyAction.ALLOW)

        assertEquals(McpProactivePolicyOutcome.Saved, outcome)
        val reloaded = McpPolicyEngine(policyFile = file).config.value.providerRules
        assertEquals(McpPolicyAction.ALLOW, reloaded["com.example"])
    }

    @Test
    fun `an existing provider rule in either direction is kept`() {
        for ((existing, requested) in listOf(
            McpPolicyAction.DENY to McpPolicyAction.ALLOW,
            McpPolicyAction.ALLOW to McpPolicyAction.DENY,
        )) {
            val file = policyFile()
            val engine = McpPolicyEngine(policyFile = file)
            engine.setProviderPolicy("com.example", existing)

            assertEquals(McpProactivePolicyOutcome.Refused, engine.setProviderPolicyIfAbsent("com.example", requested))
            assertEquals(existing, McpPolicyEngine(policyFile = file).config.value.providerRules["com.example"])
        }
    }

    @Test
    fun `a provider ALLOW never loosens a tool rule, which still outranks it`() {
        val engine = McpPolicyEngine(policyFile = policyFile())
        engine.setToolPolicy("run_command", McpPolicyAction.ASK)

        engine.setProviderPolicyIfAbsent("terminal", McpPolicyAction.ALLOW)

        assertEquals(McpPolicyAction.ASK, engine.policyFor("run_command", "terminal"))
    }

    @Test
    fun `a damaged policy file is never overwritten`() {
        val file = policyFile()
        file.writeText("broken policy")
        val engine = McpPolicyEngine(policyFile = file)

        val outcome = engine.setProviderPolicyIfAbsent("com.example", McpPolicyAction.ALLOW)

        assertEquals(McpProactivePolicyOutcome.PolicyUnreadable, outcome)
        assertEquals("broken policy", file.readText())
        assertIs<McpPolicyFault.PersistedPolicyUnreadable>(engine.fault.value)
    }
}
