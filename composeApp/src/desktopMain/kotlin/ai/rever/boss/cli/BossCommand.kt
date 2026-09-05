package ai.rever.boss.cli

import ai.rever.boss.utils.DeepLinkHandler
import ai.rever.boss.utils.DeepLinkOrigin
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.completion.CompletionCandidates
import com.github.ajalt.clikt.completion.CompletionCommand
import com.github.ajalt.clikt.completion.CompletionGenerator
import com.github.ajalt.clikt.core.ProgramResult
import ai.rever.boss.utils.SingleInstanceManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.net.URLEncoder

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
 * Loads workspace configuration.
 * Usage: boss workspace myworkspace.json
 */
class BossWorkspaceCommand : CliktCommand(name = "workspace") {
    override fun help(context: Context) = "Loads a workspace configuration"

    val configPath by argument(help = "Path to workspace config file")

    override fun run() {
        // Convert to deep link
        val encodedPath = URLEncoder.encode(configPath, "UTF-8")
        val deepLink = "boss://workspace?path=$encodedPath"
        DeepLinkHandler.processDeepLink(deepLink, DeepLinkOrigin.OPERATOR_CLI)
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
 * Queries running BOSS Console status (memory, active project, version).
 * Usage:
 *   boss status
 *   boss status --json
 */
class BossStatusCommand : CliktCommand(name = "status") {
    override fun help(context: Context) = "Queries status and health of the running BOSS Console instance"

    val json by option("--json", help = "Output status as JSON").flag(default = false)

    override fun run() {
        val result = SingleInstanceManager.queryStatus()
        result.fold(
            onSuccess = { rawJson ->
                if (json) {
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

    private fun formatHumanStatus(rawJson: String): String {
        return buildString {
            appendLine("BOSS Console Status")
            appendLine("-------------------")
            try {
                val element = Json.parseToJsonElement(rawJson).jsonObject
                appendLine("  Running:        ${element["running"]?.jsonPrimitive?.contentOrNull ?: "true"}")
                appendLine("  Version:        ${element["version"]?.jsonPrimitive?.contentOrNull ?: "unknown"}")
                appendLine("  OS:             ${element["os"]?.jsonPrimitive?.contentOrNull ?: "unknown"} (${element["arch"]?.jsonPrimitive?.contentOrNull ?: ""})")
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
            } catch (_: Exception) {
                appendLine(rawJson)
            }
        }
    }
}

/**
 * Discovers and invokes MCP tools in the running BOSS Console harness.
 * Usage:
 *   boss mcp list [--json]
 *   boss mcp invoke <tool_name> [-a|--args <json>] [--stdin]
 */
class BossMcpCommand : CliktCommand(name = "mcp") {
    override fun help(context: Context) = "Discovers and invokes MCP tools in the running BOSS Console"

    val action by argument(
        help = "Action to perform: list, describe (or info), invoke (or call)",
        completionCandidates = CompletionCandidates.Fixed("list", "describe", "info", "invoke", "call"),
    ).optional()
    val tool by argument(help = "Tool name to describe or invoke").optional()
    val args by option("-a", "--args", help = "JSON arguments string for the tool").default("{}")
    val stdin by option("--stdin", help = "Read JSON arguments from standard input").flag(default = false)
    val timeout by option("-t", "--timeout", help = "Timeout in seconds for tool invocation (default: 30)").default("30")
    val filter by option("-f", "--filter", help = "Filter tools by substring in name, description, or plugin ID")
    val raw by option("-r", "--raw", help = "Emit only the raw unescaped content (ideal for shell scripts and piping)").flag(default = false)
    val json by option("--json", help = "Output response in raw JSON format").flag(default = false)

    override fun run() {
        when (val act = action?.lowercase()) {
            null, "list" -> handleList()
            "describe", "info" -> handleDescribe()
            "invoke", "call" -> handleInvoke()
            else -> {
                echo("Unknown mcp action: '$act'. Supported actions: list, describe, invoke", err = true)
                throw ProgramResult(1)
            }
        }
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
                            val filtered = array.filter { item ->
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
                echo("Error: ${error.message}", err = true)
                throw ProgramResult(1)
            },
        )
    }

    private fun handleDescribe() {
        val toolName = tool
        if (toolName.isNullOrBlank()) {
            echo("Missing tool name. Usage: boss mcp describe <tool_name> [--json]", err = true)
            throw ProgramResult(1)
        }

        val result = SingleInstanceManager.queryMcpList()
        result.fold(
            onSuccess = { rawJson ->
                try {
                    val array = Json.parseToJsonElement(rawJson).jsonArray
                    val match = array.firstOrNull { item ->
                        val name = item.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        name.equals(toolName, ignoreCase = true)
                    }
                    if (match == null) {
                        echo("MCP tool '$toolName' not found or disabled in BOSS Console. Run 'boss mcp list' to view available tools.", err = true)
                        throw ProgramResult(1)
                    }
                    if (json) {
                        echo(match.toString())
                    } else {
                        echo(formatHumanToolDetail(match.jsonObject))
                    }
                } catch (e: ProgramResult) {
                    throw e
                } catch (e: Exception) {
                    echo("Error: Describing MCP tool '$toolName' failed: ${e.message}", err = true)
                    throw ProgramResult(1)
                }
            },
            onFailure = { error ->
                echo("Error: ${error.message}", err = true)
                throw ProgramResult(1)
            },
        )
    }

    private fun handleInvoke() {
        val toolName = tool
        if (toolName.isNullOrBlank()) {
            echo("Missing tool name. Usage: boss mcp invoke <tool_name> [--args '<json>' | --stdin] [-r|--raw] [--json]", err = true)
            throw ProgramResult(1)
        }

        val argumentsJson =
            if (stdin || args == "-") {
                try {
                    readBoundedStdin(SingleInstanceManager.MAX_REQUEST_BYTES)
                } catch (e: ProgramResult) {
                    throw e
                } catch (e: Exception) {
                    echo("Error: Failed to read arguments from stdin: ${e.message}", err = true)
                    throw ProgramResult(1)
                }
            } else {
                args
            }

        // Validate that argumentsJson is well-formed JSON
        try {
            val parsed = Json.parseToJsonElement(argumentsJson)
            if (parsed !is JsonObject) {
                echo("Error: Tool arguments must be a JSON object (e.g. '{\"key\":\"value\"}').", err = true)
                throw ProgramResult(1)
            }
        } catch (e: ProgramResult) {
            throw e
        } catch (e: Exception) {
            echo("Error: Malformed JSON arguments: ${e.message ?: "Invalid JSON syntax"}", err = true)
            throw ProgramResult(1)
        }

        val timeoutMs = (timeout.toLongOrNull() ?: 30L) * 1000L
        val result = SingleInstanceManager.invokeMcpTool(toolName, argumentsJson, timeoutMs = timeoutMs)
        result.fold(
            onSuccess = { responseJson ->
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
                    if (json) {
                        echo(responseJson)
                    } else {
                        echo(responseJson)
                    }
                }
            },
            onFailure = { error ->
                echo("Error: ${error.message}", err = true)
                throw ProgramResult(1)
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

    private fun formatHumanToolsList(rawJson: String, filterQuery: String?): String {
        return buildString {
            if (filterQuery.isNullOrBlank()) {
                appendLine("Available MCP Tools in BOSS Console")
                appendLine("===================================")
            } else {
                appendLine("Available MCP Tools in BOSS Console (filtered by: '$filterQuery')")
                appendLine("================================================================")
            }
            try {
                val array = Json.parseToJsonElement(rawJson).jsonArray
                val filtered = if (filterQuery.isNullOrBlank()) {
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
    }

    private fun formatHumanToolDetail(obj: JsonObject): String {
        return buildString {
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            val pluginId = obj["pluginId"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            val desc = obj["description"]?.jsonPrimitive?.contentOrNull ?: "No description provided."
            val requiresAdmin = obj["requiresAdmin"]?.jsonPrimitive?.booleanOrNull ?: false
            val perms = obj["requiredPermissions"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

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
 * Configures Clikt command structure.
 */
fun createBossCLI(): BossCommand =
    BossCommand().subcommands(
        BossUrlCommand(),
        BossWorkspaceCommand(),
        BossFileCommand(),
        BossFolderCommand(),
        BossTerminalCommand(),
        BossStatusCommand(),
        BossMcpCommand(),
        BossCompletionCommand(),
    )

