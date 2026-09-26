package ai.rever.boss.mcp.secrets

import ai.rever.boss.mcp.mcpJsonNestingExceeds
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Finds and replaces secret references inside a tool call's JSON arguments.
 *
 * Works on the decoded JSON tree, not on the raw text, for two reasons:
 * - A reference can be spelled with JSON escapes (`{{secret:...`), and a regex over the
 *   raw text would miss it while the handler, which decodes, would see it.
 * - Substituting into decoded strings and re-encoding is what keeps the scalar map a handler
 *   reads through `McpToolArgs.string(k)` and the raw JSON it may parse itself in agreement
 *   (INV6): both are rebuilt from one tree.
 *
 * Only string primitives are substituted. References in keys are rejected. Numbers, booleans
 * and nulls cannot carry a reference and are left byte-for-byte alone.
 */
object McpArgumentSubstitution {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parse [raw] as a JSON object, or null if it is not one.
     *
     * Every real caller sends an object (the terminal-tab bridge serialises the MCP request's
     * `arguments`, the CLI validates one before dispatch), so a non-object that still contains
     * the reference marker is treated as malformed by the caller, not silently passed through.
     */
    fun parseElement(raw: String): JsonElement? =
        if (mcpJsonNestingExceeds(raw)) {
            // The tree reader would overflow on this input; null routes to the caller's
            // fallback (refuse when a marker is present, run raw otherwise), and the invoke
            // path still writes its ledger row.
            null
        } else {
            try {
                json.parseToJsonElement(raw)
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: SerializationException) {
                null
            }
        }

    fun parseObject(raw: String): JsonObject? = parseElement(raw) as? JsonObject

    /** Every decoded string value in [element], depth-first, keys excluded. */
    fun stringValues(element: JsonElement): List<String> {
        val out = ArrayList<String>()
        collect(element, out)
        return out
    }

    private fun collect(
        element: JsonElement,
        out: MutableList<String>,
    ) {
        when (element) {
            is JsonObject -> element.values.forEach { collect(it, out) }
            is JsonArray -> element.forEach { collect(it, out) }
            is JsonPrimitive -> if (element.isString) out.add(element.content)
        }
    }

    /**
     * Scan the argument object for references. See [SecretReferenceParser.findIn] for the
     * malformed-wins rule.
     */
    fun scan(arguments: JsonElement): SecretReferenceScan {
        if (referenceKey(arguments) != null) {
            return SecretReferenceScan.Malformed(MalformedSecretReference.IN_JSON_KEY)
        }
        return SecretReferenceParser.findIn(stringValues(arguments))
    }

    private fun referenceKey(element: JsonElement): String? =
        when (element) {
            is JsonObject -> {
                element.keys.firstOrNull(SecretReferenceParser::mayContain)
                    ?: element.values.firstNotNullOfOrNull(::referenceKey)
            }

            is JsonArray -> {
                element.firstNotNullOfOrNull(::referenceKey)
            }

            is JsonPrimitive -> {
                null
            }
        }

    /**
     * Return [arguments] with every reference replaced by its value from [values].
     *
     * Pure: the input object is not mutated. A reference absent from [values] is left as its
     * literal text, which the registry never lets happen (it resolves all or nothing, INV2) but
     * which is the safe behaviour if it ever did: a literal placeholder, not a wrong value.
     */
    fun substitute(
        arguments: JsonObject,
        values: Map<SecretReference, String>,
    ): JsonObject = rewrite(arguments, values) as JsonObject

    private fun rewrite(
        element: JsonElement,
        values: Map<SecretReference, String>,
    ): JsonElement =
        when (element) {
            is JsonObject -> {
                JsonObject(element.mapValues { (_, v) -> rewrite(v, values) })
            }

            is JsonArray -> {
                JsonArray(element.map { rewrite(it, values) })
            }

            is JsonPrimitive -> {
                if (element.isString && SecretReferenceParser.mayContain(element.content)) {
                    JsonPrimitive(SecretReferenceParser.substituteIn(element.content) { values[it] })
                } else {
                    element
                }
            }
        }

    /** Serialise a rewritten object back to the raw form handlers receive. */
    fun encode(arguments: JsonObject): String = json.encodeToString(JsonObject.serializer(), arguments)
}
