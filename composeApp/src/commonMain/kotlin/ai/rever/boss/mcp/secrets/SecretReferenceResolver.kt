package ai.rever.boss.mcp.secrets

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The slice of a vault entry the resolver needs. Mapped from the host's own secret model at the
 * one place the resolver is wired (see `McpToolRegistryImpl`), so this package depends on no
 * Supabase type and every test can hand in records directly.
 */
data class SecretRecord(
    val id: String,
    val website: String,
    val username: String,
    val password: String,
    val notes: String?,
    val tags: List<String> = emptyList(),
) {
    /** Never expose the plaintext-bearing fields through incidental logging or diagnostics. */
    override fun toString(): String = "SecretRecord(id=$id, fields=[website, username, password, notes, tags])"
}

/**
 * One secret the signed-in user may see, by id. The only thing the resolver asks of the host.
 *
 * By id rather than by page, so resolving a reference decrypts the referenced row and nothing
 * else (`get_user_secret_by_id`, which mirrors the listing's visibility rule). Earlier the host
 * had only the paged listing, and one reference walked it page by page, every row decrypted on
 * the way, and a reference to an id that did not exist walked the whole vault before it was
 * refused; all of it before any operator prompt, on the agent's say-so.
 *
 * `null` is "no visible secret has this id", which is also what another user's id answers; a
 * failure is "the vault could not be read". The two are refused differently.
 */
fun interface SecretLookup {
    suspend fun byId(id: String): Result<SecretRecord?>
}

/**
 * The outcome of resolving a call's references. Reasons name ids and fields, never values, and
 * never the website either: a refusal message travels back to the agent.
 */
sealed interface SecretResolution {
    /** Every reference resolved. [descriptors] is what the operator sees; [values] is what the handler gets. */
    data class Resolved(
        val values: Map<SecretReference, String>,
        val descriptors: List<SecretDescriptor>,
    ) : SecretResolution {
        /** Never expose resolved values through incidental logging or diagnostics. */
        override fun toString(): String = "Resolved(references=${values.keys.map { it.ledgerName }})"
    }

    /** A reference names a secret this path must never deliver (parity with the plugin's own refusal). */
    data class Forbidden(
        val reason: String,
    ) : SecretResolution

    /** A reference could not be resolved: unknown id, empty field, or the vault could not be read. */
    data class Unresolved(
        val reason: String,
    ) : SecretResolution
}

/**
 * Resolves references against the vault, all or nothing.
 *
 * Refusal precedence inside one call: a forbidden reference refuses the call even when another
 * reference is merely missing, because "you may not have this" is the more important answer.
 *
 * The AI-provider refusal mirrors `aiProviderRefusal` in the secret-manager plugin: secrets tagged
 * [AI_PROVIDER_TAG] are provider configuration that the host's AI settings own, and the plugin
 * already refuses to reveal them through `secret_get`. A reference must not be a way around that.
 */
class SecretReferenceResolver(
    private val lookup: SecretLookup,
) {
    suspend fun resolve(references: Set<SecretReference>): SecretResolution {
        if (references.isEmpty()) return SecretResolution.Resolved(emptyMap(), emptyList())
        return findRecords(references.map { it.id }.toSet()).fold(
            onSuccess = { found -> refusalFor(references, found) ?: assemble(references, found) },
            onFailure = { failure ->
                SecretResolution.Unresolved("the vault could not be read (${failure::class.simpleName ?: "error"})")
            },
        )
    }

    /**
     * One lookup per distinct id, issued together rather than one after another: the reads are
     * independent, they all happen before the operator is prompted, and a call may carry up to
     * [ai.rever.boss.mcp.secrets.McpSecretPrePass.MAX_REFERENCES_PER_CALL] of them, so serialising
     * would put that many round trips in front of the prompt for no gain.
     *
     * A lookup that fails fails the whole call: a partial answer would be a partial call. A miss
     * does not; it is reported by [refusalFor] with every other miss, so the agent learns all of
     * them at once. The result keeps the reference order, which only the message ordering depends
     * on, so nothing observable changed with the concurrency.
     */
    private suspend fun findRecords(wanted: Set<String>): Result<Map<String, SecretRecord>> =
        coroutineScope {
            val reads = wanted.map { id -> id to async { lookupById(id) } }
            val found = LinkedHashMap<String, SecretRecord>()
            for ((id, read) in reads) {
                val record = read.await().getOrElse { return@coroutineScope Result.failure(it) } ?: continue
                // The RPC filters on this id, so a row for any other is a vault fault, and it is
                // treated as the miss it is rather than delivered under the requested name.
                if (record.id.equals(id, ignoreCase = true)) found[id] = record
            }
            Result.success(found)
        }

    /** One lookup, with a throwing vault folded into the same failure as a returned one. */
    private suspend fun lookupById(id: String): Result<SecretRecord?> =
        try {
            lookup.byId(id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            Result.failure(t)
        }

    /**
     * Forbidden outranks missing: "you may not have this" is the more important answer even
     * when another reference in the same call is merely unknown.
     */
    private fun refusalFor(
        references: Set<SecretReference>,
        found: Map<String, SecretRecord>,
    ): SecretResolution? {
        val forbidden = references.firstOrNull { found[it.id]?.tags?.contains(AI_PROVIDER_TAG) == true }
        val missing = references.filter { it.id !in found }
        return when {
            forbidden != null -> {
                SecretResolution.Forbidden(
                    "secret ${forbidden.id} is an AI provider key and is not readable through a reference; " +
                        "configure the provider in the host's AI settings instead",
                )
            }

            missing.isNotEmpty() -> {
                SecretResolution.Unresolved(
                    "no secret with id ${missing.joinToString { it.id }} " +
                        "(use secrets_list or secret_search to find the id)",
                )
            }

            else -> {
                null
            }
        }
    }

    /** Every reference has a record; pick its field, refusing a field the record does not hold. */
    private fun assemble(
        references: Set<SecretReference>,
        found: Map<String, SecretRecord>,
    ): SecretResolution {
        val values = LinkedHashMap<SecretReference, String>()
        val descriptors = ArrayList<SecretDescriptor>()
        for (ref in references) {
            val record = found.getValue(ref.id)
            val value =
                when (ref.field) {
                    SecretField.PASSWORD -> record.password
                    SecretField.USERNAME -> record.username
                    SecretField.NOTES -> record.notes
                }?.takeIf { it.isNotEmpty() }
                    ?: return SecretResolution.Unresolved("secret ${ref.id} has no ${ref.field.wireName}")
            values[ref] = value
            descriptors.add(SecretDescriptor(ref, record.website, record.username))
        }
        return SecretResolution.Resolved(values, descriptors)
    }

    companion object {
        /**
         * The tag the secret-manager plugin puts on provider keys
         * (`ProviderCredentialStore.TAG_AI_PROVIDER`). This is an intentionally duplicated,
         * fail-open cross-repository contract: change both declarations together.
         */
        const val AI_PROVIDER_TAG: String = "ai-provider"
    }
}
