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

    @Test
    fun `checkInvocation fails closed for unknown tool`() {
        val file = tempBaselineFile()
        val store = ToolDnaBaselineStore(baselineFile = file)
        val engine = McpSentinelEngine(baselineStore = store)

        val check = engine.checkInvocation("unknown_provider", "unknown_tool")
        assertFalse(check.isAllowed, "Invocation of unknown tool must fail closed")
        assertEquals(SentinelTrustState.UNKNOWN, check.trustState)
    }

    @Test
    fun `canonicalizes provider IDs case-insensitively`() {
        val file = tempBaselineFile()
        val store = ToolDnaBaselineStore(baselineFile = file)
        val engine = McpSentinelEngine(baselineStore = store)

        val tool = AttackSimulationFixtures.BENIGN_READ_FILE_TOOL
        engine.evaluateAll(listOf(tool))
        engine.approveAndTrustTool("Codebase_Provider", "read_project_file")

        val checkUpper = engine.checkInvocation("CODEBASE_PROVIDER", "read_project_file")
        assertTrue(checkUpper.isAllowed, "Provider ID lookup must be case-insensitive")
    }

    @Test
    fun `detects rug pull when readOnly flag changes from true to false`() {
        val file = tempBaselineFile()
        val store = ToolDnaBaselineStore(baselineFile = file)
        val engine = McpSentinelEngine(baselineStore = store)

        val readOnlyTool = AttackSimulationFixtures.BENIGN_READ_FILE_TOOL
        engine.evaluateAll(listOf(readOnlyTool))
        engine.approveAndTrustTool("codebase_provider", "read_project_file")

        val readWriteTool =
            ai.rever.boss.plugin.api.RegisteredMcpTool(
                providerId = "codebase_provider",
                definition =
                    ai.rever.boss.plugin.api.McpToolDefinition(
                        name = "read_project_file",
                        description = readOnlyTool.definition.description,
                        inputSchema = readOnlyTool.definition.inputSchema,
                        readOnly = false,
                        handler = readOnlyTool.definition.handler,
                    ),
            )

        val eval = engine.evaluateAll(listOf(readWriteTool)).single()
        assertTrue(eval.diffResult != null && eval.diffResult.hasChanges)
        val check = engine.checkInvocation("codebase_provider", "read_project_file")
        assertFalse(check.isAllowed, "Rug pull modifying readOnly state must be refused")
    }

    // ── BL-A REGRESSION ──────────────────────────────────────────────────────────────────
    // A tampered baseline record whose HMAC is invalid but whose userDecision field says
    // "APPROVED" and whose fingerprint matches the live tool must NOT become TRUSTED and
    // must NOT allow invocation. Trust must flow only from trustState==TRUSTED on an
    // HMAC-verified record.
    @Test
    fun `BL-A tampered HMAC record with userDecision APPROVED and matching fingerprint is denied`() {
        val file = tempBaselineFile()
        val store1 = ToolDnaBaselineStore(baselineFile = file)
        val engine1 = McpSentinelEngine(baselineStore = store1)

        val tool = AttackSimulationFixtures.BENIGN_READ_FILE_TOOL

        // Establish a legitimate TRUSTED baseline.
        engine1.evaluateAll(listOf(tool))
        engine1.approveAndTrustTool("codebase_provider", "read_project_file")
        val evalTrusted = engine1.evaluateAll(listOf(tool)).single()
        assertEquals(SentinelTrustState.TRUSTED, evalTrusted.trustState)

        // Read the current fingerprint so the tampered record can claim to match it.
        val currentFingerprint = evalTrusted.currentFingerprint.fingerprint

        // Forge a baseline JSON on disk:
        // - trustState is REVIEW_REQUIRED (so HMAC check would catch it)
        // - userDecision is "APPROVED" (the bypass value)
        // - canonicalFingerprint matches the live tool exactly (so evaluateUnchangedTool fires)
        // - hmacSignature is a plausible but forged value
        val forgedJson =
            """
            [
              {
                "providerId": "codebase_provider",
                "toolName": "read_project_file",
                "canonicalFingerprint": "$currentFingerprint",
                "fingerprintVersion": "v1",
                "firstSeenTimestamp": 1000,
                "lastSeenTimestamp": 1000,
                "trustState": "TRUSTED",
                "userDecision": "APPROVED",
                "lastAcceptedDescription": "Reads a file",
                "lastAcceptedSchemaJson": "{}",
                "hmacSignature": "forged_hmac_that_will_fail_verification_1234567890abcdef"
              }
            ]
            """.trimIndent()
        file.writeText(forgedJson)

        // Load via a fresh store (simulates app restart after tamper).
        val store2 = ToolDnaBaselineStore(baselineFile = file)
        val engine2 = McpSentinelEngine(baselineStore = store2)

        // The baseline record should have been degraded to REVIEW_REQUIRED on load.
        val loaded = store2.getBaseline("codebase_provider", "read_project_file")
        assertNotNull(loaded)
        assertEquals(
            SentinelTrustState.REVIEW_REQUIRED,
            loaded.trustState,
            "BL-A: HMAC-invalid record must be loaded as REVIEW_REQUIRED",
        )
        // userDecision must be cleared by processLoadedRecords.
        val loadedDecision = loaded.userDecision
        assertTrue(
            loadedDecision == null || loadedDecision != "APPROVED",
            "BL-A: userDecision must be cleared on HMAC-invalid records, was: $loadedDecision",
        )

        // evaluateAll with the matching live tool must NOT promote to TRUSTED.
        val eval = engine2.evaluateAll(listOf(tool)).single()
        val msg1 =
            "BL-A: HMAC-tampered record with matching fingerprint must not become TRUSTED; " +
                "got ${eval.trustState}"
        assertTrue(eval.trustState != SentinelTrustState.TRUSTED, msg1)

        // Invocation must be denied.
        val check = engine2.checkInvocation("codebase_provider", "read_project_file")
        assertFalse(check.isAllowed, "BL-A: Invocation on tampered-HMAC baseline must be denied")
    }

    // ── BL-B REGRESSION ──────────────────────────────────────────────────────────────────
    // Tools whose descriptions or schema strings contain standard ZWJ emoji sequences
    // (technologist, family, rainbow flag) must NOT be blocked. ZWJ (U+200D) is required
    // for multi-codepoint emoji composition and is not an injection vector.
    @Test
    fun `BL-B tool with ZWJ emoji in description is not flagged SUSPICIOUS`() {
        val file = tempBaselineFile()
        val store = ToolDnaBaselineStore(baselineFile = file)
        val engine = McpSentinelEngine(baselineStore = store)

        // 🧑‍💻 = U+1F9D1 + U+200D + U+1F4BB (technologist)
        // 👨‍👩‍👧‍👦 = U+1F468 + U+200D + U+1F469 + U+200D + U+1F467 + U+200D + U+1F466 (family)
        // 🏳️‍🌈 = U+1F3F3 + U+FE0F + U+200D + U+1F308 (rainbow flag)
        val zwjEmoji = "\uD83E\uDDD1\u200D\uD83D\uDCBB" // 🧑‍💻
        val familyEmoji = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66" // 👨‍👩‍👧‍👦
        val rainbowFlag = "\uD83C\uDFF3\uFE0F\u200D\uD83C\uDF08" // 🏳️‍🌈

        val toolWithEmoji =
            RegisteredMcpTool(
                providerId = "emoji_provider",
                definition =
                    McpToolDefinition(
                        name = "emoji_tool",
                        description =
                            "Use this tool to assist $zwjEmoji developers with " +
                                "$familyEmoji family tasks and $rainbowFlag inclusive work.",
                        inputSchema = """{"type":"object"}""",
                        handler = McpToolHandler { McpToolResult("ok") },
                    ),
            )

        val eval = engine.evaluateAll(listOf(toolWithEmoji)).single()
        val msg =
            "BL-B: Tool with ZWJ emoji must not be SUSPICIOUS; " +
                "got ${eval.trustState} with findings: ${eval.securityFindings}"
        assertTrue(eval.trustState != SentinelTrustState.SUSPICIOUS, msg)

        // Also verify invocation is not blocked by the scanner finding.
        engine.approveAndTrustTool("emoji_provider", "emoji_tool")
        val eval2 = engine.evaluateAll(listOf(toolWithEmoji)).single()
        assertEquals(SentinelTrustState.TRUSTED, eval2.trustState, "BL-B: ZWJ emoji tool must be approvable to TRUSTED")

        val check = engine.checkInvocation("emoji_provider", "emoji_tool")
        assertTrue(check.isAllowed, "BL-B: TRUSTED ZWJ emoji tool must be invocable")
    }

    // ── BL-C REGRESSION ──────────────────────────────────────────────────────────────────
    // After recoverCorruptedStore(), tools must be placed in REVIEW_REQUIRED, not NEW.
    // A NEW tool is immediately invocable; a post-recovery tool must require explicit
    // operator re-approval before invocation is allowed.
    @Test
    fun `BL-C recoverCorruptedStore forces REVIEW_REQUIRED and blocks invocation until re-approved`() {
        val file = tempBaselineFile()

        // Corrupt the baseline file on disk (simulates on-disk corruption scenario).
        file.writeText("this is not valid json { [ corrupt }")

        val store = ToolDnaBaselineStore(baselineFile = file)
        assertTrue(store.isCorrupted, "BL-C: Store must detect on-disk corruption")

        val engine = McpSentinelEngine(baselineStore = store)
        val tool = AttackSimulationFixtures.BENIGN_READ_FILE_TOOL

        // Trigger operator recovery action.
        val recovered = engine.recoverCorruptedStore(listOf(tool))
        assertTrue(recovered, "BL-C: recoverCorruptedStore must return true on successful clear")
        assertFalse(store.isCorrupted, "BL-C: Store must no longer be corrupted after recovery")

        // Post-recovery: tool must be REVIEW_REQUIRED, not NEW.
        val eval = engine.evaluations.value["codebase_provider/read_project_file"]
        assertNotNull(eval, "BL-C: Evaluation must exist for tool after recovery")
        assertEquals(
            SentinelTrustState.REVIEW_REQUIRED,
            eval.trustState,
            "BL-C: Tool must be REVIEW_REQUIRED after recovery, not NEW or TRUSTED; got ${eval.trustState}",
        )

        // Invocation must be denied without explicit re-approval.
        val checkBefore = engine.checkInvocation("codebase_provider", "read_project_file")
        assertFalse(
            checkBefore.isAllowed,
            "BL-C: Tool invocation must be denied after recovery until operator re-approves",
        )

        // Only after explicit operator re-approval may invocation proceed.
        engine.approveAndTrustTool("codebase_provider", "read_project_file", registeredTool = tool)
        engine.evaluateAll(listOf(tool))
        val checkAfter = engine.checkInvocation("codebase_provider", "read_project_file")
        assertTrue(checkAfter.isAllowed, "BL-C: Tool must be invocable after explicit re-approval post-recovery")
    }

    // ── N5 REGRESSION ──────────────────────────────────────────────────────────────────
    // HMAC payload construction includes payloadVersion=v1 and supports legacy record fallback.
    @Test
    fun `N5-1 saved baselines compute HMAC using payloadVersion v1`() {
        val file = tempBaselineFile()
        val store = ToolDnaBaselineStore(baselineFile = file)
        val engine = McpSentinelEngine(baselineStore = store)
        val tool = AttackSimulationFixtures.BENIGN_READ_FILE_TOOL

        engine.evaluateAll(listOf(tool))
        engine.approveAndTrustTool("codebase_provider", "read_project_file", registeredTool = tool)

        val baseline = store.getBaseline("codebase_provider", "read_project_file")
        assertNotNull(baseline)
        assertNotNull(baseline.hmacSignature)
        assertTrue(baseline.hmacSignature.isNotBlank(), "N5-1: Saved baseline must possess non-blank HMAC signature")
    }

    @Test
    fun `N5-2 legacy baseline records without payloadVersion verify successfully`() {
        val file = tempBaselineFile()
        val store = ToolDnaBaselineStore(baselineFile = file)
        val tool = AttackSimulationFixtures.BENIGN_READ_FILE_TOOL

        val record =
            ToolBaselineRecord(
                providerId = "codebase_provider",
                toolName = "read_project_file",
                canonicalFingerprint = "abcd1234efgh5678",
                fingerprintVersion = "v1",
                firstSeenTimestamp = 1000L,
                lastSeenTimestamp = 1000L,
                trustState = SentinelTrustState.TRUSTED,
                lastAcceptedDescription = "Reads a file",
                lastAcceptedSchemaJson = "{}",
                readOnly = true,
                requiresAdmin = false,
                userDecision = "APPROVED",
            )

        val logger =
            ai.rever.boss.utils.logging.BossLogger
                .forComponent("Test")
        val key = SentinelHmacHelper.initOrLoadHmacKey(file, null, logger)
        val legacySig = SentinelHmacHelper.computeLegacyHmac(key, record)

        val legacyRecord = record.copy(hmacSignature = legacySig)
        store.saveBaseline(legacyRecord)

        val loadedStore = ToolDnaBaselineStore(baselineFile = file)
        val loadedBaseline = loadedStore.getBaseline("codebase_provider", "read_project_file")
        assertNotNull(loadedBaseline)
        assertEquals(
            SentinelTrustState.TRUSTED,
            loadedBaseline.trustState,
            "N5-2: Legacy unversioned HMAC baseline must verify and load as TRUSTED",
        )
    }
}
