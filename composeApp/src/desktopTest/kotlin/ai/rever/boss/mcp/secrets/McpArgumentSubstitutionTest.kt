package ai.rever.boss.mcp.secrets

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Substitution works on the decoded JSON tree, and the tree is the only thing it changes.
 */
class McpArgumentSubstitutionTest {
    private val id = "6f1d2c3e-4b5a-4c6d-8e7f-90a1b2c3d4e5"
    private val ref = SecretReference(id, SecretField.PASSWORD)
    private val user = SecretReference(id, SecretField.USERNAME)

    private fun obj(raw: String): JsonObject = requireNotNull(McpArgumentSubstitution.parseObject(raw))

    @Test
    fun `a non-object is not parsed`() {
        assertNull(McpArgumentSubstitution.parseObject("[1,2]"))
        assertNull(McpArgumentSubstitution.parseObject("\"{{secret:x}}\""))
        assertNull(McpArgumentSubstitution.parseObject("not json"))
        assertNull(McpArgumentSubstitution.parseObject(""))
    }

    @Test
    fun `references nested in objects and arrays are found and replaced`() {
        val arguments =
            obj(
                """{"env":{"TOKEN":"{{secret:$id}}","USER":"{{secret:$id.username}}"},""" +
                    """"args":["--pw","{{secret:$id}}"],"n":3}""",
            )
        val scan = McpArgumentSubstitution.scan(arguments)
        assertIs<SecretReferenceScan.Found>(scan)
        assertEquals(setOf(ref, user), scan.references)

        val out = McpArgumentSubstitution.substitute(arguments, mapOf(ref to "hunter2!!", user to "alice"))
        assertEquals("hunter2!!", out["env"]!!.jsonObject["TOKEN"]!!.jsonPrimitive.content)
        assertEquals("alice", out["env"]!!.jsonObject["USER"]!!.jsonPrimitive.content)
        assertEquals("hunter2!!", out["args"]!!.jsonArray[1].jsonPrimitive.content)
        assertEquals("3", out["n"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a reference spelled with json escapes is still a reference`() {
        // The escapes are assembled from doubled backslashes on purpose: written as \u007b in a
        // literal, the formatter rewrites them to the raw character and the test stops testing
        // anything. What the parser receives is the six-character sequence backslash-u-0-0-7-b.
        val open = "\\u007b\\u007b"
        val close = "\\u007d\\u007d"
        val raw = "{\"t\":\"" + open + "secret:" + id + close + "\"}"
        assertTrue(raw.contains("\\u007b"), raw)
        val arguments = obj(raw)
        val scan = McpArgumentSubstitution.scan(arguments)
        assertIs<SecretReferenceScan.Found>(scan)
        assertEquals(setOf(ref), scan.references)
    }

    @Test
    fun `a reference in a key is refused and never substituted`() {
        val arguments = obj("""{"{{secret:$id}}":"v"}""")
        assertIs<SecretReferenceScan.Malformed>(McpArgumentSubstitution.scan(arguments))
        val out = McpArgumentSubstitution.substitute(arguments, mapOf(ref to "value"))
        assertEquals(setOf("{{secret:$id}}"), out.keys)
    }

    @Test
    fun `nested and escaped key markers refuse even alongside valid values`() {
        val escaped = "\\u007b\\u007bsecret:$id}}"
        for (key in listOf("{{secret:$id}}", "{{secret:broken", escaped)) {
            val arguments = obj("""{"items":[{"$key":"v"}],"value":"{{secret:$id}}"}""")
            assertIs<SecretReferenceScan.Malformed>(McpArgumentSubstitution.scan(arguments))
        }
    }

    @Test
    fun `non-string primitives are untouched`() {
        val arguments = obj("""{"a":1.50,"b":true,"c":null,"d":"{{secret:$id}}"}""")
        val out = McpArgumentSubstitution.substitute(arguments, mapOf(ref to "v"))
        assertSame(arguments["a"], out["a"])
        assertSame(arguments["b"], out["b"])
        assertSame(arguments["c"], out["c"])
    }

    @Test
    fun `values with quotes, backslashes and newlines survive the encode round trip`() {
        val value = "pa\"ss\\word\nwith\ttabs é 🔐"
        val arguments = obj("""{"content":"KEY={{secret:$id}}"}""")
        val encoded = McpArgumentSubstitution.encode(McpArgumentSubstitution.substitute(arguments, mapOf(ref to value)))
        val back = Json.parseToJsonElement(encoded).jsonObject
        assertEquals("KEY=$value", back["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `substitution is pure`() {
        val arguments = obj("""{"a":"{{secret:$id}}"}""")
        McpArgumentSubstitution.substitute(arguments, mapOf(ref to "v"))
        assertEquals("{{secret:$id}}", arguments["a"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a reference with no value stays literal rather than becoming empty`() {
        val arguments = obj("""{"a":"{{secret:$id}}"}""")
        val out = McpArgumentSubstitution.substitute(arguments, emptyMap())
        assertEquals("{{secret:$id}}", out["a"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a value that itself looks like a reference is not re-substituted`() {
        // Substitution is one pass over the original tree; the value is data, not grammar.
        val other = "00000000-0000-4000-8000-000000000001"
        val arguments = obj("""{"a":"{{secret:$id}}"}""")
        val out = McpArgumentSubstitution.substitute(arguments, mapOf(ref to "{{secret:$other}}"))
        assertEquals("{{secret:$other}}", out["a"]!!.jsonPrimitive.content)
    }

    @Test
    fun `string values are collected depth first and keys are not`() {
        val arguments = obj("""{"k":{"x":["a",{"y":"b"}],"z":"c"},"w":1}""")
        assertEquals(listOf("a", "b", "c"), McpArgumentSubstitution.stringValues(arguments))
        assertEquals(emptyList(), McpArgumentSubstitution.stringValues(JsonArray(listOf(JsonPrimitive(1)))))
    }
}
