package ai.rever.boss.mcp

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Two plugins can ship a tool with the same name. A rule one of them earns must never overwrite
 * or erase the rule the other one holds, and an operator's DENY has to keep holding for the
 * plugin it was written against.
 *
 * These tests go through the engine's public write and read calls only, so they say the same
 * thing about a build that stores rules by tool name alone. There the second write replaced the
 * first, and a DENY wiped that way fell back to the read-only default, which is ALLOW.
 */
class McpCrossProviderRuleIsolationTest {
    private val tempFiles = mutableListOf<File>()

    private fun policyFile(): File {
        val dir =
            kotlin.io.path
                .createTempDirectory("mcp-policy-isolation-test")
                .toFile()
        return File(dir, "mcp-tool-policy.json").also { tempFiles.add(it) }
    }

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { it.parentFile?.deleteRecursively() }
        tempFiles.clear()
    }

    // list_files is read-only by name, so with no rule it resolves to the ALLOW default. That is
    // what makes an erased DENY visible: the tool goes from refused to running unprompted.
    private val tool = "list_files"

    @Test
    fun `one provider's ALLOW does not overwrite another provider's DENY`() {
        val engine = McpPolicyEngine(policyFile = policyFile())
        engine.setToolPolicy(tool, McpPolicyAction.DENY, providerId = "plugin-a")

        engine.setToolPolicy(tool, McpPolicyAction.ALLOW, providerId = "plugin-b")

        assertEquals(McpPolicyAction.DENY, engine.policyFor(tool, "plugin-a"))
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor(tool, "plugin-b"))
    }

    @Test
    fun `one provider's DENY does not overwrite another provider's ALLOW`() {
        val engine = McpPolicyEngine(policyFile = policyFile())
        engine.setToolPolicy("run_command", McpPolicyAction.ALLOW, providerId = "plugin-a")

        engine.setToolPolicy("run_command", McpPolicyAction.DENY, providerId = "plugin-b")

        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command", "plugin-a"))
        assertEquals(McpPolicyAction.DENY, engine.policyFor("run_command", "plugin-b"))
    }

    @Test
    fun `a section write for one provider leaves another provider's DENY in force`() {
        val engine = McpPolicyEngine(policyFile = policyFile())
        engine.setToolPolicy(tool, McpPolicyAction.DENY, providerId = "plugin-a")

        val outcome =
            engine.setSectionPolicies(
                listOf(
                    McpSectionPolicyChange(
                        tool,
                        "plugin-b",
                        engine.revocationVersion(tool, "plugin-b"),
                        null,
                        McpPolicyAction.ALLOW,
                    ),
                ),
            )

        assertEquals(McpProactivePolicyOutcome.Saved, outcome)
        assertEquals(McpPolicyAction.DENY, engine.policyFor(tool, "plugin-a"))
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor(tool, "plugin-b"))
    }

    @Test
    fun `one section write can carry the same tool name for two providers`() {
        val engine = McpPolicyEngine(policyFile = policyFile())

        val outcome =
            engine.setSectionPolicies(
                listOf(
                    McpSectionPolicyChange(tool, "plugin-a", 0L, null, McpPolicyAction.DENY),
                    McpSectionPolicyChange(tool, "plugin-b", 0L, null, McpPolicyAction.ALLOW),
                ),
            )

        assertEquals(McpProactivePolicyOutcome.Saved, outcome)
        assertEquals(McpPolicyAction.DENY, engine.policyFor(tool, "plugin-a"))
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor(tool, "plugin-b"))
    }

    @Test
    fun `a proactive add for one provider leaves another provider's DENY in force`() {
        val engine = McpPolicyEngine(policyFile = policyFile())
        engine.setToolPolicy(tool, McpPolicyAction.DENY, providerId = "plugin-a")

        val outcome =
            engine.setToolPolicyIfAbsent(
                tool,
                McpPolicyAction.ALLOW,
                engine.revocationVersion(tool, "plugin-b"),
                "plugin-b",
            )

        assertEquals(McpProactivePolicyOutcome.Saved, outcome)
        assertEquals(McpPolicyAction.DENY, engine.policyFor(tool, "plugin-a"))
    }

    @Test
    fun `an approval prompt's always-allow for one provider cannot lift another provider's DENY`() {
        val engine = McpPolicyEngine(policyFile = policyFile())
        engine.setToolPolicy(tool, McpPolicyAction.DENY, providerId = "plugin-a")

        // The approval path writes with preserveDeny, the way an impostor's "Always Allow" would.
        engine.setToolPolicy(tool, McpPolicyAction.ALLOW, preserveDeny = true, providerId = "plugin-b")

        assertEquals(McpPolicyAction.DENY, engine.policyFor(tool, "plugin-a"))
    }

    @Test
    fun `both providers' rules survive a restart`() {
        val file = policyFile()
        val engine = McpPolicyEngine(policyFile = file)
        engine.setToolPolicy(tool, McpPolicyAction.DENY, providerId = "plugin-a")
        engine.setToolPolicy(tool, McpPolicyAction.ALLOW, providerId = "plugin-b")

        val reloaded = McpPolicyEngine(policyFile = file)

        assertEquals(McpPolicyAction.DENY, reloaded.policyFor(tool, "plugin-a"))
        assertEquals(McpPolicyAction.ALLOW, reloaded.policyFor(tool, "plugin-b"))
        assertFalse(reloaded.fault.value is McpPolicyFault.PersistedPolicyUnreadable)
    }
}
