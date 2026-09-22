package ai.rever.boss.mcp.loom

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The JSONL line the runtime would have written for one MCP call.
 *
 * [omit] drops fields on the way out, which is how the reader's handling of a record from an older
 * schema is exercised without hand-writing a second fixture.
 */
internal fun ledgerRecord(
    id: String = "rec-1",
    timestamp: Long = 1_700_000_000_000L,
    toolName: String = "run_command",
    providerId: String = "boss-workspace",
    policyApplied: String = "ASK",
    approvalDisposition: String = "AUTO_ALLOWED",
    durationMs: Long = 12L,
    isError: Boolean = false,
    sanitizedArgs: Map<String, String> = emptyMap(),
    errorSnippet: String? = null,
    hash: String? = "hash-for-rec-1",
    parentHash: String? = "genesis",
    omit: Set<String> = emptySet(),
): String {
    val record =
        buildJsonObject {
            put("id", id)
            put("timestamp", timestamp)
            put("toolName", toolName)
            put("providerId", providerId)
            put("policyApplied", policyApplied)
            put("approvalDisposition", approvalDisposition)
            put("durationMs", durationMs)
            put("isError", isError)
            put(
                "sanitizedArgs",
                buildJsonObject { sanitizedArgs.forEach { (key, value) -> put(key, value) } },
            )
            if (errorSnippet != null) put("errorSnippet", errorSnippet)
            if (hash != null) put("hash", hash)
            if (parentHash != null) put("parentHash", parentHash)
        }
    return JsonObject(record.filterKeys { it !in omit }).toString()
}
