package ai.rever.boss.mcp

import ai.rever.boss.notifications.NotificationCenter
import ai.rever.boss.notifications.NotificationOrigin
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract tests for [NotificationMcpToolProvider], exercising each tool through its registered
 * handler exactly as the MCP registry would. The provider reads and writes the
 * [NotificationCenter] singleton, so each test redirects it to a hermetic temp file.
 */
class NotificationMcpToolProviderTest {
    private lateinit var tempDir: File
    private lateinit var tempFile: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("notif-mcp-test-").toFile()
        tempFile = File(tempDir, "notifications.json")
        NotificationCenter.resetForTesting(tempFile)
    }

    @AfterTest
    fun tearDown() {
        NotificationCenter.resetForTesting()
        tempDir.deleteRecursively()
    }

    private fun args(vararg pairs: Pair<String, Any>) = McpToolArgs(mapOf(*pairs), "{}")

    private suspend fun call(
        name: String,
        args: McpToolArgs,
    ): McpToolResult {
        val tool = NotificationMcpToolProvider.tools().firstOrNull { it.name == name }
        requireNotNull(tool) { "tool $name not found" }
        return tool.handler.call(args)
    }

    private fun json(result: McpToolResult) = Json.parseToJsonElement(result.text).jsonObject

    @Test
    fun `only the list tool is read-only`() {
        val readOnlyByName = NotificationMcpToolProvider.tools().associate { it.name to it.readOnly }
        assertEquals(true, readOnlyByName["notifications_list"])
        assertEquals(false, readOnlyByName["notification_post"])
        assertEquals(false, readOnlyByName["notification_mark_read"])
        assertEquals(false, readOnlyByName["notifications_clear"])
        assertEquals(false, readOnlyByName["notifications_clear_all"])
    }

    @Test
    fun `post appears in the list with its level`() =
        runBlocking {
            call("notification_post", args("title" to "Done", "message" to "built", "level" to "success"))
            val listed = json(call("notifications_list", args()))
            val entries = listed["notifications"]!!.jsonArray
            assertEquals(1, entries.size)
            assertEquals(
                "Done",
                entries
                    .first()
                    .jsonObject["title"]!!
                    .jsonPrimitive.content,
            )
            assertEquals(
                "SUCCESS",
                entries
                    .first()
                    .jsonObject["level"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `post requires a title`() =
        runBlocking {
            assertTrue(call("notification_post", args()).isError)
        }

    @Test
    fun `unreadOnly filters read entries`() =
        runBlocking {
            val posted = NotificationCenter.post("A", origin = NotificationOrigin.HOST)
            NotificationCenter.post("B", origin = NotificationOrigin.HOST)
            NotificationCenter.markRead(posted.id)

            val unread = json(call("notifications_list", args("unreadOnly" to true)))["notifications"]!!.jsonArray
            assertEquals(1, unread.size)
            assertEquals(
                "B",
                unread
                    .first()
                    .jsonObject["title"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `mark_read by id and by all`() =
        runBlocking {
            val a = NotificationCenter.post("A", origin = NotificationOrigin.HOST)
            NotificationCenter.post("B", origin = NotificationOrigin.HOST)

            assertFalse(call("notification_mark_read", args("id" to a.id)).isError)
            assertEquals(1, NotificationCenter.unreadCount())

            call("notification_mark_read", args("all" to true))
            assertEquals(0, NotificationCenter.unreadCount())
        }

    @Test
    fun `mark_read without id or all is an error`() =
        runBlocking {
            assertTrue(call("notification_mark_read", args()).isError)
        }

    // -----------------------------------------------------------------
    // Scoped clearing (#1588). notifications_clear removes only the caller's own
    // agent-posted entries - those whose stored label matches the caller's 'source'
    // after the same normalization a post applies - and can never touch host entries
    // or another label's. notifications_clear_all keeps the old whole-inbox wipe.
    // -----------------------------------------------------------------

    @Test
    fun `clear_all empties the inbox`() =
        runBlocking {
            NotificationCenter.post("A", origin = NotificationOrigin.HOST)
            call("notification_post", args("title" to "agent note"))
            val result = json(call("notifications_clear_all", args()))
            assertEquals(2, result["removed"]!!.jsonPrimitive.content.toInt())
            assertTrue(NotificationCenter.notifications.value.isEmpty())
        }

    @Test
    fun `clear removes only the caller's own agent-labelled entries`() =
        runBlocking {
            call("notification_post", args("title" to "swe note", "source" to "swe"))
            call("notification_post", args("title" to "other note", "source" to "other"))
            NotificationCenter.post("Host notice", source = "System", origin = NotificationOrigin.HOST)

            val result = json(call("notifications_clear", args("source" to "swe")))

            assertEquals(1, result["removed"]!!.jsonPrimitive.content.toInt())
            assertEquals(
                setOf("other note", "Host notice"),
                NotificationCenter.notifications.value
                    .map { it.title }
                    .toSet(),
                "other labels and host entries survive",
            )
        }

    @Test
    fun `clear cannot reach host entries even when the stored label collides`() =
        runBlocking {
            // A host may label its own notice anything - even the agent-prefixed form - and
            // it stores verbatim. The origin check, not the label text, is what keeps it out
            // of an agent-scoped clear.
            NotificationCenter.post("Host notice", source = "agent: swe", origin = NotificationOrigin.HOST)
            call("notification_post", args("title" to "agent note", "source" to "swe"))

            val result = json(call("notifications_clear", args("source" to "swe")))

            assertEquals(1, result["removed"]!!.jsonPrimitive.content.toInt())
            assertEquals(
                "Host notice",
                NotificationCenter.notifications.value
                    .single()
                    .title,
            )
        }

    @Test
    fun `clear normalizes the supplied label the way a post does`() =
        runBlocking {
            call("notification_post", args("title" to "flat", "source" to "Ops\nSystem"))
            val longLabel = "x".repeat(NotificationCenter.MAX_SOURCE_LABEL_CHARS + 10)
            call("notification_post", args("title" to "long", "source" to longLabel))

            val flattened = json(call("notifications_clear", args("source" to "Ops\nSystem")))
            assertEquals(
                1,
                flattened["removed"]!!.jsonPrimitive.content.toInt(),
                "flattening applies on both sides",
            )
            val truncated = json(call("notifications_clear", args("source" to longLabel)))
            assertEquals(
                1,
                truncated["removed"]!!.jsonPrimitive.content.toInt(),
                "the length cap applies on both sides",
            )
            assertTrue(NotificationCenter.notifications.value.isEmpty())
        }

    @Test
    fun `clear without a source removes only bare-agent entries`() =
        runBlocking {
            call("notification_post", args("title" to "unlabelled"))
            call("notification_post", args("title" to "labelled", "source" to "swe"))
            NotificationCenter.post("Host notice", origin = NotificationOrigin.HOST)

            val result = json(call("notifications_clear", args()))

            assertEquals(1, result["removed"]!!.jsonPrimitive.content.toInt())
            assertEquals(
                setOf("labelled", "Host notice"),
                NotificationCenter.notifications.value
                    .map { it.title }
                    .toSet(),
            )
        }

    @Test
    fun `a failed persist leaves the inbox unchanged`() =
        runBlocking {
            NotificationCenter.post("Host notice", origin = NotificationOrigin.HOST)
            call("notification_post", args("title" to "agent note", "source" to "swe"))
            // A storage path whose parent is a regular file cannot be written into, so the
            // persist inside the lock throws before the in-memory inbox is touched.
            val blocker = File(tempDir, "blocker").apply { writeText("x") }
            NotificationCenter.storageFile = File(blocker, "notifications.json")

            assertFailsWith<IOException> { call("notifications_clear", args("source" to "swe")) }
            assertFailsWith<IOException> { call("notifications_clear_all", args()) }

            assertEquals(2, NotificationCenter.notifications.value.size, "memory is not ahead of disk")
        }

    @Test
    fun `clear_all is mutating by name, with its own name-keyed approval rule`() {
        assertTrue(
            McpMutatingToolCatalog.isMutating("notifications_clear_all", declaredReadOnly = true),
            "the name signal is final for the whole-inbox wipe; a dishonest read claim cannot upgrade it",
        )
        val defaultConfig = McpToolPolicyConfig()
        assertEquals(
            defaultConfig.defaultMutatingAction,
            McpMutatingToolCatalog.resolveAction(
                "notifications_clear_all",
                defaultConfig,
                declaredReadOnly = false,
            ),
        )
        // The operator's per-tool rule still overrides the catalog, in either direction.
        val allowed = McpToolPolicyConfig(rules = mapOf("notifications_clear_all" to McpPolicyAction.ALLOW))
        assertEquals(
            McpPolicyAction.ALLOW,
            McpMutatingToolCatalog.resolveAction("notifications_clear_all", allowed, declaredReadOnly = false),
        )
    }

    @Test
    fun `an agent-supplied system source is stamped agent, not echoed`() =
        runBlocking {
            val posted = json(call("notification_post", args("title" to "Task done", "source" to "System")))
            assertEquals(
                "AGENT",
                posted["origin"]!!.jsonPrimitive.content,
                "the post result carries the authoritative origin",
            )
            assertEquals(
                "agent: System",
                posted["source"]!!.jsonPrimitive.content,
                "the agent-supplied label is demoted, never echoed as-is",
            )

            val listed = json(call("notifications_list", args()))["notifications"]!!.jsonArray
            assertEquals(
                "AGENT",
                listed
                    .first()
                    .jsonObject["origin"]!!
                    .jsonPrimitive.content,
            )
            assertEquals(
                "agent: System",
                listed
                    .first()
                    .jsonObject["source"]!!
                    .jsonPrimitive.content,
            )
        }

    // -----------------------------------------------------------------
    // Bounds (#1500 post-merge review, tracked in #1590). The list is read-only and allowed
    // without asking; the post is the inbox's only writer.
    //
    // Numeric arguments are Long, because that is what McpToolRegistryImpl.scalarOf hands a
    // handler for a whole JSON number. An Int here would test a type the registry never produces.
    // -----------------------------------------------------------------

    private suspend fun postNumbered(count: Int) {
        repeat(count) { call("notification_post", args("title" to "n${it + 1}")) }
    }

    private fun titles(result: McpToolResult): List<String> {
        val entries = json(result)["notifications"]!!.jsonArray
        return entries.map { it.jsonObject["title"]!!.jsonPrimitive.content }
    }

    private fun number(
        result: McpToolResult,
        key: String,
    ) = json(result)[key]!!.jsonPrimitive.content.toInt()

    @Test
    fun `the list returns one page by default, newest first, and reports the rest`() =
        runBlocking {
            postNumbered(60)

            val listed = call("notifications_list", args())

            assertEquals(NotificationMcpToolProvider.DEFAULT_LIST_LIMIT, titles(listed).size)
            assertEquals("n60", titles(listed).first(), "newest first")
            assertEquals(60, number(listed, "total"), "the rest is not hidden")
            assertEquals(0, number(listed, "offset"))
            assertEquals(NotificationMcpToolProvider.DEFAULT_LIST_LIMIT, number(listed, "returned"))
        }

    @Test
    fun `offset and limit page through the inbox`() =
        runBlocking {
            postNumbered(25)

            val second = titles(call("notifications_list", args("limit" to 10L, "offset" to 10L)))

            assertEquals((15 downTo 6).map { "n$it" }, second, "the second page of ten, newest first")
        }

    /** The clamp bounds nothing unless the page ceiling sits below what the store can hold. */
    @Test
    fun `the page ceiling stays below the store's size`() {
        assertTrue(
            NotificationMcpToolProvider.MAX_LIST_LIMIT < NotificationCenter.MAX_ENTRIES,
            "MAX_LIST_LIMIT must stay below NotificationCenter.MAX_ENTRIES, or one call can return the whole inbox",
        )
    }

    /**
     * The ceiling is below what the store can hold, so this can fail: with the clamp removed,
     * `limit = 10000` over 150 stored entries returns 150.
     */
    @Test
    fun `a limit above the ceiling is clamped to it, and one below 1 is raised to 1`() =
        runBlocking {
            postNumbered(150)

            val huge = call("notifications_list", args("limit" to 10_000L))
            assertEquals(NotificationMcpToolProvider.MAX_LIST_LIMIT, titles(huge).size)
            assertEquals(150, number(huge, "total"))
            assertEquals(1, titles(call("notifications_list", args("limit" to 0L))).size)
            assertEquals(1, titles(call("notifications_list", args("limit" to -5L))).size)
            assertEquals("n150", titles(call("notifications_list", args("offset" to -5L, "limit" to 1L))).single())
        }

    @Test
    fun `total and paging follow the unread filter`() =
        runBlocking {
            postNumbered(10)
            NotificationCenter.notifications.value
                .take(4)
                .forEach { NotificationCenter.markRead(it.id) }

            val page = call("notifications_list", args("unreadOnly" to true, "limit" to 3L))

            assertEquals(6, number(page, "total"), "total counts what the filter keeps, not the inbox")
            assertEquals(3, titles(page).size)
            assertEquals(listOf("n6", "n5", "n4"), titles(page), "the newest four were read")
        }

    @Test
    fun `an over-long title or message is refused, names its limit, and stores nothing`() =
        runBlocking {
            listOf(
                args("title" to "t".repeat(NotificationCenter.MAX_TITLE_CHARS + 1)),
                args("title" to "ok", "message" to "m".repeat(NotificationCenter.MAX_MESSAGE_CHARS + 1)),
            ).forEach { request ->
                val result = call("notification_post", request)
                assertTrue(result.isError, "an over-long field must be refused")
                assertTrue("the limit is" in result.text, "the refusal names the limit: ${result.text}")
            }

            assertEquals(0, NotificationCenter.notifications.value.size, "a refused post must not be stored")
        }

    @Test
    fun `a title and message exactly at their limits are accepted`() =
        runBlocking {
            val result =
                call(
                    "notification_post",
                    args(
                        "title" to "t".repeat(NotificationCenter.MAX_TITLE_CHARS),
                        "message" to "m".repeat(NotificationCenter.MAX_MESSAGE_CHARS),
                    ),
                )

            assertFalse(result.isError, result.text)
            assertEquals(1, NotificationCenter.notifications.value.size)
        }
}
