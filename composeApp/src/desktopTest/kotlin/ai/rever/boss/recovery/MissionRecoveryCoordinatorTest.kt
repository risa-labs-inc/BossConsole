package ai.rever.boss.recovery

import ai.rever.boss.recovery.models.AgentClaim
import ai.rever.boss.recovery.models.ClaimType
import ai.rever.boss.recovery.models.RecoveryResult
import ai.rever.boss.recovery.models.VerificationStatus
import ai.rever.boss.recovery.runtime.MissionRecoveryCoordinator
import ai.rever.boss.recovery.runtime.RecoveryEvent
import ai.rever.boss.recovery.storage.WorkspaceCheckpointStorage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun `rewindToCheckpoint resets isBusy when the project root disappears`() =
        runBlocking {
            File(tempProjectRoot, "src/App.kt").also {
                it.parentFile.mkdirs()
                it.writeText("initial")
            }
            coordinator.startMission(tempProjectRoot, missionId = "mission-busy-rewind")
            val cp = coordinator.createCheckpoint("v1")

            // Moving or deleting the root makes SafePathResolver.canonicalRoot throw an
            // IOException inside WorkspaceReconciler.rewind, after isBusy was set.
            assertTrue(tempProjectRoot.deleteRecursively())

            assertFailsWith<IOException> { coordinator.rewindToCheckpoint(cp.checkpointId) }
            assertFalse(coordinator.state.value.isBusy, "isBusy must reset when rewind throws")
        }

    @Test
    fun `previewRecovery resets isBusy when the project root disappears`() =
        runBlocking {
            File(tempProjectRoot, "src/App.kt").also {
                it.parentFile.mkdirs()
                it.writeText("initial")
            }
            coordinator.startMission(tempProjectRoot, missionId = "mission-busy-preview")
            val cp = coordinator.createCheckpoint("v1")

            assertTrue(tempProjectRoot.deleteRecursively())

            assertFailsWith<IOException> { coordinator.previewRecovery(cp.checkpointId) }
            assertFalse(coordinator.state.value.isBusy, "isBusy must reset when preview throws")
        }

    @Test
    fun `rewind records its result even when the calling coroutine is cancelled mid-rewind`() =
        runBlocking {
            // A workspace big enough that the rewind (double hash + file copies)
            // outlives the cancellation below on any machine.
            repeat(2_000) { index ->
                File(tempProjectRoot, "payload-$index.dat").writeText("y".repeat(64 * 1024))
            }
            File(tempProjectRoot, "keep.txt").writeText("keep")

            coordinator.startMission(tempProjectRoot, missionId = "mission-cancel")
            val checkpoint = coordinator.createCheckpoint(label = "pre-edit")

            // Post-checkpoint edit so the rewind has restore work to do.
            File(tempProjectRoot, "keep.txt").writeText("edited")

            val events = mutableListOf<RecoveryEvent>()
            val collector = launch { coordinator.events.collect { events += it } }
            delay(50)

            val rewindJob = launch { coordinator.rewindToCheckpoint(checkpoint.checkpointId) }
            delay(20)
            rewindJob.cancel()
            rewindJob.join()
            delay(50)
            collector.cancel()

            // The caller's cancellation must not discard a rewind that completed:
            // the result is recorded, the event is emitted, and isBusy resets.
            val result = coordinator.state.value.lastRecoveryResult
            assertNotNull(result)
            assertIs<RecoveryResult.Success>(result)
            assertEquals(false, coordinator.state.value.isBusy)
            assertTrue(events.any { it is RecoveryEvent.RewindExecuted })
        }
}
