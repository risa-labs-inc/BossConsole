package ai.rever.boss.mcp

/**
 * Nesting depth past which MCP tool arguments are not parsed at all. Real tool calls nest a
 * few levels; this only bounds a hostile payload.
 *
 * kotlinx's tree reader recurses once per nested array, so `Json.parseToJsonElement` on a deeply
 * nested payload throws StackOverflowError - an Error that `catch (e: Exception)` does not stop.
 * Every parse of agent-supplied arguments on the invoke path (the risk evaluator, and the
 * argument sanitizer the ledger record is built from) checks [mcpJsonNestingExceeds] first, so a
 * payload that would overflow is rejected before the parser sees it and the ledger row is still
 * written. One constant and one guard, so the two callers cannot drift apart.
 */
internal const val MAX_MCP_ARGUMENT_DEPTH = 128

/**
 * Whether [raw] nests arrays or objects more than [limit] deep, counting brackets outside JSON
 * strings (escapes included). A linear scan with no recursion and no parse.
 */
internal fun mcpJsonNestingExceeds(
    raw: String,
    limit: Int = MAX_MCP_ARGUMENT_DEPTH,
): Boolean {
    var depth = 0
    var inString = false
    var escaped = false
    for (c in raw) {
        when {
            escaped -> escaped = false
            inString && c == '\\' -> escaped = true
            c == '"' -> inString = !inString
            inString -> Unit
            c == '[' || c == '{' -> if (++depth > limit) return true
            c == ']' || c == '}' -> depth--
        }
    }
    return false
}
