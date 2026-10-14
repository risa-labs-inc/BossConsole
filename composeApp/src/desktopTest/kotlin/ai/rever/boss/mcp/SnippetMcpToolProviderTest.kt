package ai.rever.boss.mcp

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.snippets.Snippet
import ai.rever.boss.snippets.SnippetLibrary
import ai.rever.boss.snippets.SnippetLibraryManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for [SnippetMcpToolProvider], exercising each tool through its registered
 * handler exactly as the MCP registry would. The provider reads and writes the
 * [SnippetLibraryManager] singleton, so each test redirects it to a hermetic temp file and
 * restores it afterwards.
 */
class SnippetMcpToolProviderTest {
    private lateinit var tempDir: File
    private lateinit var tempFile: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("snippet-mcp-test-").toFile()
        tempFile = File(tempDir, "snippets.json")
        SnippetLibraryManager.resetForTesting(tempFile)
    }

    @AfterTest
    fun tearDown() {
        SnippetLibraryManager.resetForTesting()
        tempDir.deleteRecursively()
    }

    private fun args(vararg pairs: Pair<String, Any>) = McpToolArgs(mapOf(*pairs), "{}")

    private suspend fun call(
        name: String,
        args: McpToolArgs,
    ): McpToolResult {
        val tool = SnippetMcpToolProvider.tools().firstOrNull { it.name == name }
        requireNotNull(tool) { "tool $name not found" }
        return tool.handler.call(args)
    }

    private fun json(result: McpToolResult) = Json.parseToJsonElement(result.text).jsonObject

    @Test
    fun `read tools are read-only and write tools are mutating`() {
        val readOnlyByName = SnippetMcpToolProvider.tools().associate { it.name to it.readOnly }
        assertEquals(true, readOnlyByName["snippets_list"])
        assertEquals(true, readOnlyByName["snippet_list"])
        assertEquals(true, readOnlyByName["snippet_get"])
        assertEquals(false, readOnlyByName["snippet_save"])
        assertEquals(false, readOnlyByName["snippet_delete"])
    }

    @Test
    fun `save creates a snippet then get returns its body`() =
        runBlocking {
            val saveResult = call("snippet_save", args("title" to "Greeting", "body" to "Hi", "tags" to "demo, chat"))
            val savedId = json(saveResult)["id"]!!.jsonPrimitive.content
            assertEquals("Hi", json(saveResult)["body"]!!.jsonPrimitive.content)

            val getResult = call("snippet_get", args("id" to savedId))
            assertFalse(getResult.isError)
            assertEquals("Greeting", json(getResult)["title"]!!.jsonPrimitive.content)
            assertEquals("Hi", json(getResult)["body"]!!.jsonPrimitive.content)
            assertEquals("demo,chat", json(getResult)["tags"]!!.jsonPrimitive.content)
        }

    @Test
    fun `save with an existing id updates rather than duplicating`() =
        runBlocking {
            val created = SnippetLibraryManager.add("Old", "old body")
            call("snippet_save", args("id" to created.id, "title" to "New", "body" to "new body"))

            assertEquals(1, SnippetLibraryManager.snippets.value.size)
            assertEquals("New", SnippetLibraryManager.get(created.id)?.title)
        }

    @Test
    fun `save with an unknown id is an error and creates nothing`() =
        runBlocking {
            val result = call("snippet_save", args("id" to "snippet-missing", "title" to "T", "body" to "b"))
            assertTrue(result.isError)
            assertTrue(SnippetLibraryManager.snippets.value.isEmpty())
        }

    @Test
    fun `list omits the body and honors the tag filter`() =
        runBlocking {
            SnippetLibraryManager.add("A", "body-a", listOf("kotlin"))
            SnippetLibraryManager.add("B", "body-b", listOf("rust"))

            val all = json(call("snippets_list", args()))["snippets"]!!.jsonArray
            assertEquals(2, all.size)
            assertNull(all.first().jsonObject["body"], "the list view must not include the body")

            val kotlinOnly = json(call("snippets_list", args("tag" to "kotlin")))["snippets"]!!.jsonArray
            assertEquals(1, kotlinOnly.size)
            assertEquals(
                "A",
                kotlinOnly
                    .first()
                    .jsonObject["title"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `delete removes a snippet and reports missing ids as errors`() =
        runBlocking {
            val created = SnippetLibraryManager.add("A", "a")
            val ok = call("snippet_delete", args("id" to created.id))
            assertFalse(ok.isError)
            assertNull(SnippetLibraryManager.get(created.id))

            val missing = call("snippet_delete", args("id" to created.id))
            assertTrue(missing.isError)
        }

    @Test
    fun `get and delete require an id`() =
        runBlocking {
            assertTrue(call("snippet_get", args()).isError)
            assertTrue(call("snippet_delete", args()).isError)
        }

    // -----------------------------------------------------------------
    // Bounds (#1500 post-merge review, tracked in #1590). The list is read-only and allowed
    // without asking; snippet_save is the library's only writer, and it had no size at all.
    // -----------------------------------------------------------------

    /** Creates through the tool, as an agent would, and fails the test at the first refused save. */
    private suspend fun saveNumbered(count: Int) {
        repeat(count) {
            val result = call("snippet_save", args("title" to "s${it + 1}", "body" to "b"))
            assertFalse(result.isError, "seeding save ${it + 1} was refused: ${result.text}")
        }
    }

    /**
     * Writes a library straight to the store's file and reloads it: a full library is 500 entries,
     * and 500 saves through the tool rewrite the whole file 500 times.
     */
    private fun seedLibrary(
        count: Int,
        tagOf: (Int) -> List<String> = { emptyList() },
    ) {
        val snippets = (1..count).map { Snippet(id = "snippet-$it", title = "s$it", body = "b", tags = tagOf(it)) }
        tempFile.writeText(Json.encodeToString(SnippetLibrary.serializer(), SnippetLibrary(snippets)))
        SnippetLibraryManager.resetForTesting(tempFile)
        assertEquals(count, SnippetLibraryManager.snippets.value.size, "the seeded library did not load")
    }

    private fun titles(result: McpToolResult): List<String> {
        // Block body: as an expression this fits ktlint's 140 columns on one line and breaks detekt's 120.
        val snippets = json(result)["snippets"]!!.jsonArray
        return snippets.map { it.jsonObject["title"]!!.jsonPrimitive.content }
    }

    private fun total(result: McpToolResult) = json(result)["total"]!!.jsonPrimitive.content.toInt()

    // The registry hands a handler a Long for every whole JSON number (McpToolRegistryImpl.scalarOf),
    // so limits and offsets below are Long literals: an Int would exercise a path production never takes.

    @Test
    fun `the list returns one page by default and says how many there are`() =
        runBlocking {
            saveNumbered(60)

            val listed = call("snippets_list", args())

            assertEquals(SnippetMcpToolProvider.DEFAULT_LIST_LIMIT, titles(listed).size)
            assertEquals(60, total(listed), "the rest is not hidden")
        }

    @Test
    fun `offset and limit page through the library`() =
        runBlocking {
            saveNumbered(25)

            val second = titles(call("snippets_list", args("limit" to 10L, "offset" to 10L)))

            assertEquals((11..20).map { "s$it" }, second, "the second page of ten, in library order")
        }

    @Test
    fun `an out-of-range limit or offset is clamped rather than obeyed`() =
        runBlocking {
            saveNumbered(3)

            assertEquals(3, titles(call("snippets_list", args("limit" to 10_000L))).size)
            assertEquals(1, titles(call("snippets_list", args("limit" to 0L))).size)
            assertEquals(3, titles(call("snippets_list", args("offset" to -5L))).size)
        }

    /**
     * The ceiling is its own number, below the library's size, so one call cannot take a full
     * library. With the two equal the clamp would bound nothing: this is the test that says so.
     */
    @Test
    fun `a large limit is clamped to one page even when the library holds more`() =
        runBlocking {
            seedLibrary(SnippetLibraryManager.MAX_SNIPPETS)

            val listed = call("snippets_list", args("limit" to SnippetLibraryManager.MAX_SNIPPETS.toLong()))

            assertEquals(SnippetMcpToolProvider.MAX_LIST_LIMIT, titles(listed).size)
            assertEquals(SnippetLibraryManager.MAX_SNIPPETS, total(listed))
        }

    @Test
    fun `the list schema states its bounds in machine-readable form`() {
        val tool = SnippetMcpToolProvider.tools().first { it.name == "snippets_list" }
        val properties = Json.parseToJsonElement(tool.inputSchema).jsonObject["properties"]!!.jsonObject
        val limit = properties["limit"]!!.jsonObject
        val offset = properties["offset"]!!.jsonObject

        assertEquals(1, limit["minimum"]!!.jsonPrimitive.content.toInt())
        assertEquals(SnippetMcpToolProvider.MAX_LIST_LIMIT, limit["maximum"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, offset["minimum"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `an offset past the end returns an empty page and keeps the total`() =
        runBlocking {
            saveNumbered(5)

            val listed = call("snippets_list", args("offset" to 50L))

            assertFalse(listed.isError, listed.text)
            assertTrue(titles(listed).isEmpty())
            assertEquals(5, total(listed), "an empty page still says how many there are")
        }

    @Test
    fun `a tag filter pages over the matching snippets and totals only those`() =
        runBlocking {
            // Every third snippet is tagged: s3, s6, ... s30.
            seedLibrary(30) { if (it % 3 == 0) listOf("kotlin") else emptyList() }

            val page = call("snippets_list", args("tag" to "kotlin", "limit" to 4L, "offset" to 2L))

            assertEquals(10, total(page), "the total is the filtered count, not the library's")
            assertEquals(listOf("s9", "s12", "s15", "s18"), titles(page))
        }

    @Test
    fun `an over-long field is refused, names its limit, and stores nothing`() =
        runBlocking {
            listOf(
                args("title" to "t".repeat(SnippetLibraryManager.MAX_TITLE_CHARS + 1), "body" to "b"),
                args("title" to "t", "body" to "b".repeat(SnippetLibraryManager.MAX_BODY_CHARS + 1)),
                args("title" to "t", "body" to "b", "tags" to "x".repeat(SnippetLibraryManager.MAX_TAGS_CHARS + 1)),
            ).forEach { request ->
                val result = call("snippet_save", request)
                assertTrue(result.isError, "an over-long field must be refused")
                assertTrue("the limit is" in result.text, "the refusal names the limit: ${result.text}")
            }

            assertEquals(0, SnippetLibraryManager.snippets.value.size, "a refused save must not be stored")
        }

    @Test
    fun `fields exactly at their limits are accepted`() =
        runBlocking {
            val result =
                call(
                    "snippet_save",
                    args(
                        "title" to "t".repeat(SnippetLibraryManager.MAX_TITLE_CHARS),
                        "body" to "b".repeat(SnippetLibraryManager.MAX_BODY_CHARS),
                        "tags" to "x".repeat(SnippetLibraryManager.MAX_TAGS_CHARS),
                    ),
                )

            assertFalse(result.isError, result.text)
        }

    /**
     * A full library refuses a NEW snippet but still lets an existing one be edited: the cap bounds
     * how much there is, not whether the library can be maintained.
     */
    @Test
    fun `a full library refuses a new snippet but still accepts an update`() =
        runBlocking {
            seedLibrary(SnippetLibraryManager.MAX_SNIPPETS)

            val create = call("snippet_save", args("title" to "one more", "body" to "b"))
            val update = call("snippet_save", args("id" to "snippet-1", "title" to "renamed", "body" to "b"))

            assertTrue(create.isError, "creating past the cap must be refused")
            assertTrue("full" in create.text, create.text)
            assertEquals(SnippetLibraryManager.MAX_SNIPPETS, SnippetLibraryManager.snippets.value.size)
            assertFalse(update.isError, "an update does not grow the library, so it is allowed: ${update.text}")
            assertEquals("renamed", SnippetLibraryManager.get("snippet-1")?.title)
        }

    /**
     * Two creates racing at one below the cap: exactly one may land. The size is checked inside the
     * store's lock; a check before the call, outside it, lets both through and ends at cap + 1.
     */
    @Test
    fun `two concurrent creates at one below the cap leave the library exactly full`() =
        runBlocking(Dispatchers.Default) {
            repeat(RACE_ROUNDS) { round ->
                seedLibrary(SnippetLibraryManager.MAX_SNIPPETS - 1)
                val start = CompletableDeferred<Unit>()
                val results =
                    (1..2)
                        .map { n ->
                            async {
                                start.await()
                                call("snippet_save", args("title" to "racer $n", "body" to "b"))
                            }
                        }.also { start.complete(Unit) }
                        .awaitAll()

                assertEquals(1, results.count { !it.isError }, "round $round: exactly one create may land")
                val size = SnippetLibraryManager.snippets.value.size
                assertEquals(SnippetLibraryManager.MAX_SNIPPETS, size, "round $round: the library ends exactly full")
            }
        }

    private companion object {
        // Rounds, because one unlucky interleaving is enough to fail and a single round can miss it.
        const val RACE_ROUNDS = 20
    }
}
