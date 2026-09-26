package ai.rever.boss.downloads

import ai.rever.boss.mcp.secrets.captureHostLogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.parallel.ResourceLock
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Persistence and concurrency tests for [DownloadHistoryManager], pinning the shared state-file
 * contract: atomic writes, mutex-serialized mutations, forward-coercing reads, and bounded size.
 */
@ResourceLock("DownloadHistoryManager")
class DownloadHistoryManagerTest {
    private lateinit var tempDir: File
    private lateinit var tempFile: File
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("dl-history-test-").toFile()
        tempFile = File(tempDir, "download-history.json")
        DownloadHistoryManager.resetForTesting(tempFile)
    }

    @AfterTest
    fun tearDown() {
        DownloadHistoryManager.resetForTesting()
        tempDir.deleteRecursively()
    }

    @Test
    fun `record derives the file name, persists, and survives a reload newest first`() =
        runBlocking {
            DownloadHistoryManager.record("https://ex.com/a.zip", "/downloads/a.zip", 1234L)
            DownloadHistoryManager.record("https://ex.com/b.pdf", "/downloads/sub/b.pdf")

            DownloadHistoryManager.resetForTesting(tempFile)
            val all = DownloadHistoryManager.downloads.value
            assertEquals(2, all.size)
            assertEquals("b.pdf", all.first().fileName, "newest first")
            assertEquals("a.zip", all[1].fileName)
            assertEquals(1234L, all[1].sizeBytes)
            assertNull(all.first().sizeBytes)
        }

    @Test
    fun `remove deletes only the named record`() =
        runBlocking {
            val a = DownloadHistoryManager.record("u", "/d/a.txt")
            DownloadHistoryManager.record("u", "/d/b.txt")
            assertTrue(DownloadHistoryManager.remove(a.id))
            assertEquals(1, DownloadHistoryManager.downloads.value.size)
            assertFalse(DownloadHistoryManager.remove(a.id))
        }

    @Test
    fun `clear empties the history`() =
        runBlocking {
            DownloadHistoryManager.record("u", "/d/a.txt")
            assertEquals(1, DownloadHistoryManager.clear())
            assertTrue(DownloadHistoryManager.downloads.value.isEmpty())
            assertEquals(0, DownloadHistoryManager.clear())
        }

    @Test
    fun `the history is bounded to MAX_ENTRIES newest records`() =
        runBlocking {
            var t = 0L
            DownloadHistoryManager.clock = { t++ }
            repeat(DownloadHistoryManager.MAX_ENTRIES + 10) { i -> DownloadHistoryManager.record("u", "/d/f$i.bin") }

            val all = DownloadHistoryManager.downloads.value
            assertEquals(DownloadHistoryManager.MAX_ENTRIES, all.size)
            assertEquals("f${DownloadHistoryManager.MAX_ENTRIES + 9}.bin", all.first().fileName)
        }

    @Test
    fun `concurrent records all persist without a lost update`() =
        runBlocking {
            val count = 40
            (1..count)
                .map { i -> async(Dispatchers.Default) { DownloadHistoryManager.record("u", "/d/f$i.bin") } }
                .awaitAll()

            assertEquals(count, DownloadHistoryManager.downloads.value.size)
            val onDisk = json.decodeFromString(DownloadHistory.serializer(), tempFile.readText())
            assertEquals(count, onDisk.downloads.size)
        }

    @Test
    fun `a corrupt file is preserved until an explicit clear`() =
        runBlocking {
            tempFile.writeText("{ not valid")
            DownloadHistoryManager.resetForTesting(tempFile)
            assertTrue(DownloadHistoryManager.downloads.value.isEmpty())
            assertTrue(DownloadHistoryManager.loadFailed)
            assertFailsWith<IllegalStateException> { DownloadHistoryManager.record("u", "/d/new") }
            assertEquals("{ not valid", tempFile.readText())
            assertEquals(0, DownloadHistoryManager.clear())
            assertFalse(DownloadHistoryManager.loadFailed)
            assertTrue(json.decodeFromString(DownloadHistory.serializer(), tempFile.readText()).downloads.isEmpty())
        }

    @Test
    fun `a corrupt history file is logged without its URL or path`() {
        val secret = "download-token-and-private-path"
        tempFile.writeText(
            """{"downloads":[{"url":"https://example.test/file?$secret","filePath":"/private/$secret"""",
        )

        val (_, logged) =
            captureHostLogs {
                DownloadHistoryManager.resetForTesting(tempFile)
            }

        val failure = logged.single { it.message == "Failed to load download history" }
        assertNull(failure.error, "the decoder exception includes the document and must not be attached")
        assertEquals("JsonDecodingException", failure.data?.get("decodeFailure"))
        for (entry in logged) {
            assertFalse(secret in "${entry.message} ${entry.data} ${entry.error}", "leaked in: $entry")
        }
    }

    @Test
    fun `a failed save does not claim an in-memory change`() =
        runBlocking {
            DownloadHistoryManager.record("u", "/d/first")
            val before = DownloadHistoryManager.downloads.value
            assertTrue(tempFile.delete())
            assertTrue(tempFile.mkdir())

            assertFailsWith<Exception> { DownloadHistoryManager.record("u", "/d/second") }
            assertEquals(before, DownloadHistoryManager.downloads.value)
        }

    @Test
    fun `first list loads history without creating the parent at initialization`(): Unit =
        runBlocking {
            DownloadHistoryManager.record("u", "/d/first")
            val saved = tempFile.readText()
            val missingParent = File(tempDir, "nested")
            val nestedFile = File(missingParent, "download-history.json")
            DownloadHistoryManager.resetForTesting(nestedFile, loadNow = false)
            assertFalse(missingParent.exists())
            assertTrue(DownloadHistoryManager.list().isEmpty())
            assertTrue(missingParent.isDirectory)

            nestedFile.writeText(saved)
            DownloadHistoryManager.resetForTesting(nestedFile, loadNow = false)
            assertEquals(1, DownloadHistoryManager.list().size)
        }
}
