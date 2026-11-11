package ai.rever.boss.plugin.browser.autofill

import ai.rever.boss.services.supabase.models.CreateSecretRequest
import ai.rever.boss.services.supabase.models.SecretEntryWithSharing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.security.MessageDigest
import java.time.YearMonth

/** What Chromium should hold for one Secret Manager row. Values never appear in toString. */
internal sealed class MirrorItem {
    abstract val key: String

    /** Changes whenever anything Chromium would store changes; kept in memory only. */
    abstract val fingerprint: String

    class Login(
        val origin: String,
        val username: String,
        val password: String,
    ) : MirrorItem() {
        override val key = loginKey(origin, username)
        override val fingerprint get() = sha256("$origin\u0000$username\u0000$password")

        override fun toString() = "Login($key)"
    }

    class Card(
        val number: String,
        val expiry: YearMonth,
        val holder: String,
    ) : MirrorItem() {
        override val key = cardKey(number.takeLast(4), expiry)
        override val fingerprint get() = sha256("$number\u0000$expiry\u0000$holder")

        override fun toString() = "Card($key)"
    }

    class Address(
        val fields: AddressFields,
    ) : MirrorItem() {
        override val key = addressKey(fields)
        override val fingerprint get() = sha256(fields.toJson().toString())

        override fun toString() = "Address($key)"
    }
}

internal data class AddressFields(
    val name: String = "",
    val email: String = "",
    val company: String = "",
    val phone: String = "",
    val street: String = "",
    val city: String = "",
    val state: String = "",
    val zip: String = "",
    val country: String = "",
) {
    fun isEmpty() = street.isBlank() && city.isBlank() && zip.isBlank()

    fun toJson(): JsonObject =
        buildJsonObject {
            put("name", name)
            put("email", email)
            put("company", company)
            put("phone", phone)
            put("street", street)
            put("city", city)
            put("state", state)
            put("zip", zip)
            put("country", country)
        }

    companion object {
        fun parse(raw: String?): AddressFields? {
            val o = parseObject(raw) ?: return null
            return AddressFields(
                name = o.str("name"),
                email = o.str("email"),
                company = o.str("company"),
                phone = o.str("phone"),
                street = o.str("street"),
                city = o.str("city"),
                state = o.str("state"),
                zip = o.str("zip"),
                country = o.str("country"),
            ).takeUnless { it.isEmpty() }
        }
    }
}

internal fun loginKey(
    origin: String,
    username: String,
) = "pw|$origin|$username"

internal fun cardKey(
    last4: String,
    expiry: YearMonth,
) = "card|$last4|$expiry"

internal fun addressKey(f: AddressFields) =
    "addr|" + sha256(listOf(f.name, f.street, f.zip).joinToString("\u0000") { it.trim().lowercase() }).take(24)

internal fun sha256(s: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }

private val JSON = Json { ignoreUnknownKeys = true }

private fun parseObject(raw: String?): JsonObject? =
    raw?.takeIf { it.isNotBlank() }?.let { runCatching { JSON.parseToJsonElement(it).jsonObject }.getOrNull() }

private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

/**
 * Secret Manager rows -> Chromium items. Read-only: nothing here writes a row.
 *
 * Shapes, in order of precedence:
 * - `fluck/<ws>/vault/<alias>` (Max vault): kind:password -> login for the alias's site,
 *   kind:card -> card from password (PAN) + notes JSON; anything else skipped.
 * - other `fluck/...` rows are connector tokens -> skipped.
 * - `card/<brand>-<last4>` kind:card, `address/<label>` kind:address (canonical shapes).
 * - any other row whose website parses as a URL or domain -> login.
 */
@Suppress("TooManyFunctions", "ReturnCount") // one mapping per row shape; early returns read as the shape rules
internal object AutofillMapping {
    const val KIND_PASSWORD = "kind:password"
    const val KIND_CARD = "kind:card"
    const val KIND_ADDRESS = "kind:address"

    // create_secret's client validation rejects an empty password; an address has no secret.
    const val ADDRESS_PASSWORD_FILLER = "-"

    private val HOST = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$")

    fun map(rows: List<SecretEntryWithSharing>): Map<String, MirrorItem> {
        // Owned rows first, so an owned row wins over a shared copy with the same key.
        val ordered = rows.sortedByDescending { it.isOwner }
        val out = LinkedHashMap<String, MirrorItem>()
        for (row in ordered) {
            val item = map(row.website, row.username, row.password, row.notes, row.tags) ?: continue
            out.putIfAbsent(item.key, item)
        }
        return out
    }

    fun map(
        website: String,
        username: String,
        password: String,
        notes: String?,
        tags: List<String>,
    ): MirrorItem? {
        val site = website.trim()
        val kind = tags.firstOrNull { it.startsWith("kind:") }
        if (site.startsWith("fluck/")) return mapMaxVault(site, username, password, notes, kind)
        if (site.startsWith("card/")) return if (kind == KIND_CARD) card(password, notes) else null
        if (site.startsWith("address/")) {
            return if (kind == KIND_ADDRESS) AddressFields.parse(notes)?.let { MirrorItem.Address(it) } else null
        }
        if (kind != null && kind != KIND_PASSWORD) return null
        return login(normalizeOrigin(site), username, password)
    }

