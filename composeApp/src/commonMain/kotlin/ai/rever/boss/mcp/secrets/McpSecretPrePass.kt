package ai.rever.boss.mcp.secrets

import ai.rever.boss.mcp.McpApprovalDisposition
import ai.rever.boss.mcp.McpPolicyAction
import ai.rever.boss.mcp.McpPolicyEngine
import ai.rever.boss.mcp.McpSecretPolicyAction
import ai.rever.boss.mcp.SECRET_READ_PERMISSION
import ai.rever.boss.mcp.parseMcpToolArgs
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.utils.logging.BossLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

private val argsLogger by lazy { BossLogger.forComponent("McpSecretPrePass") }

/**
 * The `{{secret:<id>}}` pre-pass `McpToolRegistryCore.invoke` runs on every call before the
 * policy and approval path. It decides, in a fixed order and before any prompt, whether a call is
 * secret-bearing and what that means for it; the registry then authorizes, executes and records
 * the call on the terms this returns. Its own class so the registry core stays the size it was,
 * and so the seam it establishes (one resolver in front of the governance path, see
 * `docs/MCP_SECRET_REFERENCES.md`, "Extending") has one home.
 *
 * @param resolver Where references are resolved from; `null` means no vault, and a call carrying
 *   a reference is refused rather than handed placeholder text.
 * @param secretsPermitted Whether the signed-in user may read secrets at all. Mirrors the gate the
 *   secret-manager plugin puts on `secret_get` (`secret.read`, admin bypass).
 */
internal class McpSecretPrePass(
    private val policyEngine: McpPolicyEngine,
    private val resolver: SecretReferenceResolver?,
    private val secretsPermitted: () -> Boolean,
) {
    /**
     * The secret pre-pass: what a call's `{{secret:...}}` references mean for it, decided in the
     * order `docs/MCP_SECRET_REFERENCES.md` documents and before any prompt.
     *
     * 1. No marker or Unicode JSON escape in the raw text: not secret-bearing. Escaped JSON must be
     *    decoded because a marker can be written as `\u007b\u007bsecret:`.
     * 2. Malformed reference: refused. A handler must never receive placeholder text.
     * 3. Feature off, or no `secret.read`: forbidden, before any vault read.
     * 4. Tool or provider policy DENY: nothing is read; the normal path refuses.
     * 5. `secretBearingCalls = DENY`: forbidden, before any vault read.
     * 6. Resolve, all or nothing. The values are held for this call only; the operator sees
     *    descriptors, and the handler sees values only after approval.
     */
    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod")
    suspend fun prepare(
        args: McpToolArgs,
        policy: McpPolicyAction,
    ): SecretPreparation {
        val hasLiteralMarker = SecretReferenceParser.mayContain(args.raw)
        if (!hasLiteralMarker && !args.raw.contains("\\u")) return SecretPreparation.None
        val element =
            McpArgumentSubstitution.parseElement(args.raw)
                ?: return if (hasLiteralMarker) {
                    SecretPreparation.Refused(
                        McpApprovalDisposition.SECRET_UNRESOLVED,
                        "Arguments carry a secret reference but are not valid JSON",
                    )
                } else {
                    SecretPreparation.None
                }
        val scan = McpArgumentSubstitution.scan(element)
        if (element !is JsonObject && scan !is SecretReferenceScan.None) {
            return SecretPreparation.Refused(
                McpApprovalDisposition.SECRET_UNRESOLVED,
                "Arguments carry a secret reference but are not a JSON object",
            )
        }
        val arguments = element as? JsonObject ?: return SecretPreparation.None
        val references =
            when (scan) {
                SecretReferenceScan.None -> {
                    return SecretPreparation.None
                }

                is SecretReferenceScan.Malformed -> {
                    return SecretPreparation.Refused(
                        McpApprovalDisposition.SECRET_UNRESOLVED,
                        "Malformed secret reference ${scan.literal}: ${scan.reason}".take(240),
                    )
                }

                is SecretReferenceScan.Found -> {
                    scan.references
                }
            }
        val config = policyEngine.config.value
        if (!config.secretReferencesEnabled) {
            return SecretPreparation.Refused(
                McpApprovalDisposition.SECRET_FORBIDDEN,
                "Secret references are disabled on this host",
                references,
            )
        }
        if (!secretsPermitted()) {
            return SecretPreparation.Refused(
                McpApprovalDisposition.SECRET_FORBIDDEN,
                "Secret references require the $SECRET_READ_PERMISSION permission",
                references,
            )
        }
        if (policy == McpPolicyAction.DENY) return SecretPreparation.DeniedByToolPolicy(references)
        if (config.secretBearingCalls == McpSecretPolicyAction.DENY) {
            return SecretPreparation.Refused(
                McpApprovalDisposition.SECRET_FORBIDDEN,
                "Secret-bearing calls are refused by host policy (secretBearingCalls = DENY)",
                references,
            )
        }
        return resolve(references, arguments, scrub = config.resultScrubbingEnabled)
    }

    /** Step 6 of [prepare]: the vault read, all or nothing, off the caller's dispatcher. */
    private suspend fun resolve(
        references: Set<SecretReference>,
        arguments: JsonObject,
        scrub: Boolean,
    ): SecretPreparation {
        val vault =
            resolver
                ?: return SecretPreparation.Refused(
                    McpApprovalDisposition.SECRET_UNRESOLVED,
                    "No secret vault is available on this host",
                    references,
                )
        return when (val resolution = withContext(Dispatchers.IO) { vault.resolve(references) }) {
            is SecretResolution.Forbidden -> {
                SecretPreparation.Refused(McpApprovalDisposition.SECRET_FORBIDDEN, resolution.reason, references)
            }

            is SecretResolution.Unresolved -> {
                SecretPreparation.Refused(McpApprovalDisposition.SECRET_UNRESOLVED, resolution.reason, references)
            }

            is SecretResolution.Resolved -> {
                SecretPreparation.Ready(
                    references = references,
                    arguments = arguments,
                    values = resolution.values,
                    descriptors = resolution.descriptors,
                    scrub = scrub,
                )
            }
        }
    }
}

