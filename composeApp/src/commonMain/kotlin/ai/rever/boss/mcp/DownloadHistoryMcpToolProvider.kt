package ai.rever.boss.mcp

import ai.rever.boss.downloads.DownloadHistoryManager
import ai.rever.boss.downloads.DownloadRecord
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Host MCP tool provider exposing the persistent download history
 * ([DownloadHistoryManager]) to AI agents and automation clients, so an agent that triggered a
 * download can confirm what landed and where.
 *
 * `downloads_history_list` declares `readOnly = true`, but the host's sensitive-read catalog
 * routes it through the approval-requiring default because URLs can contain bearer tokens;
 * `downloads_history_clear` declares `readOnly = false` (classified mutating and routed through
 * the usual ASK approval), matching the posture of the other host providers.
 */
object DownloadHistoryMcpToolProvider : McpToolProvider {
    private val logger = BossLogger.forComponent("DownloadHistoryMcpToolProvider")
    override val providerId: String = "boss-downloads"

    private const val DEFAULT_LIST_LIMIT = 50
    private const val MAX_LIST_LIMIT = 100

    override fun tools(): List<McpToolDefinition> =
        listOf(
            createListTool(),
            createClearTool(),
        )

    private fun createListTool(): McpToolDefinition =
        McpToolDefinition(
            name = "downloads_history_list",
            description =
                "List completed downloads newest first, including full URLs and local paths. " +
                    "Returns up to $DEFAULT_LIST_LIMIT entries by default; use limit and offset for more.",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {
                        "limit": { "type": "integer", "minimum": 1, "maximum": $MAX_LIST_LIMIT, "description": "Entries to return" },
                        "offset": { "type": "integer", "minimum": 0, "description": "Entries to skip from the newest" }
                    }
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleList(args) },
            readOnly = true,
        )

    private fun createClearTool(): McpToolDefinition =
        McpToolDefinition(
            name = "downloads_history_clear",
            description = "Clear the persistent download history.",
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

    @Suppress("TooGenericExceptionCaught") // disk reads can fail through several filesystem exception types
    private suspend fun handleList(args: McpToolArgs): McpToolResult {
        val downloads =
            try {
                DownloadHistoryManager.list()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Could not read download history", error = e)
                return McpToolResult("Download history could not be read; clear it before listing", isError = true)
            }
        val limit = (args.int("limit") ?: DEFAULT_LIST_LIMIT).coerceIn(1, MAX_LIST_LIMIT)
        val offset = (args.int("offset") ?: 0).coerceAtLeast(0)
        val page = downloads.drop(offset).take(limit)
        val response =
            buildJsonObject {
                put("success", true)
                put("total", downloads.size)
                put("offset", offset)
                put("returned", page.size)
                put(
                    "downloads",
                    buildJsonArray { page.forEach { add(recordJson(it)) } },
                )
            }
        return McpToolResult(response.toString())
    }

    @Suppress("TooGenericExceptionCaught") // persistence can fail through several filesystem exception types
    private suspend fun handleClear(): McpToolResult {
        val removed =
            try {
                DownloadHistoryManager.clear()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Could not clear download history", error = e)
                return McpToolResult("Could not persist cleared download history", isError = true)
            }
        return McpToolResult(
            buildJsonObject {
                put("success", true)
                put("removed", removed)
            }.toString(),
        )
    }

    private fun recordJson(record: DownloadRecord) =
        buildJsonObject {
            put("id", record.id)
            put("url", record.url)
            put("fileName", record.fileName)
            put("filePath", record.filePath)
            record.sizeBytes?.let { put("sizeBytes", it) }
            put("completedAt", record.completedAt)
        }
}
