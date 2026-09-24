package ai.rever.boss.mcp

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.snippets.Snippet
import ai.rever.boss.snippets.SnippetLibraryManager
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Host MCP tool provider exposing the operator's reusable prompt/command snippets
 * ([SnippetLibraryManager]) to AI agents and automation clients.
 *
 * Tools exposed:
 * - snippets_list / snippet_list (read-only)
 * - snippet_get (read-only)
 * - snippet_save (mutating)
 * - snippet_delete (mutating)
 *
 * The read tools declare `readOnly = true`, so the mutating gate's fail-closed OR leaves them at
 * the default ALLOW; the write tools declare `readOnly = false`, so the same gate classifies them
 * as mutating (neither name is in the host's known-mutating list) and they default to ASK, with
 * the approval dialog the operator's confirmation. This mirrors [WorkspaceMcpToolProvider]: no
 * tool declares `requiredPermissions`, since the registry is a loopback-only server for the local
 * machine's own agents and an undeclared tool is permitted there.
 */
@Suppress("TooManyFunctions")
object SnippetMcpToolProvider : McpToolProvider {
    override val providerId: String = "boss-snippets"

    override fun tools(): List<McpToolDefinition> =
        listOf(
            createListTool("snippets_list"),
            createListTool("snippet_list"),
            createGetTool("snippet_get"),
            createSaveTool("snippet_save"),
            createDeleteTool("snippet_delete"),
        )

    private fun createListTool(name: String): McpToolDefinition =
        McpToolDefinition(
            name = name,
            description = "List stored prompt/command snippets, optionally filtered to a single tag.",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {
                        "tag": { "type": "string", "description": "Optional tag to filter by (case-insensitive)" }
                    }
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleList(args) },
            readOnly = true,
        )

    private fun createGetTool(name: String): McpToolDefinition =
        McpToolDefinition(
            name = name,
            description = "Get one snippet, including its full body, by id.",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {
                        "id": { "type": "string", "description": "Snippet id" }
                    },
                    "required": ["id"]
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleGet(args) },
            readOnly = true,
        )

    private fun createSaveTool(name: String): McpToolDefinition =
        McpToolDefinition(
            name = name,
            description =
                "Create a snippet, or update an existing one when 'id' is supplied. 'tags' is a " +
                    "comma-separated list.",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {
                        "id": { "type": "string", "description": "Existing snippet id to update; omit to create" },
                        "title": { "type": "string", "description": "Short human-readable title" },
                        "body": { "type": "string", "description": "The prompt or command text" },
                        "tags": { "type": "string", "description": "Comma-separated tags" }
                    },
                    "required": ["title", "body"]
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleSave(args) },
            readOnly = false,
        )

    private fun createDeleteTool(name: String): McpToolDefinition =
        McpToolDefinition(
            name = name,
            description = "Delete a snippet by id.",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {
                        "id": { "type": "string", "description": "Snippet id" }
                    },
                    "required": ["id"]
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleDelete(args) },
            readOnly = false,
        )

    private fun handleList(args: McpToolArgs): McpToolResult {
        val tag = args.string("tag")
        val snippets =
            if (tag.isNullOrBlank()) {
                SnippetLibraryManager.snippets.value
            } else {
                SnippetLibraryManager.byTag(tag)
            }
        val response =
            buildJsonObject {
                put("success", true)
                put(
                    "snippets",
                    buildJsonArray {
                        snippets.forEach { add(summaryJson(it)) }
                    },
                )
            }
        return McpToolResult(response.toString())
    }

    @Suppress("ReturnCount")
    private fun handleGet(args: McpToolArgs): McpToolResult {
        val id = args.string("id")
        if (id.isNullOrBlank()) {
            return McpToolResult("id is required", isError = true)
        }
        val snippet =
            SnippetLibraryManager.get(id)
                ?: return McpToolResult("Snippet '$id' not found", isError = true)
        return McpToolResult(fullJson(snippet).toString())
    }

    @Suppress("ReturnCount")
    private suspend fun handleSave(args: McpToolArgs): McpToolResult {
        val title = args.string("title")
        val body = args.string("body")
        if (title.isNullOrBlank()) {
            return McpToolResult("title is required", isError = true)
        }
        if (body == null) {
            return McpToolResult("body is required", isError = true)
        }
        // Absent 'tags' means "keep the existing set" on update; an explicit
        // empty string clears it.
        val tags = args.string("tags")?.let(::parseTags)
        val id = args.string("id")

        val saved =
            if (id.isNullOrBlank()) {
                SnippetLibraryManager.add(title, body, tags.orEmpty())
            } else {
                SnippetLibraryManager.update(id, title, body, tags)
                    ?: return McpToolResult("Snippet '$id' not found; omit 'id' to create a new one", isError = true)
            }
        return McpToolResult(fullJson(saved).toString())
    }

    private suspend fun handleDelete(args: McpToolArgs): McpToolResult {
        val id = args.string("id")
        if (id.isNullOrBlank()) {
            return McpToolResult("id is required", isError = true)
        }
        return if (SnippetLibraryManager.remove(id)) {
            McpToolResult(
                buildJsonObject {
                    put("success", true)
                    put("id", id)
                    put("deleted", true)
                }.toString(),
            )
        } else {
            McpToolResult("Snippet '$id' not found", isError = true)
        }
    }

    /** Comma-separated tags to a trimmed, non-empty list. */
    private fun parseTags(s: String?): List<String> = s.orEmpty().split(',').mapNotNull { it.trim().ifEmpty { null } }

    /** The list view omits [Snippet.body] so a long-body library does not flood the agent's context. */
    private fun summaryJson(snippet: Snippet) =
        buildJsonObject {
            put("id", snippet.id)
            put("title", snippet.title)
            put("tags", snippet.tags.joinToString(","))
            put("createdAt", snippet.createdAt)
            put("updatedAt", snippet.updatedAt)
        }

    private fun fullJson(snippet: Snippet) =
        buildJsonObject {
            put("success", true)
            put("id", snippet.id)
            put("title", snippet.title)
            put("body", snippet.body)
            put("tags", snippet.tags.joinToString(","))
            put("createdAt", snippet.createdAt)
            put("updatedAt", snippet.updatedAt)
        }
}
