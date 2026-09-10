package ai.rever.boss.recovery

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.recovery.mcp.RecoveryMcpToolProvider
import ai.rever.boss.recovery.runtime.MissionRecoveryCoordinator
import ai.rever.boss.recovery.storage.WorkspaceCheckpointStorage
import kotlinx.coroutines.runBlocking
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
        tempProjectRoot = kotlin.io.path.createTempDirectory("mcp-recovery-test").toFile()
        tempStorageDir = kotlin.io.path.createTempDirectory("mcp-storage-test").toFile()
        val storage = WorkspaceCheckpointStorage(baseStorageDir = tempStorageDir)
        coordinator = MissionRecoveryCoordinator(storage = storage)
        provider = RecoveryMcpToolProvider(coordinator)
    }

    @AfterTest
    fun tearDown() {
        tempProjectRoot.deleteRecursively()
        tempStorageDir.deleteRecursively()
    }

    private fun mockArgs(map: Map<String, Any?> = emptyMap()): McpToolArgs = McpToolArgs(map)

    @Test
    fun `provider exposes all 5 recovery tools and gates rewind with requiresAdmin`() {
        val tools = provider.tools()
        assertEquals(5, tools.size)
        val toolNames = tools.map { it.name }.toSet()
        assertTrue(toolNames.contains("recovery_start_mission"))
        assertTrue(toolNames.contains("recovery_create_checkpoint"))
        assertTrue(toolNames.contains("recovery_verify_claim"))
        assertTrue(toolNames.contains("recovery_rewind"))
        assertTrue(toolNames.contains("recovery_list_checkpoints"))

        val rewindTool = tools.single { it.name == "recovery_rewind" }
        assertTrue(rewindTool.requiresAdmin, "recovery_rewind must require admin authorization")
    }

    @Test
    fun `recovery_start_mission initializes baseline via MCP handler`() = runBlocking {
        File(tempProjectRoot, "main.kt").writeText("fun main() = Unit")

        val startTool = provider.tools().single { it.name == "recovery_start_mission" }
        val handler = requireNotNull(startTool.handler)
        val result = handler.call(
            mockArgs(mapOf("projectPath" to tempProjectRoot.path, "missionId" to "mission-mcp-1"))
        )

        assertFalse(result.isError)
        assertTrue(result.text.contains("mission-mcp-1"))
        assertEquals("mission-mcp-1", coordinator.state.value.activeMissionId)
    }

    @Test
    fun `recovery_create_checkpoint and recovery_list_checkpoints via MCP handler`() = runBlocking {
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
    fun `recovery_verify_claim runs command and reports discrepancy via MCP handler`() = runBlocking {
        coordinator.startMission(tempProjectRoot, missionId = "mission-mcp-3")

        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val verifyTool = provider.tools().single { it.name == "recovery_verify_claim" }
        val verifyHandler = requireNotNull(verifyTool.handler)

        val result = verifyHandler.call(
            mockArgs(
                mapOf(
                    "command" to if (isWindows) "exit /b 1" else "exit 1",
                    "claimType" to "TESTS_PASSED",
                    "claimStatement" to "All tests passed without error"
                )
            )
        )

        assertFalse(result.isError)
        assertTrue(result.text.contains("FAIL"))
        assertTrue(result.text.contains("DISCREPANCY DETECTED"))
    }

    @Test
    fun `recovery_rewind rolls back workspace state via MCP handler`() = runBlocking {
        val testFile = File(tempProjectRoot, "src/code.kt").also {
            it.parentFile.mkdirs()
            it.writeText("stable v1")
        }
        coordinator.startMission(tempProjectRoot, missionId = "mission-mcp-4")
        val cp = coordinator.createCheckpoint("Checkpoint 1")

        // Mutate file
        testFile.writeText("broken v2")

        val rewindTool = provider.tools().single { it.name == "recovery_rewind" }
        val rewindHandler = requireNotNull(rewindTool.handler)
        val rewindResult = rewindHandler.call(
            mockArgs(mapOf("checkpointId" to cp.checkpointId))
        )

        assertFalse(rewindResult.isError)
        assertTrue(rewindResult.text.contains("Successfully rewound"))
        assertEquals("stable v1", testFile.readText())
    }
}
