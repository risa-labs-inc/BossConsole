package ai.rever.boss.mcp.sentinel

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.RegisteredMcpTool
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpSentinelEvaluationMatrixTest {
    private val tempFiles = mutableListOf<File>()

    private fun tempBaselineFile(): File {
        val dir =
            kotlin.io.path
                .createTempDirectory("mcp-sentinel-eval")
                .toFile()
        return File(dir, "mcp-eval-baseline.json").also { tempFiles.add(it) }
    }

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { it.parentFile?.deleteRecursively() }
        tempFiles.clear()
    }

    @Test
    fun `EVAL-RUGPULL-001 — Trusted tool definition changed`() {
        val engine = McpSentinelEngine(ToolDnaBaselineStore(tempBaselineFile()))
        val providerId = "codebase_provider"
        val toolName = "read_project_file"

        // 1. Initial State: Establish trust for Version A
        val toolA = AttackSimulationFixtures.BENIGN_READ_FILE_TOOL
        engine.evaluateAll(listOf(toolA))
        engine.approveAndTrustTool(providerId, toolName)

        // 2. Mutation: Replace with Poisoned Version B
        val toolB = AttackSimulationFixtures.POISONED_READ_FILE_TOOL
        val results = engine.evaluateAll(listOf(toolB))
        val evalB = results.single()

        // 3. Assert Expected Detection & State Transition
        assertTrue(
            evalB.trustState == SentinelTrustState.CHANGED || evalB.trustState == SentinelTrustState.SUSPICIOUS,
            "State must transition to CHANGED or SUSPICIOUS",
        )
        assertNotNull(evalB.diffResult)
        assertTrue(evalB.diffResult!!.hasChanges)
        assertTrue(evalB.diffResult!!.categories.contains(ChangeCategory.DESCRIPTION_CHANGED))

        // 4. Assert Expected Governance Action
        val check = engine.checkInvocation(providerId, toolName)
        assertFalse(check.isAllowed, "Governance must block invocation of rug-pulled tool")
    }

    @Test
    fun `EVAL-POISON-001 — Static prompt injection payload`() {
        val engine = McpSentinelEngine(ToolDnaBaselineStore(tempBaselineFile()))
        val tool = AttackSimulationFixtures.POISONED_READ_FILE_TOOL

        val eval = engine.evaluateAll(listOf(tool)).single()

        // Assert scanner findings
        assertTrue(eval.securityFindings.isNotEmpty())
        assertTrue(eval.securityFindings.any { it.ruleId == "INJ-001" })
        assertEquals(FindingSeverity.CRITICAL, eval.securityFindings.first { it.ruleId == "INJ-001" }.severity)
        assertEquals(SentinelTrustState.SUSPICIOUS, eval.trustState)
    }

    @Test
    fun `EVAL-UNICODE-001 — Invisible Unicode obfuscation`() {
        val engine = McpSentinelEngine(ToolDnaBaselineStore(tempBaselineFile()))
        val tool = AttackSimulationFixtures.INVISIBLE_UNICODE_TOOL

        val eval = engine.evaluateAll(listOf(tool)).single()

        assertTrue(eval.securityFindings.any { it.ruleId == "UNI-001" })
        assertEquals(SentinelTrustState.SUSPICIOUS, eval.trustState)
    }

    @Test
    fun `EVAL-SHADOW-001 — Cross-server exact tool name collision`() {
        val engine = McpSentinelEngine(ToolDnaBaselineStore(tempBaselineFile()))
        val tools =
            listOf(
                AttackSimulationFixtures.SHADOWING_COLLISION_TOOL_1,
                AttackSimulationFixtures.SHADOWING_COLLISION_TOOL_2,
            )

        val evals = engine.evaluateAll(tools)
        val shadowings = engine.shadowingFindings.value

        assertTrue(shadowings.isNotEmpty())
        assertTrue(shadowings.any { it.collisionType == "EXACT_NAME_COLLISION" })
        assertTrue(evals.all { it.shadowingFindings.isNotEmpty() })
    }

    @Test
    fun `EVAL-SCHEMA-001 — Destructive schema parameter expansion`() {
        val engine = McpSentinelEngine(ToolDnaBaselineStore(tempBaselineFile()))
        val providerId = "codebase_provider"
        val toolName = "read_project_file"

        // Baseline Version A
        engine.evaluateAll(listOf(AttackSimulationFixtures.BENIGN_READ_FILE_TOOL))
        engine.approveAndTrustTool(providerId, toolName)

        // Mutation Version B with destructive parameter added
        val toolB = AttackSimulationFixtures.SCHEMA_RUG_PULL_TOOL
        val evalB = engine.evaluateAll(listOf(toolB)).single()

        assertNotNull(evalB.diffResult)
        assertTrue(evalB.diffResult!!.categories.contains(ChangeCategory.DESTRUCTIVE_PARAMETER_ADDED))
        assertTrue(evalB.diffResult!!.categories.contains(ChangeCategory.CAPABILITY_EXPANSION))
        assertEquals(SentinelTrustState.REVIEW_REQUIRED, evalB.trustState)
    }

    @Test
    fun `EVAL-FALSEPOS-001 — Benign description with keywords`() {
        val engine = McpSentinelEngine(ToolDnaBaselineStore(tempBaselineFile()))
        val tool = AttackSimulationFixtures.SAFE_CONTEXT_TOOL

        val eval = engine.evaluateAll(listOf(tool)).single()

        assertFalse(eval.securityFindings.any { it.ruleId == "INJ-001" })
        assertEquals(SentinelTrustState.NEW, eval.trustState)
    }

    @Test
    fun `EVAL-RETRUST-001 — Re-approval updates baseline and restores trust`() {
        val engine = McpSentinelEngine(ToolDnaBaselineStore(tempBaselineFile()))
        val providerId = "codebase_provider"
        val toolName = "read_project_file"

        // Baseline Version A
        engine.evaluateAll(listOf(AttackSimulationFixtures.BENIGN_READ_FILE_TOOL))
        engine.approveAndTrustTool(providerId, toolName)

        // Mutation Version B (benign update)
        val toolB =
            RegisteredMcpTool(
                providerId = providerId,
                definition =
                    McpToolDefinition(
                        name = toolName,
                        description =
                            "Read a text file from the current workspace project directory " +
                                "with UTF-8 encoding.",
                        inputSchema =
                            """{"type":"object","properties":{"path":{"type":"string"}},""" +
                                """"required":["path"]}""",
                        readOnly = true,
                        handler =
                            ai.rever.boss.plugin.api
                                .McpToolHandler {
                                    ai.rever.boss.plugin.api
                                        .McpToolResult("ok")
                                },
                    ),
            )
        engine.evaluateAll(listOf(toolB))

        // Re-approve Version B
        val success = engine.approveAndTrustTool(providerId, toolName, registeredTool = toolB)
        assertTrue(success)

        val evalAfterReapprove = engine.evaluateAll(listOf(toolB)).single()
        assertEquals(SentinelTrustState.TRUSTED, evalAfterReapprove.trustState)
        assertEquals(
            ToolDnaFingerprinter.computeFingerprint(toolB).fingerprint,
            evalAfterReapprove.currentFingerprint.fingerprint,
        )
    }
}
