package ai.rever.boss.orchestrator

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
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
    fun `construction fails when snapshots path is occupied by a file`() {
        val occupiedDataDir = Files.createTempDirectory("boss-snap-occupied").toFile()
        try {
            java.io.File(occupiedDataDir, "snapshots").writeText("occupied")

            assertFails { SnapshotManager(occupiedDataDir) }
        } finally {
            occupiedDataDir.deleteRecursively()
        }
    }

    @Test
    fun `concurrent first saves both succeed`() {
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val saves =
                listOf("first", "second").map { value ->
                    executor.submit<String> {
                        start.await()
                        manager.save("proc-concurrent", value.toByteArray())
                    }
                }
            start.countDown()

            assertEquals(2, saves.map { it.get() }.distinct().size)
            assertEquals(2, manager.listSnapshots("proc-concurrent").size)
        } finally {
            executor.shutdownNow()
        }
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
        manager.save("proc-2", "second".toByteArray())
        manager.save("proc-2", "third".toByteArray())

        val loaded = manager.loadLatest("proc-2")
        assertNotNull(loaded)
        assertEquals("third", loaded.decodeToString())
    }

    @Test
    fun `equal wall-clock timestamps still preserve save order`() {
        val fixedClockManager = SnapshotManager(tempDir) { 1_000L }

        fixedClockManager.save("proc-same-ms", "first".toByteArray())
        val newestId = fixedClockManager.save("proc-same-ms", "second".toByteArray())

        assertEquals("second", fixedClockManager.loadLatest("proc-same-ms")?.decodeToString())
        assertEquals(newestId, fixedClockManager.listSnapshots("proc-same-ms").first().id)
    }

    @Test
    fun `a backwards wall clock cannot make new state look older`() {
        val times = ArrayDeque(listOf(2_000L, 1_000L))
        val movingClockManager = SnapshotManager(tempDir) { times.removeFirst() }

        movingClockManager.save("proc-clock-reset", "before reset".toByteArray())
        val newestId = movingClockManager.save("proc-clock-reset", "after reset".toByteArray())

        val snapshots = movingClockManager.listSnapshots("proc-clock-reset")
        assertEquals(listOf(2_001L, 2_000L), snapshots.map { it.timestamp })
        assertEquals(newestId, snapshots.first().id)
        assertEquals("after reset", movingClockManager.loadLatest("proc-clock-reset")?.decodeToString())
    }

    @Test
    fun `allocation resumes above snapshots already on disk`() {
        val futureTimestamp = System.currentTimeMillis() + 1_000_000L
        val processDir = java.io.File(tempDir, "snapshots/proc-restarted").apply { mkdirs() }
        java.io.File(processDir, "$futureTimestamp-existing.snapshot").writeText("old")
        val restartedManager = SnapshotManager(tempDir) { 1L }

        val newestId = restartedManager.save("proc-restarted", "new".toByteArray())

        val snapshots = restartedManager.listSnapshots("proc-restarted")
        assertEquals(futureTimestamp + 1L, snapshots.first().timestamp)
        assertEquals(newestId, snapshots.first().id)
        assertEquals("new", restartedManager.loadLatest("proc-restarted")?.decodeToString())
    }

    @Test
    fun `concurrent saves receive distinct ordering keys`() {
        val fixedClockManager = SnapshotManager(tempDir) { 3_000L }
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val saves =
                (1..8).map { value ->
                    executor.submit<String> {
                        start.await()
                        fixedClockManager.save("proc-concurrent-order", "state-$value".toByteArray())
                    }
                }
            start.countDown()
            saves.forEach { it.get() }

            val timestamps = fixedClockManager.listSnapshots("proc-concurrent-order").map { it.timestamp }
            assertEquals(8, timestamps.distinct().size)
            assertEquals(timestamps.sortedDescending(), timestamps)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `listSnapshots returns all snapshots most recent first`() {
        manager.save("proc-3", "first".toByteArray())
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
        val ids =
            (0 until 8).map { i ->
                manager.save("proc-5", "data-$i".toByteArray())
            }
        manager.cleanup("proc-5", keepLast = 5)

        val remaining = manager.listSnapshots("proc-5")
        assertEquals(5, remaining.size)
        assertEquals(ids.takeLast(5).reversed(), remaining.map { it.id })
    }

    @Test
    fun `cleanup with keepLast greater than total does nothing`() {
        repeat(3) { i ->
            manager.save("proc-6", "data-$i".toByteArray())
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

    @Test
    fun `save rejects path traversal process IDs`() {
        assertFailsWith<IllegalArgumentException> {
            manager.save("../../escape", "payload".toByteArray())
        }
        assertFailsWith<IllegalArgumentException> {
            manager.save("../parent", "payload".toByteArray())
        }
        assertFailsWith<IllegalArgumentException> {
            manager.save("sub/dir", "payload".toByteArray())
        }
        assertFailsWith<IllegalArgumentException> {
            manager.save("invalid.", "payload".toByteArray())
        }
        assertFailsWith<IllegalArgumentException> {
            manager.save("CON", "payload".toByteArray())
        }
    }

    @Test
    fun `loadLatest and listSnapshots reject traversal process IDs`() {
        assertFailsWith<IllegalArgumentException> {
            manager.loadLatest("../../escape")
        }
        assertFailsWith<IllegalArgumentException> {
            manager.listSnapshots("../../escape")
        }
        assertFailsWith<IllegalArgumentException> {
            manager.cleanup("../../escape")
        }
    }

    @Test
    fun `save restricts permissions to owner on posix filesystems`() {
        manager.save("proc-perms", "secret-payload".toByteArray(), "secret description")
        val snapshotsDir = java.io.File(tempDir, "snapshots/proc-perms")
        val files = snapshotsDir.listFiles() ?: emptyArray()
        assertTrue(files.isNotEmpty())

        for (file in files) {
            val path = file.toPath()
            val hasPosix = path.fileSystem.supportedFileAttributeViews().contains("posix")
            if (hasPosix) {
                val perms = Files.getPosixFilePermissions(path)
                val ownerOnly =
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                    )
                assertEquals(ownerOnly, perms, "Expected owner-only permissions for ${file.name}")
            }
        }
        assertTrue(files.none { it.extension == "tmp" })
    }

    @Test
    fun `read list and cleanup reject a symlinked process directory`() {
        val outside = Files.createTempDirectory("snapshot-outside").toFile()
        try {
            java.io.File(outside, "1-external.snapshot").writeText("external")
            val link = java.io.File(tempDir, "snapshots/proc-link").toPath()
            try {
                Files.createSymbolicLink(link, outside.toPath())
            } catch (_: UnsupportedOperationException) {
                return
            } catch (_: java.nio.file.FileSystemException) {
                return
            }

            assertFailsWith<IllegalArgumentException> {
                manager.save("proc-link", "replacement".toByteArray())
            }
            assertFailsWith<IllegalArgumentException> { manager.loadLatest("proc-link") }
            assertFailsWith<IllegalArgumentException> { manager.listSnapshots("proc-link") }
            assertFailsWith<IllegalArgumentException> { manager.cleanup("proc-link") }
            assertTrue(java.io.File(outside, "1-external.snapshot").exists())
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun `snapshot and description symlinks are not followed`() {
        manager.save("proc-files", "safe".toByteArray())
        val processDir = java.io.File(tempDir, "snapshots/proc-files")
        val outside = java.io.File(tempDir, "outside-secret").apply { writeText("secret") }
        try {
            Files.createSymbolicLink(
                java.io.File(processDir, "9999999999999-external.snapshot").toPath(),
                outside.toPath(),
            )
            val realSnapshot =
                processDir
                    .listFiles { file -> file.extension == "snapshot" }
                    .orEmpty()
                    .single { !Files.isSymbolicLink(it.toPath()) }
            Files.createSymbolicLink(
                java.io.File(processDir, "${realSnapshot.nameWithoutExtension}.desc").toPath(),
                outside.toPath(),
            )
        } catch (_: UnsupportedOperationException) {
            return
        } catch (_: java.nio.file.FileSystemException) {
            return
        }

        assertEquals("safe", manager.loadLatest("proc-files")?.decodeToString())
        assertEquals("", manager.listSnapshots("proc-files").single().description)
    }
}
