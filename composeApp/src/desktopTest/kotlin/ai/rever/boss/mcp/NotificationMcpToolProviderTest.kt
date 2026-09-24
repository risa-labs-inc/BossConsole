package ai.rever.boss.mcp

import ai.rever.boss.notifications.NotificationCenter
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolResult
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
            val posted = NotificationCenter.post("A")
            NotificationCenter.post("B")
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
            val a = NotificationCenter.post("A")
            NotificationCenter.post("B")

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

    @Test
    fun `clear empties the inbox`() =
        runBlocking {
            NotificationCenter.post("A")
            val result = json(call("notifications_clear", args()))
            assertEquals(1, result["removed"]!!.jsonPrimitive.content.toInt())
            assertTrue(NotificationCenter.notifications.value.isEmpty())
        }
}
