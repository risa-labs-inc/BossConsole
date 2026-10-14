package ai.rever.boss.services.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [KeePassXmlParser] against a KeePass 2.x XML export, including the two
 * format traps: an entry's `<History>` must not be imported as extra credentials,
 * and Recycle Bin entries must be skipped. Also checks XXE hardening.
 */
class KeePassXmlParserTest {
    private val export =
        """
        <?xml version="1.0" encoding="utf-8"?>
        <KeePassFile>
          <Meta>
            <RecycleBinEnabled>True</RecycleBinEnabled>
            <RecycleBinUUID>UkVDWUNMRQ==</RecycleBinUUID>
          </Meta>
          <Root>
            <Group>
              <Name>Root</Name>
              <UUID>Um9vdA==</UUID>
              <Entry>
                <String><Key>Title</Key><Value>Example</Value></String>
                <String><Key>UserName</Key><Value>john</Value></String>
                <String><Key>Password</Key><Value>hunter2</Value></String>
                <String><Key>URL</Key><Value>https://example.com</Value></String>
                <String><Key>Notes</Key><Value>a note</Value></String>
                <History>
                  <Entry>
                    <String><Key>Password</Key><Value>oldpw</Value></String>
                    <String><Key>URL</Key><Value>https://old.example.com</Value></String>
                  </Entry>
                </History>
              </Entry>
              <Group>
                <Name>Subgroup</Name>
                <UUID>U3ViZ3JvdXA=</UUID>
                <Entry>
                  <String><Key>Title</Key><Value>Nested</Value></String>
                  <String><Key>Password</Key><Value>nestedpw</Value></String>
                </Entry>
              </Group>
              <Group>
                <Name>Recycle Bin</Name>
                <UUID>UkVDWUNMRQ==</UUID>
                <Entry>
                  <String><Key>Title</Key><Value>Deleted</Value></String>
                  <String><Key>Password</Key><Value>deletedpw</Value></String>
                </Entry>
              </Group>
            </Group>
          </Root>
        </KeePassFile>
        """.trimIndent()

    @Test
    fun `parses entries across nested groups`() {
        val result = KeePassXmlParser.parse(export)
        assertEquals(2, result.size, "Example and Nested only")

        val example = result.first { it.website == "https://example.com" }
        assertEquals("john", example.username)
        assertEquals("hunter2", example.password)
        assertEquals("a note", example.notes)

        val nested = result.first { it.password == "nestedpw" }
        assertEquals("Nested", nested.website, "URL absent, so the title is the website")
    }

    @Test
    fun `does not import an entry's history versions`() {
        assertTrue(KeePassXmlParser.parse(export).none { it.password == "oldpw" })
    }

    @Test
    fun `skips entries in the Recycle Bin`() {
        assertTrue(KeePassXmlParser.parse(export).none { it.password == "deletedpw" })
    }

    @Test
    fun `a disabled Recycle Bin still excludes its deleted entries`() {
        val disabled = export.replace("<RecycleBinEnabled>True", "<RecycleBinEnabled>False")
        assertEquals(2, KeePassXmlParser.parse(disabled).size)
        assertTrue(KeePassXmlParser.parse(disabled).none { it.password == "deletedpw" })
    }

    @Test
    fun `BOM is accepted and malformed XML never writes credential fragments to stderr`() {
        assertEquals(2, KeePassXmlParser.parse("\uFEFF" + export).size)
        val original = System.err
        val captured = java.io.ByteArrayOutputStream()
        try {
            System.setErr(java.io.PrintStream(captured))
            assertTrue(KeePassXmlParser.parse("<KeePassFile><synthetic-password></wrong>").isEmpty())
            assertEquals("", captured.toString())
        } finally {
            System.setErr(original)
        }
    }

    @Test
    fun `sniffing recognises a KeePass export and rejects other text`() {
        assertTrue(KeePassXmlParser.looksLikeKeePass(export))
        assertTrue(KeePassXmlParser.looksLikeKeePass("<KeePassFile><Root/></KeePassFile>"), "no xml declaration")
        assertFalse(KeePassXmlParser.looksLikeKeePass("<html><body>not keepass</body></html>"))
    }

    @Test
    fun `a CSV whose data merely contains the marker is not misrouted to KeePass`() {
        // The marker appears inside a field, not as the document root.
        val csv = "url,username,password,notes\nhttps://x.com,u,p,\"<KeePassFile fake>\"\n"
        assertFalse(KeePassXmlParser.looksLikeKeePass(csv), "root anchoring: a field value is not the root element")
    }

    @Test
    fun `a DOCTYPE with an external entity is refused, not expanded`() {
        val xxe =
            """
            <?xml version="1.0"?>
            <!DOCTYPE foo [ <!ENTITY xxe "INJECTED"> ]>
            <KeePassFile><Root><Group>
              <Entry><String><Key>Password</Key><Value>&xxe;</Value></String></Entry>
            </Group></Root></KeePassFile>
            """.trimIndent()
        // disallow-doctype-decl makes the parse fail; parse() returns empty rather than throwing.
        assertTrue(KeePassXmlParser.parse(xxe).isEmpty())
    }
}
