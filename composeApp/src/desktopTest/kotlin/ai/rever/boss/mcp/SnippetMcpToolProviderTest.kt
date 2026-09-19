package ai.rever.boss.mcp

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.snippets.SnippetLibraryManager
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
}
