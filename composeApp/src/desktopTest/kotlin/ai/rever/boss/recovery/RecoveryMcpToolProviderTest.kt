package ai.rever.boss.recovery

import ai.rever.boss.mcp.McpToolRegistryImpl
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.recovery.mcp.DEFAULT_VERIFY_CLAIM_TIMEOUT_MS
import ai.rever.boss.recovery.mcp.MAX_VERIFY_CLAIM_TIMEOUT_MS
import ai.rever.boss.recovery.mcp.RecoveryMcpToolProvider
import ai.rever.boss.recovery.mcp.resolveVerifyClaimTimeoutMs
import ai.rever.boss.recovery.models.VerificationStatus
import ai.rever.boss.recovery.runtime.MissionRecoveryCoordinator
import ai.rever.boss.recovery.storage.WorkspaceCheckpointStorage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecoveryMcpToolProviderTest {
    private lateinit var tempProjectRoot: File
    private lateinit var tempStorageDir: File
    private lateinit var coordinator: MissionRecoveryCoordinator
    private lateinit var provider: RecoveryMcpToolProvider

    @BeforeTest
    fun setup() {
        tempProjectRoot =
            kotlin.io.path
                .createTempDirectory("mcp-recovery-test")
                .toFile()
        tempStorageDir =
            kotlin.io.path
                .createTempDirectory("mcp-storage-test")
                .toFile()
        val storage = WorkspaceCheckpointStorage(baseStorageDir = tempStorageDir)
        coordinator = MissionRecoveryCoordinator(storage = storage)
        provider = RecoveryMcpToolProvider(coordinator)
    }

    @AfterTest
    fun tearDown() {
        tempProjectRoot.deleteRecursively()
        tempStorageDir.deleteRecursively()
    }

    @Test
    fun `verify claim timeout defaults and cap stay strictly below the host invoke timeout`() {
        assertEquals(45_000L, resolveVerifyClaimTimeoutMs(null))
        assertEquals(45_000L, resolveVerifyClaimTimeoutMs(0))
        assertEquals(45_000L, resolveVerifyClaimTimeoutMs(-5))
        assertEquals(1_000L, resolveVerifyClaimTimeoutMs(1_000))
        assertEquals(55_000L, resolveVerifyClaimTimeoutMs(55_000))
        assertEquals(55_000L, resolveVerifyClaimTimeoutMs(999_999))
        assertTrue(DEFAULT_VERIFY_CLAIM_TIMEOUT_MS < 60_000L)
        assertTrue(MAX_VERIFY_CLAIM_TIMEOUT_MS < 60_000L)
    }

    @Test
    fun `verify claim returns the verifier's own timeout verdict before any host timeout`() =
        runBlocking {
            coordinator.startMission(tempProjectRoot, missionId = "mission-verify-timeout")

            val tool = provider.tools().single { it.name == "recovery_verify_claim" }

            // A 2s stand-in for the registry's 60s invoke timeout: the verifier's
            // own 100ms process timeout must win the race, not the host wrapper.
            // 'sleep' does not exist under cmd.exe, so branch on OS like
            // IndependentVerifierTest does.
            val sleepCommand =
                if (System.getProperty("os.name").lowercase().contains("win")) {
                    "powershell -Command Start-Sleep -Milliseconds 10000"
                } else {
                    "sleep 10"
                }
            val result =
                withTimeout(2_000L) {
                    tool.handler.call(
                        mockArgs(
                            mapOf(
                                "command" to sleepCommand,
                                "timeoutMs" to 100,
                            ),
                        ),
                    )
                }

            assertFalse(result.isError)
            assertTrue(result.text.contains("UNKNOWN"))
            assertTrue(result.text.contains("timed out after 100ms"))
            assertEquals(
                VerificationStatus.UNKNOWN,
                coordinator.state.value
                    .latestVerification
                    ?.status,
            )
        }

    private fun mockArgs(map: Map<String, Any?> = emptyMap()): McpToolArgs = McpToolArgs(map)

    @Test
    fun `provider exposes all 6 recovery tools and gates every mutating tool with requiresAdmin`() {
        val tools = provider.tools()
        assertEquals(6, tools.size)
        val toolNames = tools.map { it.name }.toSet()
        assertTrue(toolNames.contains("recovery_start_mission"))
        assertTrue(toolNames.contains("recovery_create_checkpoint"))
        assertTrue(toolNames.contains("recovery_verify_claim"))
        assertTrue(toolNames.contains("recovery_preview_rewind"))
        assertTrue(toolNames.contains("recovery_rewind"))
        assertTrue(toolNames.contains("recovery_list_checkpoints"))

        val rewindTool = tools.single { it.name == "recovery_rewind" }
        assertTrue(rewindTool.requiresAdmin, "recovery_rewind must require admin authorization")

        val verifyClaimTool = tools.single { it.name == "recovery_verify_claim" }
        assertTrue(
            verifyClaimTool.requiresAdmin,
            "recovery_verify_claim executes arbitrary commands and must require admin authorization",
        )

        val previewTool = tools.single { it.name == "recovery_preview_rewind" }
        assertFalse(previewTool.requiresAdmin, "recovery_preview_rewind should be accessible for dry-run inspection")

        val startTool = tools.single { it.name == "recovery_start_mission" }
        assertTrue(
            startTool.requiresAdmin,
            "recovery_start_mission captures a baseline from a caller-supplied path and must " +
                "require admin authorization",
        )

        val checkpointTool = tools.single { it.name == "recovery_create_checkpoint" }
        assertTrue(
            checkpointTool.requiresAdmin,
            "recovery_create_checkpoint byte-copies the whole workspace into storage and must " +
                "require admin authorization",
        )
    }

    @Test
    fun `recovery_start_mission initializes baseline via MCP handler`() =
        runBlocking {
            File(tempProjectRoot, "main.kt").writeText("fun main() = Unit")

            val startTool = provider.tools().single { it.name == "recovery_start_mission" }
            val handler = requireNotNull(startTool.handler)
            val result =
                handler.call(
                    mockArgs(mapOf("projectPath" to tempProjectRoot.path, "missionId" to "mission-mcp-1")),
                )

            assertFalse(result.isError)
            assertTrue(result.text.contains("mission-mcp-1"))
            assertEquals("mission-mcp-1", coordinator.state.value.activeMissionId)
        }

    @Test
    fun `recovery_create_checkpoint and recovery_list_checkpoints via MCP handler`() =
        runBlocking {
            File(tempProjectRoot, "main.kt").writeText("fun main() = Unit")
            coordinator.startMission(tempProjectRoot, missionId = "mission-mcp-2")

            val checkpointTool = provider.tools().single { it.name == "recovery_create_checkpoint" }
            val cpHandler = requireNotNull(checkpointTool.handler)
            val cpResult = cpHandler.call(mockArgs(mapOf("label" to "Initial Snapshot")))

            assertFalse(cpResult.isError)
            assertTrue(cpResult.text.contains("Initial Snapshot"))

            val listTool = provider.tools().single { it.name == "recovery_list_checkpoints" }
            val listHandler = requireNotNull(listTool.handler)
            val listResult = listHandler.call(mockArgs())

            assertFalse(listResult.isError)
            assertTrue(listResult.text.contains("Initial Snapshot"))
        }

    @Test
    fun `recovery_verify_claim runs command and reports discrepancy via MCP handler`() =
        runBlocking {
            coordinator.startMission(tempProjectRoot, missionId = "mission-mcp-3")

            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val verifyTool = provider.tools().single { it.name == "recovery_verify_claim" }
            val verifyHandler = requireNotNull(verifyTool.handler)

            val result =
                verifyHandler.call(
                    mockArgs(
                        mapOf(
                            "command" to if (isWindows) "exit /b 1" else "exit 1",
                            "claimType" to "TESTS_PASSED",
                            "claimStatement" to "All tests passed without error",
                        ),
                    ),
                )

            assertFalse(result.isError)
            assertTrue(result.text.contains("FAIL"))
            assertTrue(result.text.contains("DISCREPANCY DETECTED"))
        }

    @Test
    fun `recovery_rewind rolls back workspace state via MCP handler`() =
        runBlocking {
            val testFile =
                File(tempProjectRoot, "src/code.kt").also {
                    it.parentFile.mkdirs()
                    it.writeText("stable v1")
                }
            coordinator.startMission(tempProjectRoot, missionId = "mission-mcp-4")
            val cp = coordinator.createCheckpoint("Checkpoint 1")

            // Mutate file
            testFile.writeText("broken v2")

            val rewindTool = provider.tools().single { it.name == "recovery_rewind" }
            val rewindHandler = requireNotNull(rewindTool.handler)
            val rewindResult =
                rewindHandler.call(
                    mockArgs(mapOf("checkpointId" to cp.checkpointId)),
                )

            assertFalse(rewindResult.isError)
            assertTrue(rewindResult.text.contains("Successfully rewound"))
            assertEquals("stable v1", testFile.readText())
        }

    @Test
    fun `recovery_preview_rewind generates dry run without modifying workspace via MCP handler`() =
        runBlocking {
            val testFile =
                File(tempProjectRoot, "src/code.kt").also {
                    it.parentFile.mkdirs()
                    it.writeText("stable v1")
                }
            coordinator.startMission(tempProjectRoot, missionId = "mission-mcp-5")
            val cp = coordinator.createCheckpoint("Checkpoint 1")

            // Mutate file and add untracked junk
            testFile.writeText("broken v2")
            val junkFile = File(tempProjectRoot, "junk.log").also { it.writeText("junk") }

            val previewTool = provider.tools().single { it.name == "recovery_preview_rewind" }
            val previewHandler = requireNotNull(previewTool.handler)
            val previewResult =
                previewHandler.call(
                    mockArgs(mapOf("checkpointId" to cp.checkpointId)),
                )

            assertFalse(previewResult.isError)
            assertTrue(previewResult.text.contains("RECOVERY PREVIEW (Dry Run)"))
            assertTrue(previewResult.text.contains("src/code.kt"))
            assertTrue(previewResult.text.contains("junk.log"))

            // Confirm zero workspace modification occurred during preview
            assertEquals("broken v2", testFile.readText(), "Workspace file must remain unchanged after dry-run preview")
            assertTrue(junkFile.exists(), "Untracked file must not be removed during dry-run preview")
        }

    @Test
    fun `recovery tools are automatically registered in McpToolRegistryImpl`() {
        val allNames =
            McpToolRegistryImpl.allTools.value
                .map { it.definition.name }
                .toSet()
        assertTrue(allNames.contains("recovery_start_mission"))
        assertTrue(allNames.contains("recovery_create_checkpoint"))
        assertTrue(allNames.contains("recovery_verify_claim"))
        assertTrue(allNames.contains("recovery_preview_rewind"))
        assertTrue(allNames.contains("recovery_rewind"))
        assertTrue(allNames.contains("recovery_list_checkpoints"))

        // Verify RBAC: non-admin permitted tools must exclude recovery_rewind
        val nonAdminNames =
            McpToolRegistryImpl.tools.value
                .map { it.definition.name }
                .toSet()
        assertFalse(nonAdminNames.contains("recovery_rewind"), "recovery_rewind must not be exposed without admin")
        assertTrue(nonAdminNames.contains("recovery_preview_rewind"), "preview must be exposed without admin")

        // Grant admin and verify recovery_rewind becomes permitted
        McpToolRegistryImpl.updateAccess(isAdmin = true, permissions = emptySet())
        val adminNames =
            McpToolRegistryImpl.tools.value
                .map { it.definition.name }
                .toSet()
        assertTrue(adminNames.contains("recovery_rewind"), "recovery_rewind must be exposed when admin is granted")
    }
}
