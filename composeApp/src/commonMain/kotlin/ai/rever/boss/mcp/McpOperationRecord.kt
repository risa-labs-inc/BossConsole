package ai.rever.boss.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * An immutable ledger entry recording a single MCP tool execution, rejection, or timeout.
 *
 * Persisted to `~/.boss/mcp-calls.jsonl` as an append-only JSONL record.
 * Arguments are strictly sanitized before recording so secrets/passwords/tokens never
 * land in the persistent log.
 */
@Serializable
data class McpOperationRecord(
    val id: String,
    val timestamp: Long,
    val toolName: String,
    val providerId: String,
    val policyApplied: McpPolicyAction,
    val approvalDisposition: McpApprovalDisposition,
    val durationMs: Long,
    val isError: Boolean,
    val sanitizedArgs: Map<String, String>,
    val errorSnippet: String? = null,
    /**
     * The secret references the call carried, as `<id>.<field>` - what the tool was allowed to
     * receive, never what it received. Empty for every call without references, and absent from
     * records written before this field existed, which decode with the default.
     */
    val secretRefs: List<String> = emptyList(),
    /**
     * True when the destructive-shell gate overrode a saved ALLOW: the tool had a saved ALLOW (a
     * tool rule, a trusted plugin or session trust), the risk evaluator rated this shell call
     * CRITICAL, and so it was asked about again (#1577). With [policyApplied] and
     * [approvalDisposition] this tells an overridden call that YOLO mode ran unattended
     * (`YOLO_ALLOWED`) apart from a routine one under the same ALLOW (#1655).
     *
     * It is not "this call was destructive". Under the default ASK policy a destructive call was
     * never covered by a saved ALLOW, so nothing is overridden and this stays `false`, including
     * when YOLO mode answers it. A secret-bearing call with a saved ALLOW is asked about too, but by
     * the secret policy, not this gate, and records `false`; its [secretRefs] say why it asked.
     * `false` as well for records written before this field existed, which decode with the default.
     *
     * Hashed only when `true`. An older build decodes the field away (`ignoreUnknownKeys`), so
     * `boss mcp ledger verify` on a downgraded install reports those rows as `RECORD_ALTERED`; the
     * same is already true of rows carrying [secretRefs].
     */
    val escalated: Boolean = false,
    /**
     * SHA-256 over [parentHash] and this record's [canonicalFormForHashing], so a record edited
     * after the fact no longer agrees with the chain that follows it.
     *
     * `null` is not an error and is not a failed check: it is how a record written before integrity
     * tracking existed decodes, and it means the record carries no integrity claim either way. Such
     * a record is reported as *unverifiable* by `McpLedgerStore.verify`, never as invalid. A record
     * is never re-chained once written, so a `null` here stays `null`.
     */
    val hash: String? = null,
    /**
     * The [hash] this record was chained to, or [McpLedgerChain.GENESIS_HASH] when it starts a
     * chain.
     *
     * Stored rather than inferred from the record before it, because the record before it is not
     * always on disk: rotation discards the oldest backup, so the oldest surviving record's
     * predecessor is routinely gone. Inferring the parent there would mean a record whose contents
     * had been edited could not be told apart from one whose predecessor had merely been rotated
     * away, and the edit would go unreported. With the parent recorded, every record that carries a
     * hash is checkable on its own.
     */
    val parentHash: String? = null,
)

/**
 * The exact text hashed for [McpOperationRecord.hash].
 *
 * Spelled out field by field rather than re-encoding the record with kotlinx.serialization,
 * because the encoder's configuration is not part of this format. The ledger's encoder currently
 * omits default-valued fields (so `errorSnippet = null` never appears on disk) and re-encoding
 * would silently change every historical hash the day someone set `encodeDefaults = true`. Here
 * keys are emitted in a fixed order, map keys are sorted, and an absent snippet is written as an
 * explicit `null`, so a record's canonical form depends on the record and nothing else.
 */
internal fun McpOperationRecord.canonicalFormForHashing(): String =
    buildJsonObject {
        put("id", id)
        put("timestamp", timestamp)
        put("toolName", toolName)
        put("providerId", providerId)
        put("policyApplied", policyApplied.name)
        put("approvalDisposition", approvalDisposition.name)
        put("durationMs", durationMs)
        put("isError", isError)
        put(
            "sanitizedArgs",
            buildJsonObject { sanitizedArgs.toSortedMap().forEach { (key, value) -> put(key, value) } },
        )
        put("errorSnippet", errorSnippet)
        if (secretRefs.isNotEmpty()) {
            put("secretRefs", JsonArray(secretRefs.map(::JsonPrimitive)))
        }
        // Written only when set, like secretRefs, so every record without it hashes as it did.
        if (escalated) {
            put("escalated", true)
        }
    }.toString()
