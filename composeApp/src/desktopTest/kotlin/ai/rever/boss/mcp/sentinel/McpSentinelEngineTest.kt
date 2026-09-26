package ai.rever.boss.mcp.sentinel

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolResult
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
        val dir =
            kotlin.io.path
                .createTempDirectory("mcp-sentinel-test")
                .toFile()
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
            eval2.currentFingerprint.fingerprint,
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
            "Poisoned definition change must be flagged as CHANGED or SUSPICIOUS",
        )
        assertNotNull(evalB.diffResult)
        assertTrue(evalB.diffResult!!.hasChanges)

        // 5. Verify Invocation Check Refusal
        val check = engine.checkInvocation("codebase_provider", "read_project_file")
        assertFalse(check.isAllowed, "Invocation of changed tool must be refused")

        // 6. Explicit Re-approval
        engine.approveAndTrustTool("codebase_provider", "read_project_file", registeredTool = toolB)
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

    @Test
    fun `evaluateSingleToolAndMerge preserves existing tool evaluations`() {
        val file = tempBaselineFile()
        val store = ToolDnaBaselineStore(baselineFile = file)
        val engine = McpSentinelEngine(baselineStore = store)

        val toolA = AttackSimulationFixtures.BENIGN_READ_FILE_TOOL
        val toolB =
            RegisteredMcpTool(
                providerId = "k8s_provider",
                definition =
                    McpToolDefinition(
                        name = "k8s_list_pods",
                        description = "List Kubernetes pods in cluster",
                        inputSchema = """{"type":"object"}""",
                        readOnly = true,
                        handler = McpToolHandler { McpToolResult("ok") },
                    ),
            )

        engine.evaluateAll(listOf(toolA))
        assertEquals(1, engine.evaluations.value.size)

        // Evaluate tool B individually
        engine.checkInvocation("k8s_provider", "k8s_list_pods", toolB)

        // Verify tool A evaluation remains intact while tool B was merged
        assertEquals(2, engine.evaluations.value.size)
        assertNotNull(engine.evaluations.value["codebase_provider/read_project_file"])
        assertNotNull(engine.evaluations.value["k8s_provider/k8s_list_pods"])
    }

    @Test
    fun `approval with stale reviewedFingerprint is rejected`() {
        val file = tempBaselineFile()
        val store = ToolDnaBaselineStore(baselineFile = file)
        val engine = McpSentinelEngine(baselineStore = store)

        val toolA = AttackSimulationFixtures.BENIGN_READ_FILE_TOOL
        engine.evaluateAll(listOf(toolA))

        val reviewedFingerprint = "stale_sha256_digest_that_does_not_match"
        val approved =
            engine.approveAndTrustTool(
                providerId = "codebase_provider",
                toolName = "read_project_file",
                reviewedFingerprint = reviewedFingerprint,
                registeredTool = toolA,
            )

        assertFalse(approved, "Approval must be rejected when reviewed fingerprint differs from current")
    }

    @Test
    fun `corrupted baseline file forces REVIEW_REQUIRED trust state`() {
        val file = tempBaselineFile()
        file.writeText("invalid json content { [ corrupt")

        val store = ToolDnaBaselineStore(baselineFile = file)
        assertTrue(store.isCorrupted, "Store must mark file as corrupted")

        val engine = McpSentinelEngine(baselineStore = store)
        val tool = AttackSimulationFixtures.BENIGN_READ_FILE_TOOL
        val eval = engine.evaluateAll(listOf(tool)).single()

        assertEquals(SentinelTrustState.REVIEW_REQUIRED, eval.trustState)
        assertTrue(eval.reason.contains("corrupted"))
    }

    @Test
    fun `tampered baseline record with invalid HMAC signature is rejected and marked REVIEW_REQUIRED`() {
        val file = tempBaselineFile()
        val store1 = ToolDnaBaselineStore(baselineFile = file)
        val engine1 = McpSentinelEngine(baselineStore = store1)

        val tool = AttackSimulationFixtures.BENIGN_READ_FILE_TOOL
        engine1.evaluateAll(listOf(tool))
        engine1.approveAndTrustTool("codebase_provider", "read_project_file")

        // Tamper with baseline JSON on disk: change trustState to TRUSTED with fake HMAC
        val content = file.readText()
        val tamperedContent =
            content.replace(
                Regex(""""hmacSignature"\s*:\s*".*?""""),
                """"hmacSignature": "forged_hmac_1234567890abcdef"""",
            )
        file.writeText(tamperedContent)

        val store2 = ToolDnaBaselineStore(baselineFile = file)
        val rec = store2.getBaseline("codebase_provider", "read_project_file")
        assertNotNull(rec)
        val msg1 = "Tampered baseline record must be forced to REVIEW_REQUIRED"
        assertEquals(SentinelTrustState.REVIEW_REQUIRED, rec.trustState, msg1)
    }

    @Test
    fun `unsigned legacy baseline record is marked REVIEW_REQUIRED for operator re-approval`() {
        val file = tempBaselineFile()
        val legacyJson =
            """
            [
              {
                "providerId": "codebase_provider",
                "toolName": "read_project_file",
                "canonicalFingerprint": "legacy_fp_123",
                "firstSeenTimestamp": 1000,
                "lastSeenTimestamp": 1000,
                "trustState": "TRUSTED",
                "lastAcceptedDescription": "desc",
                "lastAcceptedSchemaJson": "{}"
              }
            ]
            """.trimIndent()
        file.writeText(legacyJson)

        val store = ToolDnaBaselineStore(baselineFile = file)
        val rec = store.getBaseline("codebase_provider", "read_project_file")
        assertNotNull(rec)
        val msg2 = "Unsigned legacy record must require operator re-approval"
        assertEquals(SentinelTrustState.REVIEW_REQUIRED, rec.trustState, msg2)
    }
}
