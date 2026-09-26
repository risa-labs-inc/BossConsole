package ai.rever.boss.mcp.sentinel

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.RegisteredMcpTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/**
 * Computes deterministic, canonical ToolDNA fingerprints for MCP tool definitions.
 *
 * Guaranteed properties:
 * 1. Key Order Invariance: Reordered JSON object keys in schemas produce the exact same fingerprint.
 * 2. Format Invariance: Whitespace, formatting, or indentation in input JSON schemas do not alter the fingerprint.
 * 3. Version Scoped: Includes algorithm version to allow future evolution without hash collisions.
 * 4. Content Scoped: Hashes provider identity, tool name, description, schema, readOnly, and permission rules.
 */
object ToolDnaFingerprinter {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Compute a canonical [ToolDnaFingerprint] for a given [RegisteredMcpTool].
     */
    fun computeFingerprint(
        tool: RegisteredMcpTool,
        version: String = ToolDnaFingerprint.CURRENT_ALGORITHM_VERSION,
    ): ToolDnaFingerprint =
        computeFingerprint(
            providerId = tool.providerId,
            definition = tool.definition,
            version = version,
        )

    /**
     * Compute a canonical [ToolDnaFingerprint] for a [providerId] and [definition].
     */
    fun computeFingerprint(
        providerId: String,
        definition: McpToolDefinition,
        version: String = ToolDnaFingerprint.CURRENT_ALGORITHM_VERSION,
    ): ToolDnaFingerprint {
        val canonicalProvider = providerId.trim().lowercase()
        val canonicalName = definition.name.trim()
        val canonicalDesc = normalizeText(definition.description)
        val canonicalSchema = canonicalizeJson(definition.inputSchema)

        val readOnly = definition.readOnly
        val requiresAdmin = definition.requiresAdmin
        val sortedPerms = definition.requiredPermissions.sorted().map { JsonPrimitive(it) }
        val permissions = canonicalizeElement(JsonArray(sortedPerms))

        val payload =
            buildString {
                append("v=").append(version).append("\n")
                append("provider=").append(canonicalProvider).append("\n")
                append("name=").append(canonicalName).append("\n")
                append("desc=")
                    .append(canonicalDesc.length)
                    .append(":")
                    .append(canonicalDesc)
                    .append("\n")
                append("schema=")
                    .append(canonicalSchema.length)
                    .append(":")
                    .append(canonicalSchema)
                    .append("\n")
                append("readOnly=").append(readOnly).append("\n")
                append("requiresAdmin=").append(requiresAdmin).append("\n")
                append("permissions=").append(permissions).append("\n")
            }

        val digest = sha256Hex(payload)

        return ToolDnaFingerprint(
            providerId = providerId,
            toolName = definition.name,
            fingerprint = digest,
            algorithmVersion = version,
            canonicalDescription = canonicalDesc,
            canonicalInputSchemaJson = canonicalSchema,
            readOnly = readOnly,
            requiresAdmin = requiresAdmin,
        )
    }

    /**
     * Normalize string text (trim whitespace, standardize line endings).
     */
    fun normalizeText(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text.replace("\r\n", "\n").replace("\r", "\n").trim()
    }

    /**
     * Convert any JSON string representation of a schema into a canonical, sorted-key, minified JSON string.
     * Handles malformed or non-object JSON gracefully by falling back to normalized string representation.
     */
    fun canonicalizeJson(jsonString: String?): String {
        if (jsonString.isNullOrBlank()) return "{}"
        return try {
            val parsed = json.parseToJsonElement(jsonString)
            canonicalizeElement(parsed)
        } catch (_: Throwable) {
            normalizeText(jsonString)
        }
    }

    private fun canonicalizeElement(element: JsonElement): String =
        when (element) {
            is JsonObject -> {
                val sortedKeys = element.keys.sorted()
                val entries =
                    sortedKeys.joinToString(",") { key ->
                        "\"${escapeJsonString(key)}\":${canonicalizeElement(element.getValue(key))}"
                    }
                "{$entries}"
            }

            is JsonArray -> {
                val isAllStringPrimitives = element.all { it is JsonPrimitive && (it as JsonPrimitive).isString }
                val items =
                    if (isAllStringPrimitives) {
                        element.map { canonicalizeElement(it) }.sorted()
                    } else {
                        element.map { canonicalizeElement(it) }
                    }
                "[${items.joinToString(",")}]"
            }

            is JsonPrimitive -> {
                when {
                    element.isString -> "\"${escapeJsonString(element.content)}\""
                    element == JsonNull -> "null"
                    else -> element.content
                }
            }

            is JsonNull -> {
                "null"
            }
        }

    private fun escapeJsonString(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\u000C", "\\f")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    private fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
