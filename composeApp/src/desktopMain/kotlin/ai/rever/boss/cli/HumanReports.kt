package ai.rever.boss.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * The human renderings of `boss status` and `boss mcp list|describe`.
 *
 * Every string read out of a response here came from outside BOSS - a plugin's tool name, plugin id
 * and description, the active project's name - and is printed to an operator who is deciding what to
 * trust, so each goes through [TerminalText]. Printed raw, a newline in a description forges a second
 * tool in the list and an ESC sequence acts on the terminal. JSON output escapes control characters
 * already and does not pass through here.
 */

/** A string field, made safe to print on one line, or null when it is absent. */
private fun JsonObject.safeText(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.let(TerminalText::safe)

internal fun formatHumanStatus(rawJson: String): String =
    buildString {
        appendLine("BOSS Console Status")
        appendLine("-------------------")
        try {
            val element = Json.parseToJsonElement(rawJson).jsonObject
            appendLine("  Running:        ${element.safeText("running") ?: "true"}")
            appendLine("  Version:        ${element.safeText("version") ?: "unknown"}")
            val osName = element.safeText("os") ?: "unknown"
            val osArch = element.safeText("arch") ?: ""
            appendLine("  OS:             $osName ($osArch)")
            val project = element.safeText("activeProject")
            if (!project.isNullOrBlank()) {
                appendLine("  Active Project: $project")
            }
            val mem = element["memory"]?.jsonObject
            if (mem != null) {
                val used = mem.safeText("usedMb") ?: "?"
                val max = mem.safeText("maxMb") ?: "?"
                val heapPct = mem.safeText("heapPercent") ?: "?"
                appendLine("  JVM Memory:     ${used}MB / ${max}MB ($heapPct%)")
            }
            healthSummaryOf(element)?.let { appendLine("  Health:         ${TerminalText.safe(it)}") }
        } catch (_: Exception) {
            appendLine(TerminalText.safeIndented(rawJson, indent = "  "))
        }
    }

internal fun formatHumanToolsList(
    rawJson: String,
    filterQuery: String?,
): String =
    buildString {
        val shownQuery = filterQuery?.let(TerminalText::safe)
        if (filterQuery.isNullOrBlank()) {
            appendLine("Available MCP Tools in BOSS Console")
            appendLine("===================================")
        } else {
            appendLine("Available MCP Tools in BOSS Console (filtered by: '$shownQuery')")
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
                    appendLine("No MCP tools matching '$shownQuery'.")
                }
            } else {
                for (item in filtered) {
                    val obj = item.jsonObject
                    val name = obj.safeText("name") ?: "unknown"
                    val desc = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
                    val pluginId = obj.safeText("pluginId") ?: ""
                    appendLine("• $name ($pluginId)")
                    if (desc.isNotBlank()) {
                        // Every line indented: only the first used to be, so a later line of a
                        // description could start with a bullet and read as another tool.
                        appendLine(TerminalText.safeIndented(desc, indent = "    "))
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
            appendLine(TerminalText.safeIndented(rawJson, indent = "  "))
        }
    }

internal fun formatHumanToolDetail(obj: JsonObject): String =
    buildString {
        val name = obj.safeText("name") ?: "unknown"
        val pluginId = obj.safeText("pluginId") ?: "unknown"
        val desc = obj["description"]?.jsonPrimitive?.contentOrNull ?: "No description provided."
        val requiresAdmin = obj["requiresAdmin"]?.jsonPrimitive?.booleanOrNull ?: false
        val perms =
            obj["requiredPermissions"]
                ?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.map(TerminalText::safe)
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
        appendLine(TerminalText.safeIndented(desc, indent = "  "))
        obj["inputSchema"]?.let {
            appendLine()
            appendLine("Input schema:")
            // Compact JSON has no raw line breaks, but its serializer leaves DEL, C1 and bidi
            // characters alone.
            appendLine(TerminalText.safe(it.toString()).prependIndent("  "))
        }
    }
