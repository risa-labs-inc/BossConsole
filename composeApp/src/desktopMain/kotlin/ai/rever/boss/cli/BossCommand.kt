package ai.rever.boss.cli

import ai.rever.boss.mcp.ToolTelemetryStats
import ai.rever.boss.utils.DeepLinkHandler
import ai.rever.boss.utils.DeepLinkOrigin
import ai.rever.boss.utils.MAX_ARGUMENT_BYTES
import ai.rever.boss.utils.SingleInstanceManager
import com.github.ajalt.clikt.completion.CompletionCandidates
import com.github.ajalt.clikt.completion.CompletionGenerator
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Main BOSS CLI command.
 *
 * Usage:
 *   boss <url>                      # Opens URL in browser
 *   boss workspace <config>         # Loads workspace
 *   boss file <path>                # Opens file in editor
 *   boss folder <path>              # Opens folder in codebase
 *   boss terminal                   # Opens terminal
 *   boss terminal -c <command>      # Opens terminal with command
 *
 * These commands are only ever built from this process's own `argv` — `main()`
 * hands the arguments the operator passed to the BOSS executable to
 * [createBossCLI] — so every link they produce carries
 * [DeepLinkOrigin.OPERATOR_CLI]. That is the one origin the terminal handler
 * runs a command for without prompting; see [DeepLinkOrigin].
 */
class BossCommand : NoOpCliktCommand(name = "boss") {
    override fun help(context: Context) = "BOSS Console - Business Operating System + Simulation"
}

/**
 * Opens URL in Fluck browser tab.
 * Usage: boss url https://example.com
 */
class BossUrlCommand : CliktCommand(name = "url") {
    override fun help(context: Context) = "Opens a URL in Fluck browser"

    val url by argument(help = "URL to open")

    override fun run() {
        // Convert to deep link
        val encodedUrl = URLEncoder.encode(url, "UTF-8")
        val deepLink = "boss://url?url=$encodedUrl"
        DeepLinkHandler.processDeepLink(deepLink, DeepLinkOrigin.OPERATOR_CLI)
    }
}

/**
 * Loads workspace configuration or switches workspace.
 * Usage:
 *   boss workspace <config.json>
 *   boss workspace switch <name>
 */
class BossWorkspaceCommand : CliktCommand(name = "workspace") {
    override fun help(context: Context) = "Loads or manages workspaces"

    val configPath by argument(help = "Path to workspace config file").optional()

    override fun run() {
        if (currentContext.invokedSubcommand != null) return
        val path = configPath
        if (path != null) {
            // Convert to deep link
            val encodedPath = URLEncoder.encode(path, "UTF-8")
            val deepLink = "boss://workspace?path=$encodedPath"
            DeepLinkHandler.processDeepLink(deepLink, DeepLinkOrigin.OPERATOR_CLI)
        } else {
            echo(getFormattedHelp())
        }
    }
}

/**
 * Switches the active workspace tab by name or ID.
 * Usage: boss workspace switch <name>
 */
class BossWorkspaceSwitchCommand : CliktCommand(name = "switch") {
    override fun help(context: Context) = "Switches active workspace to <name>"

    val name by argument(help = "Name or ID of workspace to switch to")

    override fun run() {
        val result = SingleInstanceManager.switchWorkspace(name)
        result.fold(
            onSuccess = { msg ->
                echo(msg)
            },
            onFailure = { error ->
                echo("Error: ${error.message}", err = true)
                throw ProgramResult(1)
            },
        )
    }
}

/**
 * Opens file in editor tab.
 * Usage: boss file /path/to/file.kt
 */
class BossFileCommand : CliktCommand(name = "file") {
    override fun help(context: Context) = "Opens a file in the editor"

    val filePath by argument(help = "Path to file")

    override fun run() {
        // Convert to deep link
        val encodedPath = URLEncoder.encode(filePath, "UTF-8")
        val deepLink = "boss://file?path=$encodedPath"
        DeepLinkHandler.processDeepLink(deepLink, DeepLinkOrigin.OPERATOR_CLI)
    }
}

/**
 * Opens folder in codebase plugin.
 * Usage: boss folder /path/to/project
 */
