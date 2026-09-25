package ai.rever.boss.mcp.secrets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The grammar, and every way a candidate can fail it.
 *
 * "Malformed wins" is the rule under test throughout: one bad candidate anywhere fails the scan,
 * because the alternative - a handler receiving the literal text - is the one silent failure
 * this feature must not have.
 */
class SecretReferenceParserTest {
    private val id = "6f1d2c3e-4b5a-4c6d-8e7f-90a1b2c3d4e5"

    private fun scan(vararg strings: String) = SecretReferenceParser.findIn(strings.toList())

    @Test
    fun `a bare reference means the password field`() {
        val result = scan("token={{secret:$id}}")
        assertIs<SecretReferenceScan.Found>(result)
        assertEquals(setOf(SecretReference(id, SecretField.PASSWORD)), result.references)
    }

    @Test
    fun `every field spelling parses to its own reference`() {
        val result = scan("{{secret:$id.password}} {{secret:$id.username}} {{secret:$id.notes}}")
        assertIs<SecretReferenceScan.Found>(result)
        assertEquals(SecretField.entries.map { SecretReference(id, it) }.toSet(), result.references)
    }

    @Test
    fun `bare and dot-password spellings are one reference`() {
        val result = scan("{{secret:$id}}", "{{secret:$id.password}}")
        assertIs<SecretReferenceScan.Found>(result)
        assertEquals(1, result.references.size)
    }

    @Test
    fun `an upper-case id is the same reference as its lower-case spelling`() {
        val result = scan("{{secret:${id.uppercase()}}}", "{{secret:$id}}")
        assertIs<SecretReferenceScan.Found>(result)
        assertEquals(setOf(SecretReference(id, SecretField.PASSWORD)), result.references)
    }

    @Test
    fun `adjacent references both parse`() {
        val other = "00000000-0000-4000-8000-000000000001"
        val result = scan("{{secret:$id}}{{secret:$other.username}}")
        assertIs<SecretReferenceScan.Found>(result)
        assertEquals(2, result.references.size)
    }

    @Test
    fun `text without the marker is not secret-bearing`() {
        assertIs<SecretReferenceScan.None>(scan("plain", "{secret:$id}", "{{secrets:$id}}"))
    }

    @Test
    fun `an unknown field is malformed, not ignored`() {
        val result = scan("{{secret:$id.totp}}")
        assertIs<SecretReferenceScan.Malformed>(result)
        assertEquals("{{secret:$id.totp}}", result.literal)
        assertTrue(result.reason.contains("unknown field"), result.reason)
    }

    @Test
    fun `a field spelled in the wrong case is malformed`() {
        assertIs<SecretReferenceScan.Malformed>(scan("{{secret:$id.PASSWORD}}"))
    }

    @Test
    fun `a non-uuid id is malformed`() {
        listOf("{{secret:github}}", "{{secret:}}", "{{secret:$id-extra}}", "{{secret:${id.dropLast(1)}}}")
            .forEach { literal ->
                val result = scan(literal)
                assertIs<SecretReferenceScan.Malformed>(result, literal)
                assertEquals(literal, result.literal)
            }
    }

    @Test
    fun `one malformed candidate fails a scan that also holds good ones`() {
        val result = scan("{{secret:$id}}", "{{secret:nope}}")
        assertIs<SecretReferenceScan.Malformed>(result)
    }

    @Test
    fun `an unterminated candidate is malformed`() {
        assertIs<SecretReferenceScan.Malformed>(scan("{{secret:$id"))
        assertIs<SecretReferenceScan.Malformed>(scan("{{secret:$id}"))
        assertIs<SecretReferenceScan.Malformed>(scan("{{secret:{$id}}}"))
    }

    @Test
    fun `a candidate body cannot span braces`() {
        // The inner "{{secret:...}}" is the candidate; the outer text around it is literal.
        val result = scan("{{{{secret:$id}}}}")
        assertIs<SecretReferenceScan.Found>(result)
        assertEquals(setOf(SecretReference(id, SecretField.PASSWORD)), result.references)
    }

    @Test
    fun `substitution replaces every well-formed candidate and leaves the rest`() {
        val out =
            SecretReferenceParser.substituteIn("a={{secret:$id}} b={{secret:$id.username}} c={{secret:$id}}") { ref ->
                if (ref.field == SecretField.PASSWORD) "P" else null
            }
        assertEquals("a=P b={{secret:$id.username}} c=P", out)
    }

    @Test
    fun `the marker pre-check is a plain substring test`() {
        assertTrue(SecretReferenceParser.mayContain("x{{secret:y"))
        assertTrue(!SecretReferenceParser.mayContain("{{ secret:y}}"))
    }

    @Test
    fun `descriptor and token never mention the value`() {
        val ref = SecretReference(id, SecretField.NOTES)
        assertEquals("[secret:$id.notes]", ref.token)
        assertEquals("$id.notes", ref.ledgerName)
        assertEquals("example.com (alice) - notes", SecretDescriptor(ref, "example.com", "alice").display)
    }
}
