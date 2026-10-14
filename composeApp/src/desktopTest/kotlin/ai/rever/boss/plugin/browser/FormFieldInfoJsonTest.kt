package ai.rever.boss.plugin.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the context menu learns about the field a right-click landed on.
 *
 * `BrowserHandleImpl.getFormFieldInfoFromJS` asks the page for `JSON.stringify({...})` and reads the
 * answer back here. The fixtures below are built with [stringify], which applies the same escaping
 * `JSON.stringify` does, so a case that fails here is a page BOSS meets - not a shape invented for
 * the test.
 */
class FormFieldInfoJsonTest {
    /** One character, escaped the way `JSON.stringify` escapes it (ECMA-262 QuoteJSONString). */
    private fun escaped(ch: Char): String =
        when (ch) {
            '\\' -> "\\\\"
            '"' -> "\\\""
            '\b' -> "\\b"
            '\u000C' -> "\\f"
            '\n' -> "\\n"
            '\r' -> "\\r"
            '\t' -> "\\t"
            else -> if (ch < ' ') "\\u%04x".format(ch.code) else ch.toString()
        }

    /** A string value as `JSON.stringify` would write it, quotes included. */
    private fun quote(raw: String): String = raw.map(::escaped).joinToString("", "\"", "\"")

    /** The page's own payload: the keys the injected script emits, in its order. */
    private fun stringify(
        type: String = "text",
        name: String = "",
        id: String = "",
        placeholder: String = "",
        value: String = "",
        formAction: String = "",
        autocomplete: String = "",
        className: String = "",
    ): String =
        listOf(
            "type" to type,
            "name" to name,
            "id" to id,
            "placeholder" to placeholder,
            "value" to value,
            "formAction" to formAction,
            "autocomplete" to autocomplete,
            "className" to className,
        ).joinToString(",", "{", "}") { (k, v) -> "\"$k\":${quote(v)}" }

    @Test
    fun `a field with nothing special is read as it was`() {
        val info = formFieldInfoFrom(stringify(type = "password", name = "pw", id = "pw1", value = "hunter2"))
        assertEquals(FormFieldType.PASSWORD, info?.fieldType)
        assertEquals("pw", info?.fieldName)
        assertEquals("hunter2", info?.fieldValue)
    }

    @Test
    fun `no field gives no info`() {
        assertNull(formFieldInfoFrom(null))
        assertNull(formFieldInfoFrom(""))
        assertNull(formFieldInfoFrom("null"))
    }

    @Test
    fun `an answer that is not a JSON object gives no info`() {
        // Rather than a FormFieldInfo whose every member is empty, which claimed to describe a
        // text field nothing had read. The caller reads null as "no autofill detail for this menu".
        assertNull(formFieldInfoFrom("not json at all"))
        assertNull(formFieldInfoFrom("[]"))
        assertNull(formFieldInfoFrom("\"a string\""))
        assertNull(formFieldInfoFrom("{\"type\":\"text\""))
    }

    @Test
    fun `a password containing a quote is not truncated at it`() {
        // JSON.stringify writes this value as "a\"b", and [^"]* stops at the escaped quote.
        val info = formFieldInfoFrom(stringify(type = "password", value = "a\"b"))
        assertEquals("a\"b", info?.fieldValue)
    }

    @Test
    fun `a password containing a backslash keeps one backslash`() {
        val info = formFieldInfoFrom(stringify(type = "password", value = "a\\b"))
        assertEquals("a\\b", info?.fieldValue)
    }

    @Test
    fun `an escape sequence in a placeholder is decoded, not passed through as text`() {
        val info = formFieldInfoFrom(stringify(placeholder = "line1\nline2\tend"))
        assertEquals("line1\nline2\tend", info?.fieldPlaceholder)
    }

    @Test
    fun `a name carrying a quote is kept whole`() {
        // Classification survives the truncation either way - `user\` still contains "user" - so
        // what this pins is the name a caller matches a saved login against.
        val info = formFieldInfoFrom(stringify(name = "user\"name"))
        assertEquals("user\"name", info?.fieldName)
        assertEquals(FormFieldType.USERNAME, info?.fieldType)
    }

    @Test
    fun `a control character escape is decoded, not passed through as text`() {
        // JSON.stringify emits a control character as \uXXXX, and a plain read returns the six
        // characters of the escape. Non-ASCII needs no escape, so this is the shape that reaches it.
        val info = formFieldInfoFrom(stringify(placeholder = "a\u0001b"))
        assertEquals("a\u0001b", info?.fieldPlaceholder)
    }

    @Test
    fun `a null member reads as absent, not as the four letters of null`() {
        // JsonNull IS a JsonPrimitive and its `content` is the string "null", so reading a member
        // without asking whether it is a string turns a null into a field literally named null.
        // The injected script writes `field.name || ''`, so it cannot produce this today - but the
        // function takes a String, and "null" is a worse answer than "" for every caller.
        val info = formFieldInfoFrom("{\"type\":\"text\",\"name\":null,\"value\":null}")
        assertEquals("", info?.fieldName)
        assertEquals("", info?.fieldValue)
    }

    @Test
    fun `what the element declares beats what it is named`() {
        // A box named "username" whose type is password is a password box. Sites do this on a
        // change-password form, where the new password input keeps the account's field name.
        val declared = formFieldInfoFrom(stringify(type = "password", name = "username"))
        assertEquals(FormFieldType.PASSWORD, declared?.fieldType)

        // Same the other way: autocomplete is the author speaking, a name is a substring guess.
        val auto = formFieldInfoFrom(stringify(autocomplete = "username", name = "passcode"))
        assertEquals(FormFieldType.USERNAME, auto?.fieldType)
    }

    @Test
    fun `a name is used when the element declares nothing`() {
        assertEquals(FormFieldType.USERNAME, formFieldInfoFrom(stringify(name = "login_id"))?.fieldType)
        assertEquals(FormFieldType.EMAIL, formFieldInfoFrom(stringify(id = "email_box"))?.fieldType)
        assertEquals(FormFieldType.PASSWORD, formFieldInfoFrom(stringify(name = "passcode"))?.fieldType)
    }

    @Test
    fun `a field that says nothing about itself is text, and an unknown type is unknown`() {
        assertEquals(FormFieldType.TEXT, formFieldInfoFrom(stringify(name = "q"))?.fieldType)
        assertEquals(FormFieldType.UNKNOWN, formFieldInfoFrom(stringify(type = "number"))?.fieldType)
    }

    @Test
    fun `an empty form action is absent rather than blank`() {
        assertNull(formFieldInfoFrom(stringify())?.parentFormAction)
        assertEquals("https://x/login", formFieldInfoFrom(stringify(formAction = "https://x/login"))?.parentFormAction)
    }
}