class BossFolderCommand : CliktCommand(name = "folder") {
    override fun help(context: Context) = "Opens a folder in the codebase plugin"

    val folderPath by argument(help = "Path to folder")

    override fun run() {
        // Convert to deep link
        val encodedPath = URLEncoder.encode(folderPath, "UTF-8")
        val deepLink = "boss://folder?path=$encodedPath"
        DeepLinkHandler.processDeepLink(deepLink, DeepLinkOrigin.OPERATOR_CLI)
    }
}

/**
 * Opens terminal tab, optionally with command.
 * Usage:
 *   boss terminal
 *   boss terminal -c "ls -la"
 */
class BossTerminalCommand : CliktCommand(name = "terminal") {
    override fun help(context: Context) = "Opens a terminal tab"

    val command by option("-c", "--command", help = "Command to run in terminal")

    override fun run() {
        // Convert to deep link
        val deepLink =
            if (command != null) {
                val encodedCommand = URLEncoder.encode(command, "UTF-8")
                "boss://terminal?command=$encodedCommand"
            } else {
                "boss://terminal"
            }
        DeepLinkHandler.processDeepLink(deepLink, DeepLinkOrigin.OPERATOR_CLI)
    }
}

/**
 * Queries running BOSS Console status (memory, active project, version, tool telemetry).
 * Usage:
 *   boss status
 *   boss status --json
 *   boss status --tools
 *   boss status --tools --json
 */
class BossStatusCommand : CliktCommand(name = "status") {
    override fun help(context: Context) = "Queries status and health of the running BOSS Console instance"

    val json by option("--json", help = "Output status as JSON").flag(default = false)
    val tools by option("--tools", help = "Output MCP tool execution telemetry and performance statistics")
        .flag(default = false)

    override fun run() {
        val result = SingleInstanceManager.queryStatus()
        result.fold(
            onSuccess = { rawJson ->
                if (tools) {
                    if (json) {
                        echo(formatToolsJson(rawJson))
                    } else {
                        echo(formatToolsTable(rawJson))
                    }
                } else if (json) {
                    echo(rawJson)
                } else {
                    echo(formatHumanStatus(rawJson))
                }
            },
            onFailure = { error ->
                echo("Error: ${error.message}", err = true)
                throw ProgramResult(1)
            },
        )
    }

    private fun formatToolsJson(rawJson: String): String =
        try {
            val element = Json.parseToJsonElement(rawJson).jsonObject
            val toolsElement = element["tools"] ?: buildJsonObject { put("tools", buildJsonArray {}) }
            toolsElement.toString()
        } catch (_: Exception) {
            rawJson
        }

    private fun formatToolsTable(rawJson: String): String =
        buildString {
            appendLine("MCP Tool Execution Statistics")
            appendLine("-----------------------------")
            val rows =
                try {
                    parseToolStatusRows(rawJson)
                } catch (_: Exception) {
                    appendLine("Error parsing MCP tool telemetry from status response.")
                    return@buildString
                }

            if (rows.isEmpty()) {
                appendLine("No MCP tool invocations recorded yet.")
                return@buildString
            }

            renderToolRows(this, rows)
        }

    private fun parseToolStatusRows(rawJson: String): List<ToolStatusRow> {
        val element = Json.parseToJsonElement(rawJson).jsonObject
        val toolsArray = extractToolsArray(element)
        return try {
            val typedStats = Json.decodeFromJsonElement<List<ToolTelemetryStats>>(toolsArray)
            typedStats.map { stat ->
                ToolStatusRow(
                    tool = stat.tool,
                    calls = stat.totalInvocations.toString(),
                    errors = stat.totalErrors.toString(),
                    errorPct = "${stat.errorPercentage}%",
                    p50 = stat.p50Ms.toString(),
                    p99 = stat.p99Ms.toString(),
                )
            }
        } catch (_: Exception) {
            toolsArray.mapNotNull { item ->
                (item as? JsonObject)?.let { extractToolRow(it) }
            }
        }
    }

    private fun extractToolsArray(element: JsonObject): JsonArray =
        when (val t = element["tools"]) {
            is JsonArray -> t
            is JsonObject -> t["tools"]?.jsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }

