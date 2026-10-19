package ai.rever.boss.plugin.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The [FormFieldInfo] described by the JSON `getFormFieldInfoFromJS` reads out of the page, or null
 * when the page described no field.
 *
 * Split out of `BrowserHandleImpl.getFormFieldInfoFromJS` so the parse can be exercised without a
 * live browser frame, the way `cloneProgressLogMessage` is in `DesktopGitService`.
 *
 * **The page builds this with `JSON.stringify`, so it is read as JSON.** Each key used to be read
 * with the pattern `"key":"([^"]*)"`, which is not a JSON parser in two ways, and both of them
 * reach a credential:
 *
 * - `[^"]*` stops at the first quote, and `JSON.stringify` writes a quote inside a value as `\"`.
 *   So the password `a"b` arrived as `a\` - truncated at the quote, and carrying a backslash
 *   nobody typed.
 * - Nothing decoded an escape, so a textarea's newline arrived as the two characters `\n`, and the
 *   same for `\\`, `\t` and `\uXXXX`.
 *
 * `Json.Default` is the right instance here, and this is not the case AGENTS.md's `supabaseJson`
 * rule is about: that rule exists because `decodeFromString<T>` treats a column the model does not
 * declare as a hard error. There is no model here - `parseToJsonElement` builds a tree and each key
 * is read off it, so the script's `className`, which nothing below reads, is simply not looked at.
 *
 * A payload that is not a JSON object now yields null rather than a [FormFieldInfo] whose every
 * field is empty. The old shape claimed to describe a text field it had not read; the caller treats
 * null as "no autofill detail for this menu", which is what it is.
 */
internal fun formFieldInfoFrom(jsonString: String?): FormFieldInfo? {
    val fields = fieldsIn(jsonString) ?: return null

    // A string member's `content` is the decoded value; a non-string member is not one of these.
    val extractValue = { key: String ->
        (fields[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    }

    val inputType = extractValue("type").ifEmpty { "text" }
    val fieldName = extractValue("name")
    val fieldId = extractValue("id")
    val autocomplete = extractValue("autocomplete")

    return FormFieldInfo(
        fieldType = fieldTypeFor(inputType, fieldName, fieldId, autocomplete),
        fieldName = fieldName,
        fieldId = fieldId,
        fieldPlaceholder = extractValue("placeholder"),
        fieldValue = extractValue("value"),
        parentFormAction = extractValue("formAction").ifEmpty { null },
        inputType = inputType,
        autocomplete = autocomplete,
    )
}

/** The members of the page's answer, or null when it did not describe a field. */
private fun fieldsIn(jsonString: String?): JsonObject? {
    if (jsonString.isNullOrBlank() || jsonString == "null") {
        return null
    }
    return runCatching { Json.parseToJsonElement(jsonString) as? JsonObject }.getOrNull()
}

/**
 * Which kind of field this is.
 *
 * The branch order is exactly the inline version's: what the element DECLARES outranks what it is
 * NAMED, because `type` and `autocomplete` are the author saying so, while a name is a guess from
 * a substring. That precedence is the reason the two halves are separate functions rather than one
 * `when` - each half answers null for "no opinion", and the caller falls through in order.
 */
private fun fieldTypeFor(
    inputType: String,
    fieldName: String,
    fieldId: String,
    autocomplete: String,
): FormFieldType =
    declaredFieldType(inputType, autocomplete)
        ?: namedFieldType(fieldName, fieldId)
        ?: if (inputType == "text") FormFieldType.TEXT else FormFieldType.UNKNOWN

/** What the element declares through `type` and `autocomplete`, or null if it declares nothing. */
private fun declaredFieldType(
    inputType: String,
    autocomplete: String,
): FormFieldType? =
    when {
        inputType == "password" -> FormFieldType.PASSWORD
        inputType == "email" -> FormFieldType.EMAIL
        autocomplete.contains("username", ignoreCase = true) -> FormFieldType.USERNAME
        autocomplete.contains("email", ignoreCase = true) -> FormFieldType.EMAIL
        autocomplete.contains("password", ignoreCase = true) -> FormFieldType.PASSWORD
        else -> null
    }

/** What the element's name or id suggests, or null if neither suggests anything. */
private fun namedFieldType(
    fieldName: String,
    fieldId: String,
): FormFieldType? =
    when {
        fieldName.contains("user", ignoreCase = true) ||
            fieldId.contains("user", ignoreCase = true) ||
            fieldName.contains("login", ignoreCase = true) ||
            fieldId.contains("login", ignoreCase = true) -> FormFieldType.USERNAME

        fieldName.contains("email", ignoreCase = true) ||
            fieldId.contains("email", ignoreCase = true) -> FormFieldType.EMAIL

        fieldName.contains("pass", ignoreCase = true) ||
            fieldId.contains("pass", ignoreCase = true) -> FormFieldType.PASSWORD

        else -> null
    }
