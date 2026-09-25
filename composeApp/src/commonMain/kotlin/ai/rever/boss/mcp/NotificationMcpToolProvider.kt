package ai.rever.boss.mcp

import ai.rever.boss.notifications.BossNotification
import ai.rever.boss.notifications.NotificationCenter
import ai.rever.boss.notifications.NotificationCenter.MAX_MESSAGE_CHARS
import ai.rever.boss.notifications.NotificationCenter.MAX_TITLE_CHARS
import ai.rever.boss.notifications.NotificationLevel
import ai.rever.boss.notifications.NotificationOrigin
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

    /** A page an agent can read in one go. */
    internal const val DEFAULT_LIST_LIMIT = 50

    /**
     * The most one list call returns. Deliberately below [NotificationCenter.MAX_ENTRIES]: clamped
     * to the store's own size this would be a no-op, since the store never holds more.
     */
    internal const val MAX_LIST_LIMIT = 100

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
            description =
                "List inbox notifications (newest first), optionally only unread ones. Returns at most 'limit' " +
                    "entries (default $DEFAULT_LIST_LIMIT, at most $MAX_LIST_LIMIT) starting at 'offset'; 'total' " +
                    "is how many match. 'offset' counts from the newest, so it is not a stable cursor: a post " +
                    "between two calls shifts every later page by one.",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {
                        "unreadOnly": { "type": "boolean", "description": "Only return unread notifications" },
                        "limit": { "type": "integer", "minimum": 1, "maximum": $MAX_LIST_LIMIT, "description": "Entries to return" },
                        "offset": { "type": "integer", "minimum": 0, "description": "Entries to skip from the newest" }
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
                        "title": { "type": "string", "maxLength": $MAX_TITLE_CHARS, "description": "Short headline" },
                        "message": { "type": "string", "maxLength": $MAX_MESSAGE_CHARS, "description": "Optional longer body" },
                        "level": { "type": "string", "description": "INFO, SUCCESS, WARNING or ERROR" },
                        "source": { "type": "string", "description": "Optional display label; stamped with agent provenance, max 80 chars" }
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

    /**
     * Paged, because this is read-only and so allowed without asking. What bounds one response:
     * at most [MAX_LIST_LIMIT] entries, each with a title and message capped by [NotificationCenter.post] and a
     * `source` that [NotificationCenter] reduces to an 80-character label for every post this tool
     * makes (#1619). Entries stored before those caps age out under the store's own size.
     *
     * An `offset` too large for an Int reads as absent, so it returns the newest page rather than
     * an empty one; a paging loop should stop on `returned == 0` or on `offset >= total`.
     */
    private fun handleList(args: McpToolArgs): McpToolResult {
        val unreadOnly = args.boolean("unreadOnly") ?: false
        val limit = (args.int("limit") ?: DEFAULT_LIST_LIMIT).coerceIn(1, MAX_LIST_LIMIT)
        val offset = (args.int("offset") ?: 0).coerceAtLeast(0)
        val matching = NotificationCenter.notifications.value.filter { !unreadOnly || !it.read }
        val entries = matching.drop(offset).take(limit)
        val response =
            buildJsonObject {
                put("success", true)
                put("unreadCount", NotificationCenter.unreadCount())
                put("total", matching.size)
                put("offset", offset)
                put("returned", entries.size)
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
        // NotificationCenter.post enforces the same caps for every publisher; checking here first is
        // what lets the refusal name the field and its limit instead of surfacing the store's
        // exception. `source` is not checked here because NotificationCenter already reduces it to
        // a bounded label for every post this tool makes (#1619).
        tooLong(args)?.let { return McpToolResult(it, isError = true) }
        // Everything arriving through this MCP boundary is agent-supplied by construction, so the
        // post is stamped AGENT and the agent's `source` argument is demoted to a display label at
        // the NotificationCenter boundary - it cannot present as a host notice (BossConsole#1587).
        val posted =
            NotificationCenter.post(
                title = title,
                message = args.string("message").orEmpty(),
                level = NotificationLevel.fromString(args.string("level")),
                source = args.string("source").orEmpty(),
                origin = NotificationOrigin.AGENT,
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

    private fun tooLong(args: McpToolArgs): String? =
        listOf(
            "title" to MAX_TITLE_CHARS,
            "message" to MAX_MESSAGE_CHARS,
        ).firstNotNullOfOrNull { (field, max) ->
            val length = args.string(field)?.length ?: 0
            if (length > max) "$field is $length characters; the limit is $max" else null
        }

    private fun entryJson(entry: BossNotification) =
        buildJsonObject {
            put("id", entry.id)
            put("title", entry.title)
            put("message", entry.message)
            put("level", entry.level.name)
            put("source", entry.source)
            // Authoritative provenance, so a host notice and an agent notice stay distinguishable
            // in the list the agent itself reads back (BossConsole#1587).
            put("origin", entry.origin.name)
            put("createdAt", entry.createdAt)
            put("read", entry.read)
        }
}