    private fun extractToolRow(obj: JsonObject): ToolStatusRow? {
        val name =
            obj["tool"]?.jsonPrimitive?.contentOrNull
                ?: obj["toolName"]?.jsonPrimitive?.contentOrNull
                ?: return null
        val calls =
            obj["totalInvocations"]?.jsonPrimitive?.contentOrNull
                ?: obj["invocations"]?.jsonPrimitive?.contentOrNull ?: "0"
        val errors =
            obj["totalErrors"]?.jsonPrimitive?.contentOrNull
                ?: obj["errors"]?.jsonPrimitive?.contentOrNull ?: "0"
        val errPct = obj["errorPercentage"]?.jsonPrimitive?.contentOrNull ?: "0.0"
        val p50 =
            obj["p50Ms"]?.jsonPrimitive?.contentOrNull
                ?: obj["p50"]?.jsonPrimitive?.contentOrNull ?: "0"
        val p99 =
            obj["p99Ms"]?.jsonPrimitive?.contentOrNull
                ?: obj["p99"]?.jsonPrimitive?.contentOrNull ?: "0"
        return ToolStatusRow(
            tool = name,
            calls = calls,
            errors = errors,
            errorPct = "$errPct%",
            p50 = p50,
            p99 = p99,
        )
    }

    private fun renderToolRows(
        builder: StringBuilder,
        rows: List<ToolStatusRow>,
    ) {
        val toolColWidth = rows.maxOf { it.tool.length }.coerceAtLeast(4).coerceAtMost(50)
        val callsColWidth = rows.maxOf { it.calls.length }.coerceAtLeast(5)
        val errorsColWidth = rows.maxOf { it.errors.length }.coerceAtLeast(6)
        val pctColWidth = rows.maxOf { it.errorPct.length }.coerceAtLeast(7)
        val p50ColWidth = rows.maxOf { it.p50.length }.coerceAtLeast(8)
        val p99ColWidth = rows.maxOf { it.p99.length }.coerceAtLeast(8)

        val header =
            "TOOL".padEnd(toolColWidth) + "  " +
                "CALLS".padStart(callsColWidth) + "  " +
                "ERRORS".padStart(errorsColWidth) + "  " +
                "ERROR %".padStart(pctColWidth) + "  " +
                "P50 (ms)".padStart(p50ColWidth) + "  " +
                "P99 (ms)".padStart(p99ColWidth)
        builder.appendLine(header)
        builder.appendLine("-".repeat(header.length))

        for (row in rows) {
            builder.appendLine(
                row.tool.padEnd(toolColWidth) + "  " +
                    row.calls.padStart(callsColWidth) + "  " +
                    row.errors.padStart(errorsColWidth) + "  " +
                    row.errorPct.padStart(pctColWidth) + "  " +
                    row.p50.padStart(p50ColWidth) + "  " +
                    row.p99.padStart(p99ColWidth),
            )
        }
    }

    private data class ToolStatusRow(
        val tool: String,
        val calls: String,
        val errors: String,
        val errorPct: String,
        val p50: String,
        val p99: String,
    )

    private fun formatHumanStatus(rawJson: String): String =
        buildString {
            appendLine("BOSS Console Status")
            appendLine("-------------------")
            try {
                val element = Json.parseToJsonElement(rawJson).jsonObject
                appendLine("  Running:        ${element["running"]?.jsonPrimitive?.contentOrNull ?: "true"}")
                appendLine("  Version:        ${element["version"]?.jsonPrimitive?.contentOrNull ?: "unknown"}")
                val osName = element["os"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                val osArch = element["arch"]?.jsonPrimitive?.contentOrNull ?: ""
                appendLine("  OS:             $osName ($osArch)")
                val project = element["activeProject"]?.jsonPrimitive?.contentOrNull
                if (!project.isNullOrBlank()) {
                    appendLine("  Active Project: $project")
                }
                val mem = element["memory"]?.jsonObject
                if (mem != null) {
                    val used = mem["usedMb"]?.jsonPrimitive?.contentOrNull ?: "?"
                    val max = mem["maxMb"]?.jsonPrimitive?.contentOrNull ?: "?"
                    val heapPct = mem["heapPercent"]?.jsonPrimitive?.contentOrNull ?: "?"
                    appendLine("  JVM Memory:     ${used}MB / ${max}MB ($heapPct%)")
                }
                healthSummaryOf(element)?.let { appendLine("  Health:         $it") }
            } catch (_: Exception) {
                appendLine(rawJson)
            }
        }
}

/**
 * Discovers and invokes MCP tools in the running BOSS Console harness.
 * Usage:
 *   boss mcp list [--json]
 *   boss mcp invoke <tool_name> [-a|--args <json>] [--stdin]
 *   boss mcp ledger <verify|tail|search> [--json]
 */
@Suppress("TooManyFunctions")
class BossMcpCommand : CliktCommand(name = "mcp") {
    override fun help(context: Context) = "Discovers and invokes MCP tools in the running BOSS Console"