    private fun mapMaxVault(
        site: String,
        username: String,
        password: String,
        notes: String?,
        kind: String?,
    ): MirrorItem? {
        val parts = site.split('/', limit = 4)
        if (parts.size != 4 || parts[2] != "vault") return null
        val alias = parts[3]
        return when (kind) {
            KIND_PASSWORD -> login(maxLoginSite(alias)?.let { normalizeOrigin(it) }, username, password)
            KIND_CARD -> card(password, notes)
            else -> null
        }
    }

    /** Max's LoginAlias.siteOf: `site` or `<name>@<site>`. */
    internal fun maxLoginSite(alias: String): String? {
        val site = if ('@' in alias) alias.substringAfterLast('@') else alias
        return site.lowercase().takeIf { HOST.matches(it) && '.' in it }
    }

    private fun login(
        origin: String?,
        username: String,
        password: String,
    ): MirrorItem? {
        if (origin == null || password.isEmpty()) return null
        return MirrorItem.Login(origin, username, password)
    }

    private fun card(
        pan: String,
        notes: String?,
    ): MirrorItem? {
        val number = pan.filter { it.isDigit() }
        if (number.length !in 12..19) return null
        val o = parseObject(notes) ?: return null
        val expiry = parseExpiry(o.str("exp_month"), o.str("exp_year")) ?: return null
        return MirrorItem.Card(number, expiry, o.str("name"))
    }

    internal fun parseExpiry(
        month: String,
        year: String,
    ): YearMonth? {
        val m = month.trim().toIntOrNull()?.takeIf { it in 1..12 } ?: return null
        val y =
            year
                .trim()
                .toIntOrNull()
                ?.let { if (it in 0..99) 2000 + it else it }
                ?.takeIf { it in 2000..2199 }
                ?: return null
        return YearMonth.of(y, m)
    }

    /**
     * `https://host[:port]` for anything that names a site, else null. Scheme-less values are
     * treated as https; paths, queries and fragments are dropped; `www.` is kept (Chromium
     * matches exact origins and suggests across subdomains).
     */
    @Suppress("CyclomaticComplexMethod")
    fun normalizeOrigin(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        val withScheme = if ("://" in s) s else "https://$s"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
        val isLocal = host == "localhost" || host.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))
        if (!isLocal && (!HOST.matches(host) || '.' !in host)) return null
        val port = uri.port.takeIf { it != -1 && !(scheme == "https" && it == 443) && !(scheme == "http" && it == 80) }
        return "$scheme://$host" + (port?.let { ":$it" } ?: "")
    }

    // ---- Canonical writes (the save path) ----

    fun loginRequest(
        origin: String,
        username: String,
        password: String,
    ) = CreateSecretRequest(website = origin, username = username, password = password, tags = listOf(KIND_PASSWORD))

    fun cardRequest(
        number: String,
        expiry: YearMonth,
        holder: String,
    ): CreateSecretRequest {
        val digits = number.filter { it.isDigit() }
        val brand = brandOf(digits)
        val last4 = digits.takeLast(4)
        return CreateSecretRequest(
            website = "card/${brand.lowercase()}-$last4",
            username = "$brand $last4",
            password = digits,
            notes = cardNotes(holder, expiry),
            tags = listOf(KIND_CARD),
        )
    }

    /** Same keys as Max's VaultInboxPoller.notes(). No limit is known here, so 0 / blank. */
    fun cardNotes(
        holder: String,
        expiry: YearMonth,
    ): String =
        buildJsonObject {
            put("name", holder)
            put("exp_month", "%02d".format(expiry.monthValue))
            put("exp_year", "%02d".format(expiry.year % 100))
            put("limit_minor", 0)
            put("currency", "")
            put(
                "billing",
                buildJsonObject {
                    put("line1", "")
                    put("city", "")
                    put("postal", "")
                    put("country", "")
                },
            )
        }.toString()

    fun addressRequest(
        fields: AddressFields,
        taken: Set<String>,
    ): CreateSecretRequest {
        val stem = slug(fields.name.ifBlank { fields.street }).ifEmpty { "home" }
        var label = stem
        var n = 2
        while ("address/$label" in taken) label = "$stem-${n++}"
        return CreateSecretRequest(
            website = "address/$label",
            username = fields.name.ifBlank { "Address" },
            password = ADDRESS_PASSWORD_FILLER,
            notes = fields.toJson().toString(),
            tags = listOf(KIND_ADDRESS),
        )
    }

    internal fun slug(s: String): String =
        s
            .lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(32)
            .trimEnd('-')

    /** Mirrors Max's brandOf so both writers spell `card/<brand>-<last4>` the same way. */
    fun brandOf(pan: String): String =
        when {
            pan.startsWith("4") -> "Visa"
            pan.length >= 2 && pan.substring(0, 2).toIntOrNull() in 51..55 -> "Mastercard"
            pan.length >= 4 && pan.substring(0, 4).toIntOrNull() in 2221..2720 -> "Mastercard"
            pan.startsWith("34") || pan.startsWith("37") -> "Amex"
            pan.startsWith("6011") || pan.startsWith("65") -> "Discover"
            else -> "card"
        }
}
