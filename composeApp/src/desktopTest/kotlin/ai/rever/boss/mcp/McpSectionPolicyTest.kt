package ai.rever.boss.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class McpSectionPolicyTest {
    private fun changes(engine: McpPolicyEngine) =
        listOf("read", "write").map {
            McpSectionPolicyChange(
                it,
                "plugin",
                engine.revocationVersion(it, "plugin"),
                engine.config.value.rules[it],
                McpPolicyAction.ALLOW,
            )
        }

    @Test fun `section saves every rule and invalidates previous queued grants`() {
        val engine = McpPolicyEngine()
        engine.setToolPolicy("write", McpPolicyAction.DENY)
        val snapshot = changes(engine)
        assertIs<McpProactivePolicyOutcome.Saved>(engine.setSectionPolicies(snapshot))
        assertEquals(
            mapOf("read" to McpPolicyAction.ALLOW, "write" to McpPolicyAction.ALLOW),
            engine.config.value.rules,
        )
        assertTrue(engine.revocationVersion("write", "plugin") > snapshot.last().expectedRevocation)
    }

    @Test fun `intervening rule change refuses entire section`() {
        val engine = McpPolicyEngine()
        val snapshot = changes(engine)
        engine.setToolPolicy("write", McpPolicyAction.ASK)
        assertIs<McpProactivePolicyOutcome.Refused>(engine.setSectionPolicies(snapshot))
        assertEquals(mapOf("write" to McpPolicyAction.ASK), engine.config.value.rules)
    }

    @Test fun `provider denial blocks all section grants`() {
        val engine = McpPolicyEngine()
        val snapshot = changes(engine)
        engine.setProviderPolicy("plugin", McpPolicyAction.DENY)
        assertIs<McpProactivePolicyOutcome.Denied>(engine.setSectionPolicies(snapshot))
        assertTrue(
            engine.config.value.rules
                .isEmpty(),
        )
    }

    @Test fun `reset invalidates section even when rules return to the same value`() {
        val engine = McpPolicyEngine()
        val snapshot = changes(engine)
        engine.revokePersistedPolicy("write")
        assertIs<McpProactivePolicyOutcome.Refused>(engine.setSectionPolicies(snapshot))
        assertTrue(
            engine.config.value.rules
                .isEmpty(),
        )
    }

    @Test fun `failed disk write leaves every rule unchanged`() {
        val dir =
            kotlin.io.path
                .createTempDirectory("section-policy")
                .toFile()
        try {
            val file = java.io.File(dir, "policy.json")
            val engine = McpPolicyEngine(file)
            engine.setToolPolicy("read", McpPolicyAction.DENY)
            val snapshot = changes(engine)
            file.delete()
            file.mkdir()
            java.io.File(file, "blocker").writeText("occupied")
            assertIs<McpProactivePolicyOutcome.Failed>(engine.setSectionPolicies(snapshot))
            assertEquals(mapOf("read" to McpPolicyAction.DENY), engine.config.value.rules)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `unreadable policy is never overwritten by a section`() {
        val dir =
            kotlin.io.path
                .createTempDirectory("section-policy")
                .toFile()
        try {
            val file = java.io.File(dir, "policy.json").apply { writeText("broken") }
            val engine = McpPolicyEngine(file)
            assertIs<McpProactivePolicyOutcome.PolicyUnreadable>(engine.setSectionPolicies(changes(engine)))
            assertEquals("broken", file.readText())
        } finally {
            dir.deleteRecursively()
        }
    }
}
