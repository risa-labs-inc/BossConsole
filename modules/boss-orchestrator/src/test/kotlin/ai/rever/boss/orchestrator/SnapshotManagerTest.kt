package ai.rever.boss.orchestrator

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnapshotManagerTest {

    private lateinit var tempDir: java.io.File
    private lateinit var manager: SnapshotManager

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("boss-snap-test").toFile()
        manager = SnapshotManager(tempDir)
    }

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `save returns a non-blank snapshot ID`() {
        val id = manager.save("proc-1", "hello".toByteArray())
        assertTrue(id.isNotBlank())
    }

    @Test
    fun `save and load round-trips data correctly`() {
        val data = "test snapshot data".toByteArray()
        manager.save("proc-1", data, "test description")

        val loaded = manager.loadLatest("proc-1")
        assertNotNull(loaded)
        assertEquals("test snapshot data", loaded.decodeToString())
    }

    @Test
    fun `loadLatest with no snapshots returns null`() {
        assertNull(manager.loadLatest("nonexistent-proc"))
    }

    @Test
    fun `loadLatest returns most recently saved snapshot`() {
        manager.save("proc-2", "first".toByteArray())
        Thread.sleep(5)
        manager.save("proc-2", "second".toByteArray())
        Thread.sleep(5)
        manager.save("proc-2", "third".toByteArray())

        val loaded = manager.loadLatest("proc-2")
        assertNotNull(loaded)
        assertEquals("third", loaded.decodeToString())
    }

    @Test
    fun `listSnapshots returns all snapshots most recent first`() {
        manager.save("proc-3", "first".toByteArray())
        Thread.sleep(5)
        manager.save("proc-3", "second".toByteArray())

        val list = manager.listSnapshots("proc-3")
        assertEquals(2, list.size)
        assertTrue(list[0].timestamp >= list[1].timestamp)
    }

    @Test
    fun `listSnapshots for unknown process returns empty list`() {
        assertEquals(emptyList(), manager.listSnapshots("unknown-proc"))
    }

    @Test
    fun `listSnapshots includes description when saved`() {
        manager.save("proc-4", "data".toByteArray(), "my description")
        val list = manager.listSnapshots("proc-4")
        assertEquals(1, list.size)
        assertEquals("my description", list[0].description)
    }

    @Test
    fun `cleanup keeps only N latest snapshots`() {
        repeat(8) { i ->
            manager.save("proc-5", "data-$i".toByteArray())
            Thread.sleep(5)
        }
        manager.cleanup("proc-5", keepLast = 5)

        val remaining = manager.listSnapshots("proc-5")
        assertEquals(5, remaining.size)
    }

    @Test
    fun `cleanup with keepLast greater than total does nothing`() {
        repeat(3) { i ->
            manager.save("proc-6", "data-$i".toByteArray())
            Thread.sleep(5)
        }
        manager.cleanup("proc-6", keepLast = 10)

        assertEquals(3, manager.listSnapshots("proc-6").size)
    }

    @Test
    fun `cleanup on nonexistent process does not throw`() {
        manager.cleanup("no-such-proc", keepLast = 5)
    }

    @Test
    fun `SnapshotInfo has correct processId and positive sizeBytes`() {
        manager.save("proc-7", ByteArray(42) { it.toByte() })
        val info = manager.listSnapshots("proc-7").first()

        assertEquals("proc-7", info.processId)
        assertTrue(info.sizeBytes > 0)
        assertTrue(info.timestamp > 0)
    }
}
