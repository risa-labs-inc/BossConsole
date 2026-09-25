package ai.rever.boss.snippets

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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Persistence and concurrency tests for [SnippetLibraryManager].
 *
 * Each test runs against a hermetic temp file via [SnippetLibraryManager.resetForTesting] and
 * restores the singleton to the real file when it finishes, so other tests in the same JVM (which
 * use the real file) are unaffected. The contract pinned here is the one every small BOSS state
 * file follows: atomic writes, mutex-serialized mutations, and forward-coercing reads.
 */
class SnippetLibraryManagerTest {
    private lateinit var tempDir: File
    private lateinit var tempFile: File
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("snippet-test-").toFile()
        tempFile = File(tempDir, "snippets.json")
        SnippetLibraryManager.resetForTesting(tempFile)
    }

    @AfterTest
    fun tearDown() {
        SnippetLibraryManager.resetForTesting()
        tempDir.deleteRecursively()
    }

    @Test
    fun `add persists a snippet that survives a reload`() =
        runBlocking {
            val saved = SnippetLibraryManager.add("Greeting", "Hello, world", listOf("demo"))
            assertTrue(tempFile.exists(), "the library file should exist after an add")

            // Reload from disk into the same singleton and confirm the snippet is there.
            SnippetLibraryManager.resetForTesting(tempFile)
            val reloaded = SnippetLibraryManager.get(saved.id)
            assertNotNull(reloaded)
            assertEquals("Greeting", reloaded.title)
            assertEquals("Hello, world", reloaded.body)
            assertEquals(listOf("demo"), reloaded.tags)
        }

    @Test
    fun `update preserves createdAt and bumps updatedAt`() =
        runBlocking {
            var t = 1_000L
            SnippetLibraryManager.clock = { t }
            val original = SnippetLibraryManager.add("Title", "body")
            t = 2_000L
            val edited = SnippetLibraryManager.update(original.id, "Title 2", "body 2", listOf("x"))

            assertNotNull(edited)
            assertEquals(original.id, edited.id)
            assertEquals(original.createdAt, edited.createdAt, "createdAt must be preserved on update")
            assertEquals(2_000L, edited.updatedAt)
            assertEquals("Title 2", edited.title)
        }

    @Test
    fun `update without tags preserves the existing set`() =
        runBlocking {
            val original = SnippetLibraryManager.add("Title", "body", listOf("keep", "this"))

            val edited = SnippetLibraryManager.update(original.id, "Title 2", "body 2")

            assertEquals(listOf("keep", "this"), edited?.tags, "omitted tags must not erase the existing set")
            val cleared = SnippetLibraryManager.update(original.id, "Title 2", "body 2", emptyList())
            assertEquals(emptyList(), cleared?.tags, "an explicit empty list is how tags are cleared")
        }

    @Test
    fun `update of an unknown id returns null and does not create`() =
        runBlocking {
            val result = SnippetLibraryManager.update("snippet-does-not-exist", "t", "b")
            assertNull(result)
            assertTrue(SnippetLibraryManager.snippets.value.isEmpty())
        }

    @Test
    fun `remove deletes only the named snippet`() =
        runBlocking {
            val a = SnippetLibraryManager.add("A", "a")
            val b = SnippetLibraryManager.add("B", "b")

            assertTrue(SnippetLibraryManager.remove(a.id))
            assertNull(SnippetLibraryManager.get(a.id))
            assertNotNull(SnippetLibraryManager.get(b.id))
            assertFalse(SnippetLibraryManager.remove(a.id), "removing an already-gone id returns false")
        }

    @Test
    fun `byTag matches case-insensitively`() =
        runBlocking {
            SnippetLibraryManager.add("A", "a", listOf("Kotlin"))
            SnippetLibraryManager.add("B", "b", listOf("rust"))

            assertEquals(1, SnippetLibraryManager.byTag("kotlin").size)
            assertEquals(1, SnippetLibraryManager.byTag("KOTLIN").size)
            assertTrue(SnippetLibraryManager.byTag("python").isEmpty())
        }

    @Test
    fun `a blank title is rejected`() {
        runBlocking {
            assertFailsWith<IllegalArgumentException> { SnippetLibraryManager.add("   ", "body") }
        }
    }

    @Test
    fun `tags are trimmed, de-duplicated, and emptied entries dropped`() =
        runBlocking {
            val saved = SnippetLibraryManager.add("T", "b", listOf(" a ", "a", "", "b"))
            assertEquals(listOf("a", "b"), saved.tags)
        }

    @Test
    fun `concurrent adds all persist without a lost update`() =
        runBlocking {
            val count = 40
            (1..count)
                .map { i ->
                    async(Dispatchers.Default) { SnippetLibraryManager.add("Snippet $i", "body $i") }
                }.awaitAll()

            assertEquals(count, SnippetLibraryManager.snippets.value.size)

            // The on-disk state must match memory: decode the file and compare the id set.
            val onDisk = json.decodeFromString(SnippetLibrary.serializer(), tempFile.readText())
            assertEquals(count, onDisk.snippets.size, "every concurrent add must be persisted")
            assertEquals(
                SnippetLibraryManager.snippets.value
                    .map { it.id }
                    .toSet(),
                onDisk.snippets.map { it.id }.toSet(),
            )
        }

    @Test
    fun `a corrupt file loads as an empty library rather than throwing`() {
        tempFile.writeText("{ this is not valid json")
        SnippetLibraryManager.resetForTesting(tempFile)
        assertTrue(SnippetLibraryManager.snippets.value.isEmpty())
    }

    @Test
    fun `an unknown key in the file is ignored rather than aborting the load`() {
        tempFile.writeText(
            "{\"snippets\":[{\"id\":\"snippet-1\",\"title\":\"T\",\"body\":\"b\"," +
                "\"tags\":[],\"createdAt\":0,\"updatedAt\":0,\"futureField\":true}]}",
        )
        SnippetLibraryManager.resetForTesting(tempFile)
        assertEquals(1, SnippetLibraryManager.snippets.value.size)
        assertEquals("T", SnippetLibraryManager.get("snippet-1")?.title)
    }

    @Test
    fun `atomicWriteText leaves no temp files behind`() =
        runBlocking {
            SnippetLibraryManager.add("A", "a")
            val strays = tempDir.listFiles()?.filter { it.name != "snippets.json" } ?: emptyList()
            assertTrue(strays.isEmpty(), "no temp files should remain: $strays")
        }

    @Test
    fun `ids remain unique when the clock and random source collide`() =
        runBlocking {
            SnippetLibraryManager.clock = { 1_000L }
            repeat(10) { SnippetLibraryManager.add("N$it", "body") }
            assertEquals(
                10,
                SnippetLibraryManager.snippets.value
                    .map { it.id }
                    .toSet()
                    .size,
            )
        }

    // -----------------------------------------------------------------
    // Bounds live in the store, so a writer other than the MCP tool meets them too (#1660 review).
    // -----------------------------------------------------------------

    @Test
    fun `the store refuses over-long fields on add and on update`() =
        runBlocking {
            val tooLongTitle = "t".repeat(SnippetLibraryManager.MAX_TITLE_CHARS + 1)
            val tooLongBody = "b".repeat(SnippetLibraryManager.MAX_BODY_CHARS + 1)
            val tooManyTagChars = listOf("x".repeat(SnippetLibraryManager.MAX_TAGS_CHARS + 1))

            assertFailsWith<IllegalArgumentException> { SnippetLibraryManager.add(tooLongTitle, "b") }
            assertFailsWith<IllegalArgumentException> { SnippetLibraryManager.add("T", tooLongBody) }
            assertFailsWith<IllegalArgumentException> { SnippetLibraryManager.add("T", "b", tooManyTagChars) }
            assertTrue(SnippetLibraryManager.snippets.value.isEmpty(), "a refused add must store nothing")

            val existing = SnippetLibraryManager.add("T", "b")
            assertFailsWith<IllegalArgumentException> { SnippetLibraryManager.update(existing.id, tooLongTitle, "b") }
            assertFailsWith<IllegalArgumentException> { SnippetLibraryManager.update(existing.id, "T", tooLongBody) }
            assertFailsWith<IllegalArgumentException> {
                SnippetLibraryManager.update(existing.id, "T", "b", tooManyTagChars)
            }
            assertEquals("b", SnippetLibraryManager.get(existing.id)?.body, "a refused update must change nothing")
        }

    @Test
    fun `a full store refuses an add but not an update`() =
        runBlocking {
            val full =
                (1..SnippetLibraryManager.MAX_SNIPPETS).map { Snippet(id = "snippet-$it", title = "s$it", body = "b") }
            tempFile.writeText(json.encodeToString(SnippetLibrary.serializer(), SnippetLibrary(full)))
            SnippetLibraryManager.resetForTesting(tempFile)

            assertFailsWith<SnippetLibraryManager.LibraryFullException> { SnippetLibraryManager.add("one more", "b") }
            assertEquals(SnippetLibraryManager.MAX_SNIPPETS, SnippetLibraryManager.snippets.value.size)
            // Ends in an assertion returning Unit: ending in assertNotNull would make this a function
            // returning Snippet, and JUnit silently skips a test method that is not void.
            val renamed = SnippetLibraryManager.update("snippet-1", "renamed", "b")
            assertNotNull(renamed, "an update does not grow the library")
            assertEquals("renamed", SnippetLibraryManager.get("snippet-1")?.title)
        }
}
