package ai.rever.boss.mcp.secrets

import ai.rever.boss.mcp.sandbox.McpRiskAssessment
import ai.rever.boss.mcp.sandbox.McpRiskLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SecretRiskEscalationTest {
    private val id = "6f1d2c3e-4b5a-4c6d-8e7f-90a1b2c3d4e5"
    private val descriptor = SecretDescriptor(SecretReference(id, SecretField.PASSWORD), "github.com", "deploy-bot")

    @Test
    fun `no descriptors leaves the assessment untouched`() {
        val base = McpRiskAssessment(McpRiskLevel.LOW, "read-only")
        assertSame(base, base.withSecrets(emptyList()))
    }

    @Test
    fun `a low-risk tool becomes high and the reason names the secret`() {
        val out = McpRiskAssessment(McpRiskLevel.LOW, "read-only").withSecrets(listOf(descriptor))
        assertEquals(McpRiskLevel.HIGH, out.level)
        val expected = "read-only; receives 1 secret: github.com (deploy-bot) - password"
        assertTrue(out.reason.startsWith(expected), out.reason)
    }

    @Test
    fun `a critical tool stays critical`() {
        val out = McpRiskAssessment(McpRiskLevel.CRITICAL, "shell").withSecrets(listOf(descriptor, descriptor))
        assertEquals(McpRiskLevel.CRITICAL, out.level)
        assertTrue(out.reason.contains("receives 2 secrets"), out.reason)
    }
}
