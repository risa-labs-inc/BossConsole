package ai.rever.boss.mcp.secrets

/**
 * Which field of a stored secret a reference names.
 *
 * Deliberately three members. The Secret Manager also stores a TOTP seed and recovery codes,
 * and neither is reachable through a reference: a generated one-time code is six digits, so
 * the result scrubber cannot protect it, and no BOSS tool consumes one today. Adding a member
 * here is a host design decision that needs its own threat model, not a drop-in.
 */
enum class SecretField(
    /** The spelling an agent writes after the dot, and the one the ledger records. */
    val wireName: String,
) {
    PASSWORD("password"),
    USERNAME("username"),
    NOTES("notes"),
    ;

    companion object {
        fun fromWireName(name: String): SecretField? = entries.firstOrNull { it.wireName == name }
    }
}

/**
 * One secret field an agent asked a tool call to receive, identified by the secret's id.
 *
 * Identity is (id, field): `{{secret:ID}}` and `{{secret:ID.password}}` name the same reference,
 * which is why the parser returns a set of these rather than a list of literals.
 */
data class SecretReference(
    /** The secret's id as the vault issued it, lower-cased so two spellings cannot resolve twice. */
    val id: String,
    val field: SecretField,
) {
    // `this.field`, not `field`: inside an accessor the bare word is the backing-field keyword.

    /** The canonical spelling: what a scrubbed result shows in place of the value. */
    val token: String get() = "[secret:$id.${this.field.wireName}]"

    /** The compact form the ledger records - never the value, never the website. */
    val ledgerName: String get() = "$id.${this.field.wireName}"
}

/**
 * What the operator sees for one reference: which secret, which field, and never the value.
 *
 * [website] and [username] are the same metadata tier `secrets_list` already reveals to an agent,
 * so surfacing them in the approval dialog discloses nothing new; they exist so an operator can
 * tell "github.com (deploy-bot) - password" from an opaque id.
 */
data class SecretDescriptor(
    val reference: SecretReference,
    val website: String,
    val username: String,
) {
    /** One line for the approval dialog and the risk reason. */
    val display: String get() = "$website ($username) - ${reference.field.wireName}"
}

/**
 * The outcome of scanning a tool call's arguments for references.
 *
 * A malformed candidate is its own outcome rather than "no references", because a handler
 * receiving the literal text `{{secret:abc.totp}}` would be the one silent failure this feature
 * must never have: the agent believes a credential was delivered and the tool got a placeholder.
 */
sealed interface SecretReferenceScan {
    /** No `{{secret:` anywhere in the arguments: the call is not secret-bearing. */
    data object None : SecretReferenceScan

    /** Every candidate parsed; [references] is the deduplicated set. */
    data class Found(
        val references: Set<SecretReference>,
    ) : SecretReferenceScan

    /**
     * Something that looks like a reference does not parse. [literal] is the offending text and
     * is safe to show: it cannot contain a value, only whatever the agent typed.
     */
    data class Malformed(
        val literal: String,
        val reason: String,
    ) : SecretReferenceScan
}

/**
 * Recognises `{{secret:<uuid>}}` and `{{secret:<uuid>.<field>}}` inside argument text.
 *
 * Grammar, and why each part is as strict as it is:
 * - The id is a UUID (8-4-4-4-12 hex). The vault issues UUIDs, so nothing else can resolve, and
 *   a bounded character class keeps the scan linear (no backtracking).
 * - The field is optional and defaults to `password`, the value an agent needs most often.
 * - Any other `{{secret:...}}` shape is [SecretReferenceScan.Malformed], never ignored.
 *
 * The scan looks at decoded string values only (see [findIn]); JSON keys are not scanned and are
 * never substituted, so a reference in a key passes through as literal text.
 */
object SecretReferenceParser {
    /** Cheap pre-check every governed call pays: a substring search, no regex, no parse. */
    const val MARKER: String = "{{secret:"

    private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /** Every candidate, well-formed or not: anything between `{{secret:` and the next `}}`. */
    private val candidate = Regex("\\{\\{secret:([^{}]*)\\}\\}")

    /** Whether [text] contains anything worth parsing. */
    fun mayContain(text: String): Boolean = text.contains(MARKER)

    /**
     * Parse one candidate body (the text between `{{secret:` and `}}`).
     *
     * Returns the reference, or null with the reason in [onMalformed].
     */
    private fun parseBody(
        body: String,
        onMalformed: (String) -> Unit,
    ): SecretReference? {
        val dot = body.indexOf('.')
        val idPart = if (dot < 0) body else body.substring(0, dot)
        val fieldPart = if (dot < 0) null else body.substring(dot + 1)
        val field = if (fieldPart == null) SecretField.PASSWORD else SecretField.fromWireName(fieldPart)
        return when {
            !uuid.matches(idPart) -> {
                onMalformed("the id is not a secret id (expected a UUID)")
                null
            }

            field == null -> {
                onMalformed(
                    "unknown field '$fieldPart' (expected one of " +
                        SecretField.entries.joinToString { it.wireName } + ")",
                )
                null
            }

            else -> {
                SecretReference(idPart.lowercase(), field)
            }
        }
    }

    /**
     * Scan one decoded string value. Used by [findIn] on every string in the argument tree and
     * by the substitution pass, which needs the same matches in the same places.
     */
    internal fun matchesIn(text: String): Sequence<MatchResult> = candidate.findAll(text)

    /**
     * Scan a list of decoded string values (the argument tree's leaves) for references.
     *
     * Malformed wins over found: one bad candidate fails the whole call, because the agent
     * cannot be handed a partially resolved call (INV2).
     */
    @Suppress("ReturnCount") // Malformed wins immediately; the final return distinguishes none/found.
    fun findIn(strings: Iterable<String>): SecretReferenceScan {
        val found = LinkedHashSet<SecretReference>()
        var any = false
        for (text in strings) {
            if (!mayContain(text)) continue
            any = true
            for (match in matchesIn(text)) {
                var malformed: SecretReferenceScan.Malformed? = null
                val ref =
                    parseBody(match.groupValues[1]) { reason ->
                        malformed = SecretReferenceScan.Malformed(match.value, reason)
                    }
                malformed?.let { return it }
                if (ref != null) found.add(ref)
            }
            val unmatchedText = candidate.replace(text, "")
            val unmatchedMarker = unmatchedText.indexOf(MARKER)
            if (unmatchedMarker >= 0) {
                return SecretReferenceScan.Malformed(
                    unmatchedText.substring(unmatchedMarker).take(120),
                    "the reference is not terminated with }}",
                )
            }
        }
        return if (!any) SecretReferenceScan.None else SecretReferenceScan.Found(found)
    }

    /**
     * Replace every well-formed reference in [text] using [valueFor].
     *
     * Only called after [findIn] returned [SecretReferenceScan.Found] for the same tree, so every
     * candidate here parses; a candidate that somehow does not is left untouched rather than
     * guessed at.
     */
    internal fun substituteIn(
        text: String,
        valueFor: (SecretReference) -> String?,
    ): String {
        if (!mayContain(text)) return text
        return candidate.replace(text) { match ->
            val ref = parseBody(match.groupValues[1]) {}
            val value = ref?.let(valueFor)
            value ?: match.value
        }
    }
}