/** What [McpSecretPrePass.prepare] decided; see its KDoc for the order. */
internal sealed interface SecretPreparation {
    /** Parsed references, for the ledger. Empty when the call is not secret-bearing. */
    val references: Set<SecretReference>

    /** What the operator is shown. Non-empty only when resolved. */
    val descriptors: List<SecretDescriptor> get() = emptyList()

    /**
     * The policy the call is authorized under, and the one the ledger records. A resolved
     * secret-bearing call always asks, whatever the tool's own rule or session trust says
     * (see [McpSecretPolicyAction]); every other outcome leaves the tool's policy alone.
     */
    fun effectivePolicy(toolPolicy: McpPolicyAction): McpPolicyAction = toolPolicy

    /** The arguments the handler receives: substituted only when resolved. */
    fun executionArgs(original: McpToolArgs): McpToolArgs = original

    /** The result transform: the scrubber only when resolved and enabled. */
    fun resultFilter(): McpResultFilter = McpResultFilter.NONE

    data object None : SecretPreparation {
        override val references: Set<SecretReference> get() = emptySet()
    }

    /** The tool's own policy is DENY; the normal path refuses and the ledger still lists the references. */
    data class DeniedByToolPolicy(
        override val references: Set<SecretReference>,
    ) : SecretPreparation

    data class Refused(
        val disposition: McpApprovalDisposition,
        val message: String,
        override val references: Set<SecretReference> = emptySet(),
    ) : SecretPreparation

    class Ready(
        override val references: Set<SecretReference>,
        private val arguments: JsonObject,
        private val values: Map<SecretReference, String>,
        override val descriptors: List<SecretDescriptor>,
        private val scrub: Boolean,
    ) : SecretPreparation {
        /**
         * Rebuilt through [parseMcpToolArgs] from the substituted tree, so the scalar map and
         * the raw JSON a handler might parse itself cannot disagree (INV6).
         */
        override fun executionArgs(original: McpToolArgs): McpToolArgs {
            val substituted = McpArgumentSubstitution.substitute(arguments, values)
            return parseMcpToolArgs(McpArgumentSubstitution.encode(substituted), argsLogger)
        }

        override fun effectivePolicy(toolPolicy: McpPolicyAction): McpPolicyAction = McpPolicyAction.ASK

        override fun resultFilter(): McpResultFilter = if (scrub) McpResultScrubber(values) else McpResultFilter.NONE

        /** Never the values. */
        override fun toString(): String = "Ready(references=${references.map { it.ledgerName }})"
    }
}
