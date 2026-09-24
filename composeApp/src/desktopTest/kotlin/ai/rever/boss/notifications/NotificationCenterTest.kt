package ai.rever.boss.notifications

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Persistence and concurrency tests for [NotificationCenter], pinning the same contract every
 * small BOSS state file follows: atomic writes, mutex-serialized mutations, forward-coercing
 * reads, and a bounded inbox. Each test runs against a hermetic temp file and restores the
 * singleton to the real file afterwards.
 */
class NotificationCenterTest {
    private lateinit var tempDir: File
    private lateinit var tempFile: File
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("notif-test-").toFile()
        tempFile = File(tempDir, "notifications.json")
        NotificationCenter.resetForTesting(tempFile)
    }

    @AfterTest
    fun tearDown() {
        NotificationCenter.resetForTesting()
        tempDir.deleteRecursively()
    }

    @Test
    fun `post persists and survives a reload, newest first`() =
        runBlocking {
            NotificationCenter.post("First")
            NotificationCenter.post("Second", "body", NotificationLevel.WARNING, "task-a")

            NotificationCenter.resetForTesting(tempFile)
            val all = NotificationCenter.notifications.value
            assertEquals(2, all.size)
            assertEquals("Second", all.first().title, "newest first")
            assertEquals(NotificationLevel.WARNING, all.first().level)
            assertEquals("task-a", all.first().source)
        }

    @Test
    fun `a blank title is rejected`() {
        runBlocking {
            assertFailsWith<IllegalArgumentException> { NotificationCenter.post("  ") }
        }
    }

    @Test
    fun `unreadCount and markRead behave`() =
        runBlocking {
            val a = NotificationCenter.post("A")
            NotificationCenter.post("B")
            assertEquals(2, NotificationCenter.unreadCount())

            assertTrue(NotificationCenter.markRead(a.id))
            assertEquals(1, NotificationCenter.unreadCount())
            assertFalse(NotificationCenter.markRead(a.id), "already read returns false")
            assertFalse(NotificationCenter.markRead("notif-missing"), "unknown id returns false")
        }

    @Test
    fun `markAllRead reports the number changed`() =
        runBlocking {
            NotificationCenter.post("A")
            NotificationCenter.post("B")
            assertEquals(2, NotificationCenter.markAllRead())
            assertEquals(0, NotificationCenter.unreadCount())
            assertEquals(0, NotificationCenter.markAllRead(), "nothing left to change")
        }

    @Test
    fun `clear empties the inbox`() =
        runBlocking {
            NotificationCenter.post("A")
            assertEquals(1, NotificationCenter.clear())
            assertTrue(NotificationCenter.notifications.value.isEmpty())
            assertEquals(0, NotificationCenter.clear())
        }

    @Test
    fun `the inbox is bounded to MAX_ENTRIES newest entries`() =
        runBlocking {
            var t = 0L
            NotificationCenter.clock = { t++ }
            repeat(NotificationCenter.MAX_ENTRIES + 25) { i -> NotificationCenter.post("N$i") }

            val all = NotificationCenter.notifications.value
            assertEquals(NotificationCenter.MAX_ENTRIES, all.size)
            // The most recent post is retained and is first; the earliest are dropped.
            assertEquals("N${NotificationCenter.MAX_ENTRIES + 24}", all.first().title)
        }

    @Test
    fun `concurrent posts all persist without a lost update`() =
        runBlocking {
            val count = 40
            (1..count)
                .map { i -> async(Dispatchers.Default) { NotificationCenter.post("N$i") } }
                .awaitAll()

            assertEquals(count, NotificationCenter.notifications.value.size)
            val onDisk = json.decodeFromString(NotificationStore.serializer(), tempFile.readText())
            assertEquals(count, onDisk.notifications.size)
        }

    @Test
    fun `a corrupt file loads as an empty inbox rather than throwing`() {
        tempFile.writeText("{ not valid")
        NotificationCenter.resetForTesting(tempFile)
        assertTrue(NotificationCenter.notifications.value.isEmpty())
    }

    @Test
    fun `an unknown level string falls back to INFO`() {
        assertEquals(NotificationLevel.INFO, NotificationLevel.fromString("wat"))
        assertEquals(NotificationLevel.INFO, NotificationLevel.fromString(null))
        assertEquals(NotificationLevel.ERROR, NotificationLevel.fromString("error"))
    }

    @Test
    fun `loading an oversized file keeps only the newest entries`() {
        val entries =
            (0 until NotificationCenter.MAX_ENTRIES + 5).map { index ->
                BossNotification(id = "n$index", title = "N$index", createdAt = index.toLong())
            }
        tempFile.writeText(json.encodeToString(NotificationStore.serializer(), NotificationStore(entries)))
        NotificationCenter.resetForTesting(tempFile)

        assertEquals(NotificationCenter.MAX_ENTRIES, NotificationCenter.notifications.value.size)
        assertEquals(
            "n${NotificationCenter.MAX_ENTRIES + 4}",
            NotificationCenter.notifications.value
                .first()
                .id,
        )
        assertEquals(
            "n5",
            NotificationCenter.notifications.value
                .last()
                .id,
        )
    }

    @Test
    fun `ids remain unique when the clock and random source collide`() =
        runBlocking {
            NotificationCenter.clock = { 1_000L }
            repeat(10) { NotificationCenter.post("N$it") }
            assertEquals(
                10,
                NotificationCenter.notifications.value
                    .map { it.id }
                    .toSet()
                    .size,
            )
        }
}
