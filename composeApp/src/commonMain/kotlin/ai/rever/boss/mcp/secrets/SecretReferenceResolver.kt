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
 * One page of the signed-in user's secrets. The only thing the resolver asks of the host.
 *
 * The resolver walks pages until every reference is found or the vault runs out;
 * [SecretReferenceResolver.maxPages] bounds the walk. A host whose pages are metadata only
 * (`list_secrets_v2`) also implements [values], the audited read of the referenced ids.
 */
fun interface SecretLookup {
    suspend fun page(
        limit: Int,
        offset: Int,
    ): Result<List<SecretRecord>>

    /**
     * Password and notes for [ids], read through the audited path. `null` means [page]
     * already carries values. An id absent from the map is one the caller may not read.
     */
    suspend fun values(ids: Set<String>): Result<Map<String, SecretFieldValues>>? = null
}

/** The value-bearing fields of one secret. */
data class SecretFieldValues(
    val password: String,
    val notes: String?,
) {
    override fun toString(): String = "SecretFieldValues(fields=[password, notes])"
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
 * Refusals ([deliveryRefusal]): AI provider keys (parity with the secret-manager plugin's
 * `aiProviderRefusal`), payment cards and CVVs (kind:card, kind:cvv), and connector tokens
 * (`fluck/<ws>/<connector>/...`). A reference must not be a way around `secret_get`.
 */
class SecretReferenceResolver(
    private val lookup: SecretLookup,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val maxPages: Int = DEFAULT_MAX_PAGES,
) {
    suspend fun resolve(references: Set<SecretReference>): SecretResolution {
        if (references.isEmpty()) return SecretResolution.Resolved(emptyMap(), emptyList())
        return findRecords(references.map { it.id }.toSet()).fold(
            onSuccess = { found ->
                refusalFor(references, found)
                    ?: withValues(references, found).fold(
                        onSuccess = { assemble(references, it) },
                        onFailure = { failure -> unreadable(failure) },
                    )
            },
            onFailure = { failure -> unreadable(failure) },
        )
    }

    private fun unreadable(failure: Throwable): SecretResolution =
        SecretResolution.Unresolved("the vault could not be read (${failure::class.simpleName ?: "error"})")

    /**
     * Fill password and notes for the referenced records that need them, through
     * [SecretLookup.values] when the host provides it. Only ids a reference reads a value
     * from are fetched, so a username-only reference reveals (and logs) nothing.
     */
    private suspend fun withValues(
        references: Set<SecretReference>,
        found: Map<String, SecretRecord>,
    ): Result<Map<String, SecretRecord>> {
        val needed = references.filter { it.field != SecretField.USERNAME }.map { it.id }.toSet()
        if (needed.isEmpty()) return Result.success(found)
        val fetched =
            try {
                lookup.values(needed) ?: return Result.success(found)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (
                @Suppress("TooGenericExceptionCaught") t: Throwable,
            ) {
                Result.failure(t)
            }
        return fetched.map { values ->
            found.mapValues { (id, record) ->
                val v = values[id]
                when {
                    id !in needed -> record
                    v == null -> record.copy(password = "", notes = null)
                    else -> record.copy(password = v.password, notes = v.notes)
                }
            }
        }
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
        val forbidden =
            references.firstNotNullOfOrNull { ref -> found[ref.id]?.let(::deliveryRefusal)?.let { ref.id to it } }
        val missing = references.filter { it.id !in found }
        return when {
            forbidden != null -> {
                SecretResolution.Forbidden("secret ${forbidden.first} ${forbidden.second}")
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

        /** Max vault payment rows: card number and CVV. Delivered only by the vault itself. */
        val PAYMENT_KIND_TAGS: Set<String> = setOf("kind:card", "kind:cvv")

        /** Connector credentials stored as `fluck/<ws>/<connector>/<VAR>`, also tagged `connector`. */
        const val CONNECTOR_TAG: String = "connector"
        private val CONNECTOR_TOKEN_WEBSITE = Regex("^fluck/[^/]+/(?!vault/)[^/]+/[^/]+$")

        /**
         * Why [record] must never be delivered through an agent-facing path, or null if it may.
         * Shared with the plugin's `secret_get` refusals; never names the website or a value.
         */
        fun deliveryRefusal(record: SecretRecord): String? =
            when {
                AI_PROVIDER_TAG in record.tags -> {
                    "is an AI provider key and is not readable through a reference; " +
                        "configure the provider in the host's AI settings instead"
                }

                record.tags.any { it in PAYMENT_KIND_TAGS } -> {
                    "is a payment card and is not readable through a reference; " +
                        "card details are only used by the vault's own checkout"
                }

                CONNECTOR_TAG in record.tags || CONNECTOR_TOKEN_WEBSITE.matches(record.website) -> {
                    "is a connector credential and is not readable through a reference; " +
                        "connectors read their own tokens"
                }

                else -> {
                    null
                }
            }

        const val DEFAULT_PAGE_SIZE: Int = 200

        /** 200 x 25 = 5,000 secrets, far past any personal vault; a bound, not a target. */
        const val DEFAULT_MAX_PAGES: Int = 25
    }
}
