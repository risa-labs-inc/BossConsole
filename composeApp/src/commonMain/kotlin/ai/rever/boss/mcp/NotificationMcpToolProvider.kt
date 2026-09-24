package ai.rever.boss.mcp

import ai.rever.boss.notifications.BossNotification
import ai.rever.boss.notifications.NotificationCenter
import ai.rever.boss.notifications.NotificationLevel
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Host MCP tool provider exposing the operator's notification inbox ([NotificationCenter]) to
 * AI agents and automation clients, so an agent can leave the operator a durable note ("the
 * migration finished", "needs your input") rather than only a transient status message.
 *
 * Tools exposed:
 * - notifications_list (read-only)
 * - notification_post (mutating)
 * - notification_mark_read (mutating)
 * - notifications_clear (mutating)
 *
 * As in [WorkspaceMcpToolProvider] and [SnippetMcpToolProvider], the read tool declares
 * `readOnly = true` (left at ALLOW by the mutating gate) and the write tools declare
 * `readOnly = false` (classified mutating and routed through the usual ASK approval).
 */
@Suppress("TooManyFunctions")
object NotificationMcpToolProvider : McpToolProvider {
    override val providerId: String = "boss-notifications"

    override fun tools(): List<McpToolDefinition> =
        listOf(
            createListTool(),
            createPostTool(),
            createMarkReadTool(),
            createClearTool(),
        )

    private fun createListTool(): McpToolDefinition =
        McpToolDefinition(
            name = "notifications_list",
            description = "List inbox notifications (newest first), optionally only unread ones.",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {
                        "unreadOnly": { "type": "boolean", "description": "Only return unread notifications" }
                    }
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleList(args) },
            readOnly = true,
        )

    private fun createPostTool(): McpToolDefinition =
        McpToolDefinition(
            name = "notification_post",
            description = "Post a durable notification to the operator's inbox.",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {
                        "title": { "type": "string", "description": "Short headline" },
                        "message": { "type": "string", "description": "Optional longer body" },
                        "level": { "type": "string", "description": "INFO, SUCCESS, WARNING or ERROR" },
                        "source": { "type": "string", "description": "Optional origin label (e.g. an agent or task name)" }
                    },
                    "required": ["title"]
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handlePost(args) },
            readOnly = false,
        )

    private fun createMarkReadTool(): McpToolDefinition =
        McpToolDefinition(
            name = "notification_mark_read",
            description = "Mark one notification read by id, or all of them when 'all' is true.",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {
                        "id": { "type": "string", "description": "Notification id to mark read" },
                        "all": { "type": "boolean", "description": "Mark every notification read" }
                    }
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleMarkRead(args) },
            readOnly = false,
        )

    private fun createClearTool(): McpToolDefinition =
        McpToolDefinition(
            name = "notifications_clear",
            description = "Remove every notification from the inbox.",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {}
                }
                """.trimIndent(),
            handler = McpToolHandler { handleClear() },
            readOnly = false,
        )

    private fun handleList(args: McpToolArgs): McpToolResult {
        val unreadOnly = args.boolean("unreadOnly") ?: false
        val entries = NotificationCenter.notifications.value.filter { !unreadOnly || !it.read }
        val response =
            buildJsonObject {
                put("success", true)
                put("unreadCount", NotificationCenter.unreadCount())
                put(
                    "notifications",
                    buildJsonArray { entries.forEach { add(entryJson(it)) } },
                )
            }
        return McpToolResult(response.toString())
    }

    @Suppress("ReturnCount")
    private suspend fun handlePost(args: McpToolArgs): McpToolResult {
        val title = args.string("title")
        if (title.isNullOrBlank()) {
            return McpToolResult("title is required", isError = true)
        }
        val posted =
            NotificationCenter.post(
                title = title,
                message = args.string("message").orEmpty(),
                level = NotificationLevel.fromString(args.string("level")),
                source = args.string("source").orEmpty(),
            )
        return McpToolResult(entryJson(posted).toString())
    }

    @Suppress("ReturnCount")
    private suspend fun handleMarkRead(args: McpToolArgs): McpToolResult {
        if (args.boolean("all") == true) {
            val changed = NotificationCenter.markAllRead()
            return McpToolResult(
                buildJsonObject {
                    put("success", true)
                    put("marked", changed)
                }.toString(),
            )
        }
        val id = args.string("id")
        if (id.isNullOrBlank()) {
            return McpToolResult("id is required (or pass all=true)", isError = true)
        }
        return if (NotificationCenter.markRead(id)) {
            McpToolResult(
                buildJsonObject {
                    put("success", true)
                    put("id", id)
                }.toString(),
            )
        } else {
            McpToolResult("Notification '$id' not found or already read", isError = true)
        }
    }

    private suspend fun handleClear(): McpToolResult {
        val removed = NotificationCenter.clear()
        return McpToolResult(
            buildJsonObject {
                put("success", true)
                put("removed", removed)
            }.toString(),
        )
    }

    private fun entryJson(entry: BossNotification) =
        buildJsonObject {
            put("id", entry.id)
            put("title", entry.title)
            put("message", entry.message)
            put("level", entry.level.name)
            put("source", entry.source)
            put("createdAt", entry.createdAt)
            put("read", entry.read)
        }
}
