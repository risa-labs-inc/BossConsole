package ai.rever.boss.recovery

import ai.rever.boss.recovery.models.AgentClaim
import ai.rever.boss.recovery.models.ClaimType
import ai.rever.boss.recovery.models.RecoveryResult
import ai.rever.boss.recovery.models.VerificationStatus
import ai.rever.boss.recovery.runtime.MissionRecoveryCoordinator
import ai.rever.boss.recovery.runtime.RecoveryEvent
import ai.rever.boss.recovery.storage.WorkspaceCheckpointStorage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MissionRecoveryCoordinatorTest {
    private lateinit var tempProjectRoot: File
    private lateinit var tempStorageDir: File
    private lateinit var coordinator: MissionRecoveryCoordinator

    @BeforeTest
    fun setup() {
        tempProjectRoot =
            kotlin.io.path
                .createTempDirectory("coordinator-project-test")
                .toFile()
        tempStorageDir =
            kotlin.io.path
                .createTempDirectory("coordinator-storage-test")
                .toFile()
        val storage = WorkspaceCheckpointStorage(baseStorageDir = tempStorageDir)
        coordinator = MissionRecoveryCoordinator(storage = storage)
    }

    @AfterTest
    fun tearDown() {
        tempProjectRoot.deleteRecursively()
        tempStorageDir.deleteRecursively()
    }

    @Test
    fun `full lifecycle startMission, checkpoint, verify, and rewind`() =
        runBlocking {
            // 1. Initial file
            val srcFile =
                File(tempProjectRoot, "src/App.kt").also {
                    it.parentFile.mkdirs()
                    it.writeText("initial code")
                }

            // 2. Start Mission
            val baseline = coordinator.startMission(tempProjectRoot, missionId = "mission-flow-1")
            assertEquals("mission-flow-1", baseline.missionId)
            assertEquals("mission-flow-1", coordinator.state.value.activeMissionId)
            assertNotNull(coordinator.state.value.baseline)

            // 3. Make working progress & create Checkpoint
            srcFile.writeText("feature v1 implemented")
            val cp1 = coordinator.createCheckpoint("Feature V1")
            assertEquals(1, coordinator.state.value.checkpoints.size)
            assertEquals("Feature V1", cp1.label)

            // 4. Verify claim
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val claim = AgentClaim(ClaimType.TESTS_PASSED, "Tests pass")
            val vResult = coordinator.verifyClaim(if (isWindows) "echo OK" else "echo OK", agentClaim = claim)
            assertEquals(VerificationStatus.PASS, vResult.status)
            assertEquals(vResult, coordinator.state.value.latestVerification)

            // 5. Agent creates bad changes and untracked junk
            srcFile.writeText("broken code")
            val junkFile = File(tempProjectRoot, "junk.tmp").also { it.writeText("trash") }

            // 6. Rewind to Checkpoint 1
            val rResult = coordinator.rewindToCheckpoint(cp1.checkpointId)
            assertIs<RecoveryResult.Success>(rResult)
            assertEquals(1, rResult.restoredFilesCount)
            assertEquals(1, rResult.removedFilesCount)

            // 7. Verify file contents restored and junk removed
            assertEquals("feature v1 implemented", srcFile.readText())
            assertFalse(junkFile.exists())
        }
}
