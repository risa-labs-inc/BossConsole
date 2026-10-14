package ai.rever.boss.services.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Reads a Bitwarden unencrypted JSON export (`Tools > Export vault`, "json").
 *
 * The CSV path already covers Bitwarden's CSV export via the header aliases; this
 * covers the JSON export, which is the default Bitwarden offers and which the CSV
 * path cannot read. The shape:
 *
 * ```json
 * { "encrypted": false, "items": [
 *   { "type": 1, "name": "Example", "notes": "…",
 *     "login": { "uris": [ {"uri": "https://example.com"} ],
 *                "username": "john", "password": "hunter2" } }
 * ]}
 * ```
 *
 * Only login items (`type == 1`) carry a credential; secure notes, cards and
 * identities are skipped. Uses the low-level JSON element API - the same the
 * Chromium reader uses - so there are no `@Serializable` models to drift, and
 * unknown fields are ignored by construction.
 *
 * Handles plaintext passwords, so the logging rule is absolute: this parses and
 * returns, and never logs a value.
 */
object BitwardenJsonParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** Bitwarden's item type discriminator for a login. */
    private const val TYPE_LOGIN = 1

    /**
     * Every login in [text] as an [ImportedPassword], or an empty list when the
     * text is not a Bitwarden export.
     *
     * An encrypted export (`"encrypted": true`) has no readable passwords, so it
     * returns empty rather than a list of ciphertext masquerading as credentials.
     */
    fun parse(text: String): List<ImportedPassword> = parseEntries(text).filter { it.password.isNotEmpty() }

    /** Includes incomplete logins so the preview can explain why they are skipped. */
    internal fun parseEntries(text: String): List<ImportedPassword> {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        // An encrypted export (`"encrypted": true`) has no readable passwords.
        val items =
            root
                ?.takeIf {
                    it["encrypted"] == null || it["encrypted"] == kotlinx.serialization.json.JsonNull ||
                        (it["encrypted"] as? JsonPrimitive)?.contentOrNull == "false"
                }?.get("items") as? JsonArray
                ?: return emptyList()
        return items.mapNotNull { element -> (element as? JsonObject)?.let(::loginOf) }
    }

    /** True when [text] parses as a JSON object with an `items` array - a cheap sniff for routing. */
    fun looksLikeBitwarden(text: String): Boolean {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return false
        return root["items"] is JsonArray
    }

    /** One login item to a credential, including missing fields for preview validation. */
    private fun loginOf(item: JsonObject): ImportedPassword? {
        val isLogin = (item["type"] as? JsonPrimitive)?.intOrNull == TYPE_LOGIN
        val login = item["login"] as? JsonObject
        // Read the password verbatim: edge whitespace is part of a password, not
        // formatting, so it must not be trimmed away (and an all-spaces password
        // must not be dropped as if it were absent).
        val password = login?.rawString("password").orEmpty()
        if (!isLogin || login == null) return null

        val website = firstUri(login) ?: item.string("name").orEmpty()
        return ImportedPassword(
            website = website,
            username = login.string("username").orEmpty(),
            password = password,
            notes = item.string("notes"),
        )
    }

    /** Prefer a web URI to native-app associations, keeping an app-only URI for the skipped preview. */
    private fun firstUri(login: JsonObject): String? {
        val uris = login["uris"] as? JsonArray ?: return null
        return uris
            .asSequence()
            .mapNotNull { (it as? JsonObject)?.string("uri") }
            .toList()
            .let { values -> values.firstOrNull { !isNonWebPasswordEntry(it) } ?: values.firstOrNull() }
    }

    /** A string field, trimmed, or null when absent, JSON null, or blank. For labels (website/username/notes). */
    private fun JsonObject.string(key: String): String? = rawString(key)?.trim()?.ifEmpty { null }

    /** Only JSON strings are credentials; objects and numeric primitives are not passwords. */
    private fun JsonObject.rawString(key: String): String? {
        val value = this[key] as? JsonPrimitive
        return value?.takeIf { it.isString }?.contentOrNull
    }
}