    val action by argument(
        help = "Action to perform: list, describe (or info), invoke (or call), ledger",
        completionCandidates =
            CompletionCandidates.Fixed("list", "describe", "info", "invoke", "call", "ledger"),
    ).optional()
    val tool by argument(
        help = "Tool name to describe or invoke, or the ledger action: verify, tail, search",
    ).optional()
    val args by option("-a", "--args", help = "JSON arguments string for the tool").default("{}")
    val stdin by option("--stdin", help = "Read JSON arguments from standard input").flag(default = false)
    val timeout by option(
        "-t",
        "--timeout",
        help = "Client wait in seconds (1-60; server execution limit: 30 seconds)",
    ).default("35")
    val filter by option("-f", "--filter", help = "Filter tools by substring in name, description, or plugin ID")
    val raw by option(
        "-r",
        "--raw",
        help = "Emit only the raw unescaped content (ideal for shell scripts and piping)",
    ).flag(default = false)
    val json by option("--json", help = "Output response in raw JSON format").flag(default = false)

    // `boss mcp ledger` reads the durable ledger off disk instead of over IPC, so it takes a file
    // path and its own filters rather than any of the arguments above.
    val ledgerFile by option("--file", help = "Ledger file to read (default: ~/.boss/mcp-calls.jsonl)")
    val ledgerLines by option("-n", "--lines", help = "Records to print for 'tail' (default: 20)").int().default(20)
    val ledgerLimit by option("--limit", help = "Records to print for 'search' (default: 50)").int().default(50)
    val ledgerTool by option("--tool", help = "Only records for this exact tool name")
    val ledgerDisposition by option(
        "--disposition",
        help = "Only unsuccessful calls in this category: denied, cancelled, withheld, failed",
    )
    val ledgerFrom by option("--from", help = "Only records at or after this time (epoch ms, date, or ISO-8601)")
    val ledgerTo by option("--to", help = "Only records at or before this time (epoch ms, date, or ISO-8601)")

    override fun run() {
        if (currentContext.invokedSubcommand != null) return
        when (val act = action?.lowercase()) {
            null, "list" -> {
                handleList()
            }

            "describe", "info" -> {
                handleDescribe()
            }

            "invoke", "call" -> {
                handleInvoke()
            }

            "ledger" -> {
                handleLedger()
            }

            else -> {
                echo("Unknown mcp action: '$act'. Supported actions: list, describe, invoke, ledger", err = true)
                throw ProgramResult(1)
            }
        }
    }

    /**
     * `boss mcp ledger <verify|tail|search>` - reads `~/.boss/mcp-calls.jsonl` and its rotated
     * backups off disk.
     *
     * Local rather than IPC on purpose. The running app can only answer from its in-memory ring
     * buffer, which holds the last 100 calls and is not the audit trail; these read the file
     * itself, so they also work with BOSS closed.
     */
    private fun handleLedger() {
        val ledgerAction = tool?.trim()?.lowercase()
        if (ledgerAction.isNullOrEmpty()) {
            fail("Missing ledger action. Usage: boss mcp ledger <verify|tail|search> [--json]")
        }
        val outcome =
            when (ledgerAction) {
                "verify" -> {
                    McpLedgerCli.verify(ledgerFile, json)
                }

                "tail" -> {
                    McpLedgerCli.tail(ledgerFile, ledgerLines, ledgerQuery(), json)
                }

                "search" -> {
                    McpLedgerCli.search(ledgerFile, ledgerLimit, ledgerQuery(), json)
                }

                else -> {
                    fail(
                        "Unknown ledger action: '$ledgerAction'. Supported actions: verify, tail, search",
                    )
                }
            }
        when (outcome) {
            is McpLedgerOutcome.Ok -> echo(outcome.text)
            is McpLedgerOutcome.Failed -> fail(outcome.message)
        }
    }

