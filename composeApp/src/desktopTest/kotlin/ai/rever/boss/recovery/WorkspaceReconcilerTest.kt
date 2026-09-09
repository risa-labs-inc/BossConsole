package ai.rever.boss.recovery

import ai.rever.boss.recovery.baseline.WorkspaceBaselineCapturer
import ai.rever.boss.recovery.models.RecoveryResult
import ai.rever.boss.recovery.reconciliation.WorkspaceReconciler
import ai.rever.boss.recovery.storage.WorkspaceCheckpointStorage
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkspaceReconcilerTest {

    private lateinit var tempProjectRoot: File
    private lateinit var tempStorageDir: File
    private lateinit var storage: WorkspaceCheckpointStorage

    @BeforeTest
    fun setup() {
        tempProjectRoot = kotlin.io.path.createTempDirectory("reconciler-project-test").toFile()
        tempStorageDir = kotlin.io.path.createTempDirectory("reconciler-storage-test").toFile()
        storage = WorkspaceCheckpointStorage(baseStorageDir = tempStorageDir)
    }

    @AfterTest
    fun tearDown() {
        tempProjectRoot.deleteRecursively()
        tempStorageDir.deleteRecursively()
    }

    @Test
    fun `rewind restores modified files to checkpoint state`() = runBlocking {
        // Step 1: Baseline
        val file1 = File(tempProjectRoot, "src/App.kt").also {
            it.parentFile.mkdirs()
            it.writeText("initial app code")
        }
        val baseline = WorkspaceBaselineCapturer.captureBaseline("m-rec", tempProjectRoot)

        // Step 2: Checkpoint 1
        file1.writeText("working checkpoint 1 code")
        val cp1 = storage.createCheckpoint("m-rec", "cp-1", "Working State", tempProjectRoot)

        // Step 3: Agent breaks the file
        file1.writeText("BROKEN SYNTAX ERROR CODE !!!")

        // Step 4: Rewind to Checkpoint 1
        val result = WorkspaceReconciler.rewind(
            baseline = baseline,
            targetCheckpoint = cp1,
            projectRoot = tempProjectRoot,
            storage = storage
        )

        assertIs<RecoveryResult.Success>(result)
        assertEquals(1, result.restoredFilesCount)
        assertEquals(0, result.removedFilesCount)
        assertEquals("working checkpoint 1 code", file1.readText())
    }

    @Test
    fun `rewind removes mission-added untracked files while preserving pre-existing baseline files`() = runBlocking {
        // Step 1: User's pre-existing note (Baseline)
        val userNote = File(tempProjectRoot, "notes.txt").also { it.writeText("User uncommitted notes") }
        val baseline = WorkspaceBaselineCapturer.captureBaseline("m-rec2", tempProjectRoot)

        // Step 2: Checkpoint 1
        val srcFile = File(tempProjectRoot, "src/Service.kt").also {
            it.parentFile.mkdirs()
            it.writeText("class Service")
        }
        val cp1 = storage.createCheckpoint("m-rec2", "cp-1", "Added Service", tempProjectRoot)

        // Step 3: Agent creates new unwanted files and mutates service
        val junkFile = File(tempProjectRoot, "temp_corrupted_junk.txt").also { it.writeText("junk") }
        val agentModule = File(tempProjectRoot, "src/extra/Extra.kt").also {
            it.parentFile.mkdirs()
            it.writeText("class Extra")
        }
        srcFile.writeText("corrupted service")

        // Step 4: Rewind to Checkpoint 1
        val result = WorkspaceReconciler.rewind(
            baseline = baseline,
            targetCheckpoint = cp1,
            projectRoot = tempProjectRoot,
            storage = storage
        )

        assertIs<RecoveryResult.Success>(result)
        assertEquals(2, result.restoredFilesCount) // notes.txt & Service.kt tracked in checkpoint
        assertEquals(2, result.removedFilesCount) // junkFile & agentModule removed

        // Verify pre-existing baseline user note was NOT deleted
        assertTrue(userNote.exists())
        assertEquals("User uncommitted notes", userNote.readText())

        // Verify service is restored
        assertEquals("class Service", srcFile.readText())

        // Verify mission added junk files were cleaned up
        assertFalse(junkFile.exists())
        assertFalse(agentModule.exists())
    }

    @Test
    fun `rewind returns InvalidCheckpoint if storage directory is missing`() = runBlocking {
        val file1 = File(tempProjectRoot, "main.kt").also { it.writeText("main") }
        val baseline = WorkspaceBaselineCapturer.captureBaseline("m-invalid", tempProjectRoot)
        val cp = storage.createCheckpoint("m-invalid", "cp-valid", "Valid", tempProjectRoot)

        // Delete storage snapshot directory to simulate corruption
        storage.getSnapshotFilesDir("m-invalid", "cp-valid").deleteRecursively()

        val result = WorkspaceReconciler.rewind(
            baseline = baseline,
            targetCheckpoint = cp,
            projectRoot = tempProjectRoot,
            storage = storage
        )

        assertIs<RecoveryResult.InvalidCheckpoint>(result)
        assertEquals("cp-valid", result.checkpointId)
        assertFalse(result.isSuccessful)
    }

    @Test
    fun `rewind detects Conflict when modified baseline file is missing from target checkpoint`() = runBlocking {
        // Step 1: Baseline contains config.json and app.kt
        val configFile = File(tempProjectRoot, "config.json").also { it.writeText("{\"v\": 1}") }
        val appFile = File(tempProjectRoot, "app.kt").also { it.writeText("fun app() = 1") }
        val baseline = WorkspaceBaselineCapturer.captureBaseline("m-conf", tempProjectRoot)

        // Step 2: Delete config.json before taking checkpoint 1 (so checkpoint does not have it)
        configFile.delete()
        appFile.writeText("fun app() = 2")
        val cp1 = storage.createCheckpoint("m-conf", "cp-1", "No Config", tempProjectRoot)

        // Step 3: User recreated/modified config.json on disk with new changes
        configFile.writeText("{\"v\": 99, \"user_secret\": true}")

        // Step 4: Rewind with allowOverwriteConflicts = false -> should detect CONFLICT
        val conflictResult = WorkspaceReconciler.rewind(
            baseline = baseline,
            targetCheckpoint = cp1,
            projectRoot = tempProjectRoot,
            storage = storage,
            allowOverwriteConflicts = false
        )

        assertIs<RecoveryResult.Conflict>(conflictResult)
        assertEquals("cp-1", conflictResult.checkpointId)
        assertTrue(conflictResult.conflictingFiles.contains("config.json"))
        assertFalse(conflictResult.isSuccessful)

        // Step 5: Rewind with allowOverwriteConflicts = true -> should succeed and overwrite/reconcile
        val forceResult = WorkspaceReconciler.rewind(
            baseline = baseline,
            targetCheckpoint = cp1,
            projectRoot = tempProjectRoot,
            storage = storage,
            allowOverwriteConflicts = true
        )

        assertIs<RecoveryResult.Success>(forceResult)
        assertTrue(forceResult.isSuccessful)
    }
}
