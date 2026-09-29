package ai.rever.boss.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Validate [argumentsJson] against a tool's declared `inputSchema` — the JSON-Schema
 * subset the plugin API's contracts actually use: `required` keys and each
 * `properties` entry's primitive `type`. Returns a caller-facing error naming the
 * offending fields, or `null` when the arguments satisfy the schema.
 *
 * [McpToolRegistryCore.invoke] turns a non-null return into an `isError` result before
 * any approval prompt or handler call, which is what makes the schema a gate rather
 * than documentation. Two edges are deliberate:
 * - A schema that does not parse to a JSON object fails closed: the host cannot enforce
 *   a contract it cannot read, so the call is refused instead of waved through.
 * - Non-object arguments fail closed. The invoke entry point normalizes only blank input
 *   to `{}` before reaching this gate; malformed and deeply nested input never reaches a handler.
 *
 * Error text names fields and expected types only, never argument values: the same
 * string reaches the caller and the operation ledger, and values can be sensitive.
 * A free function so the rule is testable without constructing the registry.
 */
@Suppress("ReturnCount") // Malformed schema and malformed arguments are separate fail-closed guards.
internal fun validateMcpToolArguments(
    inputSchema: String,
    argumentsJson: String,
): String? {
    val schema = parseJsonObject(inputSchema) ?: return "MCP tool inputSchema is not a JSON object"
    val args = parseJsonObject(argumentsJson) ?: return "MCP arguments must be a JSON object"
    val problems = missingRequiredArguments(schema, args) + mistypedArguments(schema, args)
    return if (problems.isEmpty()) {
        null
    } else {
        "MCP arguments failed inputSchema validation: ${problems.joinToString("; ")}"
    }
}

/** Parse [raw] as a JSON object, or `null` when it is malformed or any other shape. */
private fun parseJsonObject(raw: String): JsonObject? =
    try {
        if (mcpJsonNestingExceeds(raw)) null else Json.parseToJsonElement(raw) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    }

/** One message per `required` name the arguments object does not carry. */
private fun missingRequiredArguments(
    schema: JsonObject,
    args: JsonObject,
): List<String> =
    (schema["required"] as? JsonArray)
        ?.mapNotNull { element -> (element as? JsonPrimitive)?.takeIf { it.isString }?.content }
        .orEmpty()
        .filter { name -> name !in args }
        .map { name -> "missing required argument '$name'" }

/** One message per argument present in the call whose declared `type` it fails. */
private fun mistypedArguments(
    schema: JsonObject,
    args: JsonObject,
): List<String> {
    val properties = schema["properties"] as? JsonObject ?: return emptyList()
    return properties.mapNotNull { (name, propertySchema) ->
        val value = args[name] ?: return@mapNotNull null
        val types = declaredSchemaTypes(propertySchema)
        if (types.isEmpty() || types.any { matchesMcpSchemaType(it, value) }) {
            null
        } else {
            "argument '$name' must be of type ${types.joinToString(" or ")}"
        }
    }
}

/** The `type` keyword of a `properties` entry - a single name or, per JSON Schema, a list. */
private fun declaredSchemaTypes(propertySchema: JsonElement): List<String> =
    when (val declared = (propertySchema as? JsonObject)?.get("type")) {
        is JsonPrimitive -> listOf(declared.content)
        is JsonArray -> declared.mapNotNull { (it as? JsonPrimitive)?.content }
        else -> emptyList()
    }

/**
 * Whether [value] satisfies one JSON-Schema primitive `type` keyword. A keyword outside
 * the known set declares nothing this host can check, so it imposes no constraint.
 */
private fun matchesMcpSchemaType(
    type: String,
    value: JsonElement,
): Boolean =
    when (type) {
        "string" -> value is JsonPrimitive && value.isString
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "null" -> value is JsonNull
        "boolean", "integer", "number" -> value.isUnquotedScalarOf(type)
        else -> true
    }

/**
 * The scalar-type check behind [matchesMcpSchemaType]. `booleanOrNull`/`longOrNull`/
 * `doubleOrNull` read a primitive's content even when it is a quoted string, so a
 * JSON `"true"` or `"123"` must be excluded here rather than satisfy a non-string type.
 */
private fun JsonElement.isUnquotedScalarOf(type: String): Boolean {
    if (this !is JsonPrimitive || isString) return false
    return when (type) {
        "boolean" -> booleanOrNull != null
        "integer" -> longOrNull != null
        else -> doubleOrNull != null
    }
}
