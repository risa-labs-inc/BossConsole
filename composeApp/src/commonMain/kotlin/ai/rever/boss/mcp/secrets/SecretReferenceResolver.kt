package ai.rever.boss.mcp.secrets

import kotlinx.coroutines.CancellationException

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
 * One page of the signed-in user's own secrets. The only thing the resolver asks of the host.
 *
 * Pages rather than a by-id lookup because that is the RPC the host has (`get_user_secrets`
 * takes a limit and an offset, nothing else). The resolver walks pages until every reference is
 * found or the vault runs out; [SecretReferenceResolver.maxPages] bounds the walk.
 */
fun interface SecretLookup {
    suspend fun page(
        limit: Int,
        offset: Int,
    ): Result<List<SecretRecord>>
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
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val maxPages: Int = DEFAULT_MAX_PAGES,
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
     * Walk pages until every id in [wanted] is found, the vault runs out, or [maxPages] is hit.
     * A page that fails to read fails the whole lookup: a partial answer would be a partial call.
     */
    private suspend fun findRecords(wanted: Set<String>): Result<Map<String, SecretRecord>> {
        val found = HashMap<String, SecretRecord>()
        var offset = 0
        var pages = 0
        while (found.size < wanted.size && pages < maxPages) {
            val records = readPage(offset).getOrElse { return Result.failure(it) }
            for (record in records) {
                val id = record.id.lowercase()
                if (id in wanted && id !in found) found[id] = record
            }
            pages += 1
            offset += pageSize
            if (records.size < pageSize) break
        }
        return Result.success(found)
    }

    /** One page, with a throwing lookup folded into the same failure as a returned one. */
    private suspend fun readPage(offset: Int): Result<List<SecretRecord>> =
        try {
            lookup.page(pageSize, offset)
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

        const val DEFAULT_PAGE_SIZE: Int = 200

        /** 200 x 25 = 5,000 secrets, far past any personal vault; a bound, not a target. */
        const val DEFAULT_MAX_PAGES: Int = 25
    }
}
