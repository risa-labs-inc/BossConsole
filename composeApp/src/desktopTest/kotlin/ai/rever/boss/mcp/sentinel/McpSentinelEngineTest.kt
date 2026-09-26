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

class McpSentinelEngineTest {
    private val tempFiles = mutableListOf<File>()

    private fun tempBaselineFile(): File {
        val dir = kotlin.io.path.createTempDirectory("mcp-sentinel-test").toFile()
        return File(dir, "mcp-tooldna-baseline.json").also { tempFiles.add(it) }
    }

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { it.parentFile?.deleteRecursively() }
        tempFiles.clear()
    }

    @Test
    fun `baseline survives restart across fresh store and engine instances`() {
        val file = tempBaselineFile()
        val store1 = ToolDnaBaselineStore(baselineFile = file)
        val engine1 = McpSentinelEngine(baselineStore = store1)

        val tool = AttackSimulationFixtures.BENIGN_READ_FILE_TOOL
        engine1.evaluateAll(listOf(tool))
        engine1.approveAndTrustTool("codebase_provider", "read_project_file")

        val store2 = ToolDnaBaselineStore(baselineFile = file)
        val engine2 = McpSentinelEngine(baselineStore = store2)
        val eval2 = engine2.evaluateAll(listOf(tool)).single()

        assertEquals(SentinelTrustState.TRUSTED, eval2.trustState)
        assertEquals(
            ToolDnaFingerprinter.computeFingerprint(tool).fingerprint,
            eval2.currentFingerprint.fingerprint
        )
    }

    @Test
    fun `detects rug pull transition from Version A to Version B`() {
        val file = tempBaselineFile()
        val store = ToolDnaBaselineStore(baselineFile = file)
        val engine = McpSentinelEngine(baselineStore = store)

        // 1. Initial observation of benign tool Version A
        val toolA = AttackSimulationFixtures.BENIGN_READ_FILE_TOOL
        engine.evaluateAll(listOf(toolA))

        // 2. Establish trust for Version A
        engine.approveAndTrustTool("codebase_provider", "read_project_file")
        val evalAfterTrust = engine.evaluateAll(listOf(toolA)).single()
        assertEquals(SentinelTrustState.TRUSTED, evalAfterTrust.trustState)

        // 3. Reconnect / reload with Poisoned Version B
        val toolB = AttackSimulationFixtures.POISONED_READ_FILE_TOOL
        val evalB = engine.evaluateAll(listOf(toolB)).single()

        // 4. Verify Rug Pull transition to CHANGED or SUSPICIOUS
        assertTrue(
            evalB.trustState == SentinelTrustState.CHANGED || evalB.trustState == SentinelTrustState.SUSPICIOUS,
            "Poisoned definition change must be flagged as CHANGED or SUSPICIOUS"
        )
        assertNotNull(evalB.diffResult)
        assertTrue(evalB.diffResult!!.hasChanges)

        // 5. Verify Invocation Check Refusal
        val check = engine.checkInvocation("codebase_provider", "read_project_file")
        assertFalse(check.isAllowed, "Invocation of changed tool must be refused")

        // 6. Explicit Re-approval
        engine.approveAndTrustTool("codebase_provider", "read_project_file", toolB)
        val evalAfterReapprove = engine.evaluateAll(listOf(toolB)).single()
        assertEquals(SentinelTrustState.TRUSTED, evalAfterReapprove.trustState)

        // 7. Verify Invocation Allowed after Re-approval
        val checkReapproved = engine.checkInvocation("codebase_provider", "read_project_file")
        assertTrue(checkReapproved.isAllowed, "Invocation must be allowed after explicit re-approval")
    }

    @Test
    fun `blocking and unblocking tool lifecycle`() {
        val file = tempBaselineFile()
        val store = ToolDnaBaselineStore(baselineFile = file)
        val engine = McpSentinelEngine(baselineStore = store)

        val tool = AttackSimulationFixtures.BENIGN_READ_FILE_TOOL
        engine.evaluateAll(listOf(tool))

        // Block tool
        engine.blockTool("codebase_provider", "read_project_file")
        val evalBlocked = engine.evaluateAll(listOf(tool)).single()
        assertEquals(SentinelTrustState.BLOCKED, evalBlocked.trustState)

        val checkBlocked = engine.checkInvocation("codebase_provider", "read_project_file")
        assertFalse(checkBlocked.isAllowed)

        // Unblock tool
        engine.unblockTool("codebase_provider", "read_project_file")
        val evalUnblocked = engine.evaluateAll(listOf(tool)).single()
        assertTrue(evalUnblocked.trustState != SentinelTrustState.BLOCKED)
    }
}