    /** The filters `tail` and `search` share, failing on a value that cannot be read. */
    private fun ledgerQuery(): McpLedgerQuery {
        val category =
            if (ledgerDisposition.isNullOrBlank()) {
                null
            } else {
                McpLedgerCli.parseCategory(ledgerDisposition)
                    ?: fail(
                        "Unknown --disposition '$ledgerDisposition'. Expected one of: " +
                            McpLedgerCli.categoryLabels(),
                    )
            }
        return McpLedgerQuery(
            tool = ledgerTool?.trim()?.takeIf { it.isNotEmpty() },
            category = category,
            fromMillis = ledgerTime(ledgerFrom, endOfDay = false),
            toMillis = ledgerTime(ledgerTo, endOfDay = true),
        )
    }

    private fun ledgerTime(
        raw: String?,
        endOfDay: Boolean,
    ): Long? {
        if (raw.isNullOrBlank()) return null
        return McpLedgerCli.parseTime(raw, endOfDay)
            ?: fail("Cannot read time '$raw'. Use epoch milliseconds, YYYY-MM-DD, or ISO-8601.")
    }

    private fun handleList() {
        val result = SingleInstanceManager.queryMcpList()
        result.fold(
            onSuccess = { rawJson ->
                val filterQuery = filter?.trim()
                if (json) {
                    if (filterQuery.isNullOrEmpty()) {
                        echo(rawJson)
                    } else {
                        try {
                            val array = Json.parseToJsonElement(rawJson).jsonArray
                            val filtered =
                                array.filter { item ->
                                    val obj = item.jsonObject
                                    val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    val desc = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    val pluginId = obj["pluginId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    name.contains(filterQuery, ignoreCase = true) ||
                                        desc.contains(filterQuery, ignoreCase = true) ||
                                        pluginId.contains(filterQuery, ignoreCase = true)
                                }
                            echo(JsonArray(filtered).toString())
                        } catch (_: Exception) {
                            echo(rawJson)
                        }
                    }
                } else {
                    echo(formatHumanToolsList(rawJson, filterQuery))
                }
            },
            onFailure = { error ->
                fail("Error: ${error.message}")
            },
        )
    }

    private fun fail(message: String): Nothing {
        echo(message, err = true)
        throw ProgramResult(1)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun handleDescribe() {
        val toolName = tool
        if (toolName.isNullOrBlank()) {
            fail("Missing tool name. Usage: boss mcp describe <tool_name> [--json]")
        }

        val result = SingleInstanceManager.queryMcpList()
        result.fold(
            onSuccess = { rawJson ->
                try {
                    val array = Json.parseToJsonElement(rawJson).jsonArray
                    val match =
                        array.firstOrNull { item ->
                            val name =
                                item.jsonObject["name"]
                                    ?.jsonPrimitive
                                    ?.contentOrNull
                                    .orEmpty()
                            name == toolName
                        }
                    if (match == null) {
                        fail(
                            "MCP tool '$toolName' not found or disabled in BOSS Console. " +
                                "Run 'boss mcp list' to view available tools.",
                        )
                    }
                    if (json) {
                        echo(match.toString())
                    } else {
                        echo(formatHumanToolDetail(match.jsonObject))
                    }
                } catch (e: ProgramResult) {
                    throw e
                } catch (e: Exception) {
                    fail("Error: Describing MCP tool '$toolName' failed: ${e.message}")
                }
            },
            onFailure = { error ->
                fail("Error: ${error.message}")
            },
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun resolveArgumentsJson(): String {
        if (stdin || args == "-") {
            return try {
                readBoundedStdin(MAX_ARGUMENT_BYTES)
            } catch (e: ProgramResult) {
                throw e
            } catch (e: Exception) {
                fail("Error: Failed to read arguments from stdin: ${e.message}")
            }
        }
        return args
    }

    @Suppress("TooGenericExceptionCaught")
    private fun validateArgumentsJson(argumentsJson: String) {
        try {
            val parsed = Json.parseToJsonElement(argumentsJson)
            if (parsed !is JsonObject) {
                fail("Error: Tool arguments must be a JSON object (e.g. '{\"key\":\"value\"}').")
            }
        } catch (e: ProgramResult) {
            throw e
        } catch (e: Exception) {
            fail("Error: Malformed JSON arguments: ${e.message ?: "Invalid JSON syntax"}")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun handleInvokeResponse(responseJson: String) {
        try {
            val element = Json.parseToJsonElement(responseJson).jsonObject
            val isError = element["isError"]?.jsonPrimitive?.booleanOrNull ?: false
            val content = element["content"]?.jsonPrimitive?.contentOrNull ?: responseJson

            if (isError) {
                // Error output strictly written to stderr
                if (json) {
                    echo(responseJson, err = true)
                } else {
                    echo(content, err = true)
                }
                throw ProgramResult(1)
            } else {
                // Valid output strictly written to stdout
                if (json && !raw) {
                    echo(responseJson)
                } else {
                    echo(content)
                }
            }
        } catch (e: ProgramResult) {
            throw e
        } catch (_: Exception) {
            fail("Error: Malformed MCP invocation response from BOSS.")
        }
    }

    private fun handleInvoke() {
        val toolName = tool
        if (toolName.isNullOrBlank()) {
            fail(
                "Missing tool name. Usage: boss mcp invoke <tool_name> " +
                    "[--args '<json>' | --stdin] [-r|--raw] [--json]",
            )
        }

        val argumentsJson = resolveArgumentsJson()
        validateArgumentsJson(argumentsJson)

        val timeoutSeconds = timeout.toLongOrNull()
        if (timeoutSeconds == null || timeoutSeconds !in 1L..60L) {
            fail("Error: Timeout must be an integer between 1 and 60 seconds.")
        }
        val timeoutMs = timeoutSeconds * 1000L
        val result = SingleInstanceManager.invokeMcpTool(toolName, argumentsJson, timeoutMs = timeoutMs)
        result.fold(
            onSuccess = { responseJson ->
                handleInvokeResponse(responseJson)
            },
            onFailure = { error ->
                fail("Error: ${error.message}")
            },
        )
    }

    private fun readBoundedStdin(maxBytes: Int): String {
        val stream = System.`in`
        return ByteArrayOutputStream().use { buffer ->
            val chunk = ByteArray(4096)
            var totalRead = 0
            while (true) {
                val toRead = minOf(chunk.size, maxBytes - totalRead + 1)
                val read = stream.read(chunk, 0, toRead)
                if (read == -1) break
                buffer.write(chunk, 0, read)
                totalRead += read
                if (totalRead > maxBytes) {
                    echo("Error: Standard input arguments exceeded maximum size of ${maxBytes / 1024} KB", err = true)
                    throw ProgramResult(1)
                }
            }
            val content = buffer.toString(StandardCharsets.UTF_8).trim()
            content.ifEmpty { "{}" }
        }
    }

    private fun formatHumanToolsList(
        rawJson: String,
        filterQuery: String?,
    ): String =
        buildString {
            if (filterQuery.isNullOrBlank()) {
                appendLine("Available MCP Tools in BOSS Console")
                appendLine("===================================")
            } else {
                appendLine("Available MCP Tools in BOSS Console (filtered by: '$filterQuery')")
                appendLine("================================================================")
            }
            try {
                val array = Json.parseToJsonElement(rawJson).jsonArray
                val filtered =
                    if (filterQuery.isNullOrBlank()) {
                        array
                    } else {
                        array.filter { item ->
                            val obj = item.jsonObject
                            val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val desc = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val pluginId = obj["pluginId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            name.contains(filterQuery, ignoreCase = true) ||
                                desc.contains(filterQuery, ignoreCase = true) ||
                                pluginId.contains(filterQuery, ignoreCase = true)
                        }
                    }

                if (filtered.isEmpty()) {
                    if (filterQuery.isNullOrBlank()) {
                        appendLine("No MCP tools currently registered.")
                    } else {
                        appendLine("No MCP tools matching '$filterQuery'.")
                    }
                } else {
                    for (item in filtered) {
                        val obj = item.jsonObject
                        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                        val desc = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
                        val pluginId = obj["pluginId"]?.jsonPrimitive?.contentOrNull ?: ""
                        appendLine("• $name ($pluginId)")
                        if (desc.isNotBlank()) {
                            appendLine("    $desc")
                        }
                    }
                    appendLine()
                    if (filterQuery.isNullOrBlank()) {
                        appendLine("Total tools: ${array.size}")
                    } else {
                        appendLine("Matching tools: ${filtered.size} (of ${array.size} total)")
                    }
                }
            } catch (_: Exception) {
                appendLine(rawJson)
            }
        }

    private fun formatHumanToolDetail(obj: JsonObject): String =
        buildString {
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            val pluginId = obj["pluginId"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            val desc = obj["description"]?.jsonPrimitive?.contentOrNull ?: "No description provided."
            val requiresAdmin = obj["requiresAdmin"]?.jsonPrimitive?.booleanOrNull ?: false
            val perms =
                obj["requiredPermissions"]
                    ?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?: emptyList()

            appendLine("MCP Tool: $name")
            appendLine("Plugin:   $pluginId")
            if (requiresAdmin) {
                appendLine("Access:   Requires Administrator")
            } else if (perms.isNotEmpty()) {
                appendLine("Access:   Permissions: [${perms.joinToString(", ")}]")
            } else {
                appendLine("Access:   Standard")
            }
            appendLine()
            appendLine("Description:")
            appendLine(desc.prependIndent("  "))
            obj["inputSchema"]?.let {
                appendLine()
                appendLine("Input schema:")
                appendLine(it.toString().prependIndent("  "))
            }
        }
}

/**
 * Invokes an MCP tool in the running BOSS Console harness.
 * Usage: boss mcp call <tool> [args]
 */
class BossMcpCallCommand : CliktCommand(name = "call") {
    override fun help(context: Context) = "Invokes an MCP tool in the running BOSS Console"

    val tool by argument(help = "Tool name to invoke")
    val positionalArgs by argument(name = "args", help = "JSON arguments string for the tool").optional()
    val args by option("-a", "--args", help = "JSON arguments string for the tool")
    val stdin by option("--stdin", help = "Read JSON arguments from standard input").flag(default = false)
    val timeout by option(
        "-t",
        "--timeout",
        help = "Client wait in seconds (1-60; server execution limit: 30 seconds)",
    ).default("35")
    val raw by option(
        "-r",
        "--raw",
        help = "Emit only the raw unescaped content (ideal for shell scripts and piping)",
    ).flag(default = false)
    val json by option("--json", help = "Output response in raw JSON format").flag(default = false)

    override fun run() {
        val argumentsJson =
            when {
                stdin -> readStdinArgs()
                positionalArgs != null -> positionalArgs!!
                args != null -> args!!
                else -> "{}"
            }
        validateArgumentsJson(argumentsJson)

        val timeoutSeconds = timeout.toLongOrNull()
        if (timeoutSeconds == null || timeoutSeconds !in 1L..60L) {
            echo("Error: Timeout must be an integer between 1 and 60 seconds.", err = true)
            throw ProgramResult(1)
        }
        val timeoutMs = timeoutSeconds * 1000L
        val result = SingleInstanceManager.invokeMcpTool(tool, argumentsJson, timeoutMs = timeoutMs)
        result.fold(
            onSuccess = { responseJson ->
                handleInvokeResponse(responseJson)
            },
            onFailure = { error ->
                echo("Error: ${error.message}", err = true)
                throw ProgramResult(1)
            },
        )
    }

    private fun readStdinArgs(): String {
        val stream = System.`in`
        return ByteArrayOutputStream().use { buffer ->
            val chunk = ByteArray(4096)
            var totalRead = 0
            while (true) {
                val toRead = minOf(chunk.size, MAX_ARGUMENT_BYTES - totalRead + 1)
                val read = stream.read(chunk, 0, toRead)
                if (read == -1) break
                buffer.write(chunk, 0, read)
                totalRead += read
                if (totalRead > MAX_ARGUMENT_BYTES) {
                    val limitKb = MAX_ARGUMENT_BYTES / 1024
                    echo("Error: Standard input arguments exceeded maximum size of $limitKb KB", err = true)
                    throw ProgramResult(1)
                }
            }
            val content = buffer.toString(StandardCharsets.UTF_8).trim()
            content.ifEmpty { "{}" }
        }
    }

    private fun validateArgumentsJson(argumentsJson: String) {
        val errorMessage =
            try {
                val parsed = Json.parseToJsonElement(argumentsJson)

                if (parsed !is JsonObject) {
                    "Error: Tool arguments must be a JSON object (e.g. '{\"key\":\"value\"}')."
                } else {
                    null
                }
            } catch (e: kotlinx.serialization.SerializationException) {
                "Error: Malformed JSON arguments: ${e.message ?: "Invalid JSON syntax"}"
            } catch (e: IllegalArgumentException) {
                "Error: Malformed JSON arguments: ${e.message ?: "Invalid JSON syntax"}"
            }

        if (errorMessage != null) {
            echo(errorMessage, err = true)
            throw ProgramResult(1)
        }
    }

    private fun handleInvokeResponse(responseJson: String) {
        try {
            val element = Json.parseToJsonElement(responseJson).jsonObject
            val isError = element["isError"]?.jsonPrimitive?.booleanOrNull ?: false
            val content = element["content"]?.jsonPrimitive?.contentOrNull ?: responseJson

            if (isError) {
                if (json) {
                    echo(responseJson, err = true)
                } else {
                    echo(content, err = true)
                }
                throw ProgramResult(1)
            } else {
                if (json && !raw) {
                    echo(responseJson)
                } else {
                    echo(content)
                }
            }
        } catch (e: ProgramResult) {
            throw e
        } catch (_: Exception) {
            echo("Error: Malformed MCP invocation response from BOSS.", err = true)
            throw ProgramResult(1)
        }
    }
}

/**
 * Generates shell tab-completion scripts (bash, zsh, fish).
 * Usage: boss completion <bash|zsh|fish>
 */
class BossCompletionCommand : CliktCommand(name = "completion") {
    override fun help(context: Context) = "Generates shell tab-completion scripts (bash, zsh, fish)"

    val shell by argument(
        help = "Shell type (bash, zsh, fish)",
        completionCandidates = CompletionCandidates.Fixed("bash", "zsh", "fish"),
    )

    override fun run() {
        val targetShell = shell.lowercase().trim()
        if (targetShell !in setOf("bash", "zsh", "fish")) {
            echo("Error: Unsupported shell '$targetShell'. Supported shells: bash, zsh, fish", err = true)
            throw ProgramResult(1)
        }
        val root = currentContext.findRoot().command
        val script = CompletionGenerator.generateCompletionForCommand(root, targetShell)
        echo(script)
    }
}

/**
 * Plugin developer tools and lifecycle management.
 * Subcommands (init, validate, link) provide scaffolding, validation, and hot-linking.
 */
class BossPluginCommand : CliktCommand(name = "plugin") {
    override fun help(context: Context) = "Plugin developer tools and lifecycle management"

    override fun run() = Unit
}

/**
 * Configures Clikt command structure.
 */
fun createBossCLI(): BossCommand =
    BossCommand().subcommands(
        BossUrlCommand(),
        BossWorkspaceCommand().subcommands(
            BossWorkspaceSwitchCommand(),
        ),
        BossFileCommand(),
        BossFolderCommand(),
        BossTerminalCommand(),
        BossStatusCommand(),
        BossDoctorCommand(),
        BossMcpCommand().subcommands(
            BossMcpCallCommand(),
        ),
        BossCompletionCommand(),
        BossPluginCommand().subcommands(
            BossPluginInitCommand(),
            BossPluginValidateCommand(),
            BossPluginLinkCommand(),
        ),
    )

/** Configure before any BOSS logger initializes, keeping machine-readable stdout clean. */
internal fun configureHeadlessLogging() {
    System.setProperty("org.slf4j.simpleLogger.logFile", "System.err")
}
