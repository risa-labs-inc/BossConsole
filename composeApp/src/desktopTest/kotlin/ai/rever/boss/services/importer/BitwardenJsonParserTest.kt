package ai.rever.boss.services.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pins [BitwardenJsonParser] against the shape of an unencrypted Bitwarden JSON export. */
class BitwardenJsonParserTest {
    private val export =
        """
        {
          "encrypted": false,
          "folders": [ { "id": "f1", "name": "Work" } ],
          "items": [
            { "type": 1, "name": "Example", "notes": "a note",
              "login": { "uris": [ { "uri": "https://example.com" } ],
                         "username": "john", "password": "hunter2" } },
            { "type": 1, "name": "NoUriEntry",
              "login": { "username": "jane", "password": "pw2" } },
            { "type": 2, "name": "Secure note", "notes": "not a login" },
            { "type": 1, "name": "Passwordless",
              "login": { "username": "nobody", "password": "" } }
          ]
        }
        """.trimIndent()

    @Test
    fun `parses login items and skips the rest`() {
        val result = BitwardenJsonParser.parse(export)
        assertEquals(2, result.size, "only the two logins with a password")

        val example = result.first { it.username == "john" }
        assertEquals("https://example.com", example.website)
        assertEquals("hunter2", example.password)
        assertEquals("a note", example.notes)
    }

    @Test
    fun `falls back to the item name when no URI is present`() {
        val noUri = BitwardenJsonParser.parse(export).first { it.username == "jane" }
        assertEquals("NoUriEntry", noUri.website)
        assertEquals("pw2", noUri.password)
        assertNull(noUri.notes)
    }

    @Test
    fun `an encrypted export yields nothing rather than ciphertext`() {
        val encrypted = export.replace("\"encrypted\": false", "\"encrypted\": true")
        assertTrue(BitwardenJsonParser.parse(encrypted).isEmpty())
    }

    @Test
    fun `a password is read verbatim, keeping edge whitespace`() {
        val json =
            """
            { "encrypted": false, "items": [
              { "type": 1, "name": "Spaced",
                "login": { "username": "u", "password": "  pa ss  " } }
            ]}
            """.trimIndent()
        assertEquals("  pa ss  ", BitwardenJsonParser.parse(json).single().password)
    }

    @Test
    fun `an all-whitespace password is preserved, not dropped`() {
        val json =
            """
            { "encrypted": false, "items": [
              { "type": 1, "name": "Spaces",
                "login": { "username": "u", "password": "   " } }
            ]}
            """.trimIndent()
        assertEquals("   ", BitwardenJsonParser.parse(json).single().password)
    }

    @Test
    fun `sniffing recognises a Bitwarden export and rejects other text`() {
        assertTrue(BitwardenJsonParser.looksLikeBitwarden(export))
        assertFalse(BitwardenJsonParser.looksLikeBitwarden("url,username,password\na,b,c"))
        assertFalse(BitwardenJsonParser.looksLikeBitwarden("{\"not\": \"bitwarden\"}"))
    }
}
