package ai.rever.boss.recovery

import ai.rever.boss.recovery.models.CheckpointManifest
import ai.rever.boss.recovery.storage.WorkspaceCheckpointStorage
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceCheckpointStorageTest {

    private lateinit var tempProjectRoot: File
    private lateinit var tempStorageDir: File
    private lateinit var storage: WorkspaceCheckpointStorage

    @BeforeTest
    fun setup() {
        tempProjectRoot = kotlin.io.path.createTempDirectory("storage-project-test").toFile()
        tempStorageDir = kotlin.io.path.createTempDirectory("storage-dir-test").toFile()
        storage = WorkspaceCheckpointStorage(baseStorageDir = tempStorageDir)
    }

    @AfterTest
    fun tearDown() {
        tempProjectRoot.deleteRecursively()
        tempStorageDir.deleteRecursively()
    }

    @Test
    fun `createCheckpoint creates manifest, checkpoint metadata and copies file blobs`() = runBlocking {
        val srcDir = File(tempProjectRoot, "src").also { it.mkdirs() }
        File(srcDir, "App.kt").writeText("class App")
        File(tempProjectRoot, "config.json").writeText("{\"env\":\"test\"}")

        val checkpoint = storage.createCheckpoint(
            missionId = "m-123",
            checkpointId = "cp-001",
            label = "Initial State",
            projectRoot = tempProjectRoot
        )

        assertEquals("cp-001", checkpoint.checkpointId)
        assertEquals("m-123", checkpoint.missionId)
        assertEquals("Initial State", checkpoint.label)
        assertEquals(2, checkpoint.manifest.files.size)

        // Check filesystem layout in storage
        val snapshotFilesDir = storage.getSnapshotFilesDir("m-123", "cp-001")
        assertTrue(snapshotFilesDir.exists())
        assertTrue(File(snapshotFilesDir, "src/App.kt").exists())
        assertEquals("class App", File(snapshotFilesDir, "src/App.kt").readText())
        assertTrue(File(snapshotFilesDir, "config.json").exists())
    }

    @Test
    fun `loadCheckpoint deserializes stored checkpoint correctly`() = runBlocking {
        File(tempProjectRoot, "index.ts").writeText("console.log('hi')")

        storage.createCheckpoint(
            missionId = "m-load",
            checkpointId = "cp-load-1",
            label = "Before Refactor",
            projectRoot = tempProjectRoot
        )

        val loaded = storage.loadCheckpoint("m-load", "cp-load-1")
        assertNotNull(loaded)
        assertEquals("cp-load-1", loaded.checkpointId)
        assertEquals("m-load", loaded.missionId)
        assertEquals("Before Refactor", loaded.label)
        assertEquals(1, loaded.manifest.files.size)
        assertTrue(loaded.manifest.files.containsKey("index.ts"))
    }

    @Test
    fun `loadCheckpoint returns null for non-existent checkpoint`() = runBlocking {
        val loaded = storage.loadCheckpoint("m-none", "cp-none")
        assertNull(loaded)
    }

    @Test
    fun `listCheckpoints returns checkpoints ordered most recent first`() = runBlocking {
        File(tempProjectRoot, "v.txt").writeText("v1")
        storage.createCheckpoint(
            missionId = "m-list",
            checkpointId = "cp-1",
            label = "First",
            projectRoot = tempProjectRoot,
            timestamp = 1000L,
        )

        File(tempProjectRoot, "v.txt").writeText("v2")
        storage.createCheckpoint(
            missionId = "m-list",
            checkpointId = "cp-2",
            label = "Second",
            projectRoot = tempProjectRoot,
            timestamp = 2000L,
        )

        val list = storage.listCheckpoints("m-list")
        assertEquals(2, list.size)
        assertEquals("cp-2", list[0].checkpointId)
        assertEquals("cp-1", list[1].checkpointId)
    }

    @Test
    fun `deleteCheckpoint removes checkpoint from disk`() = runBlocking {
        File(tempProjectRoot, "file.txt").writeText("content")
        storage.createCheckpoint(
            missionId = "m-del",
            checkpointId = "cp-del-1",
            label = "To Delete",
            projectRoot = tempProjectRoot
        )

        assertNotNull(storage.loadCheckpoint("m-del", "cp-del-1"))

        val deleted = storage.deleteCheckpoint("m-del", "cp-del-1")
        assertTrue(deleted)
        assertNull(storage.loadCheckpoint("m-del", "cp-del-1"))
    }

    @Test
    fun `storage operations reject malicious path traversal identifiers`() = runBlocking {
        File(tempProjectRoot, "file.txt").writeText("content")

        kotlin.test.assertFailsWith<SecurityException> {
            storage.createCheckpoint(
                missionId = "../../etc",
                checkpointId = "cp-1",
                label = "Attack",
                projectRoot = tempProjectRoot
            )
        }

        kotlin.test.assertFailsWith<SecurityException> {
            storage.loadCheckpoint("m-valid", "../escape-cp")
        }

        kotlin.test.assertFailsWith<SecurityException> {
            storage.listCheckpoints("foo/bar")
        }

        kotlin.test.assertFailsWith<SecurityException> {
            storage.deleteCheckpoint("../mission", "cp-1")
        }
    }
}
