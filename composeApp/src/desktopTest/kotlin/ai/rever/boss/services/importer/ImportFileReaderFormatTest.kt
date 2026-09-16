package ai.rever.boss.services.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the format routing in [ImportFileReader.parseContent]: the new Bitwarden
 * JSON and KeePass XML paths are reached, and the pre-existing CSV path is not
 * disturbed.
 */
class ImportFileReaderFormatTest {
    @Test
    fun `secret requests reject empty credentials but preserve whitespace`() {
        val request =
            ai.rever.boss.services.supabase.models
                .CreateSecretRequest("https://example.com", "john", "")
        assertTrue(request.validate().isFailure)
        assertTrue(request.copy(password = " ").validate().isSuccess)
        val update =
            ai.rever.boss.services.supabase.models
                .UpdateSecretRequest("id", "https://example.com", "john", "")
        assertTrue(update.validate().isFailure)
        assertTrue(update.copy(password = " ").validate().isSuccess)
    }

    @Test
    fun `Bitwarden null encryption flag and whitespace password survive preview`() {
        val json = """{"encrypted":null,"items":[{"type":1,"login":{
            "uris":[{"uri":"https://example.com"}],"username":"john","password":"   "}}]}"""
        val preview = ImportFileReader.parseContent("vault.json", json).getOrThrow()
        val password = preview.passwords.single().password
        assertEquals("   ", password)
        assertTrue(
            ai.rever.boss.services.supabase.models
                .CreateSecretRequest(
                    website = "https://example.com",
                    username = "john",
                    password = password,
                ).validate()
                .isSuccess,
        )
    }

    @Test
    fun `KeePass sniffing bounds a long unterminated comment`() {
        assertEquals(false, KeePassXmlParser.looksLikeKeePass("<!--" + " ".repeat(100_000)))
    }

    @Test
    fun `routes a Bitwarden JSON export to the Bitwarden parser`() {
        val json =
            """
            { "encrypted": false, "items": [
              { "type": 1, "name": "Example",
                "login": { "uris": [ { "uri": "https://example.com" } ],
                           "username": "john", "password": "hunter2" } }
            ]}
            """.trimIndent()
        val preview = ImportFileReader.parseContent("vault.json", json).getOrThrow()
        assertEquals(1, preview.passwords.size)
        assertEquals("john", preview.passwords.first().username)
    }

    @Test
    fun `routes a KeePass XML export to the KeePass parser`() {
        val xml =
            """
            <?xml version="1.0"?>
            <KeePassFile><Root><Group>
              <Entry>
                <String><Key>Title</Key><Value>Example</Value></String>
                <String><Key>UserName</Key><Value>john</Value></String>
                <String><Key>Password</Key><Value>hunter2</Value></String>
                <String><Key>URL</Key><Value>https://example.com</Value></String>
              </Entry>
            </Group></Root></KeePassFile>
            """.trimIndent()
        val preview = ImportFileReader.parseContent("db.xml", xml).getOrThrow()
        assertEquals(1, preview.passwords.size)
        assertEquals("https://example.com", preview.passwords.first().website)
    }

    @Test
    fun `still routes a plain CSV to the CSV parser`() {
        val csv = "url,username,password\nhttps://example.com,john,hunter2\n"
        val preview = ImportFileReader.parseContent("passwords.csv", csv).getOrThrow()
        assertEquals(1, preview.passwords.size)
        assertEquals("hunter2", preview.passwords.first().password)
    }

    @Test
    fun `a CSV whose field contains the KeePass marker still parses as CSV`() {
        val csv = "url,username,password,notes\nhttps://x.com,john,hunter2,\"see <KeePassFile> note\"\n"
        val preview = ImportFileReader.parseContent("passwords.csv", csv).getOrThrow()
        assertEquals(1, preview.passwords.size)
        assertEquals("john", preview.passwords.first().username)
    }

    @Test
    fun `Bitwarden preview reports incomplete and native-app rows before import`() {
        val json = """{"items":[
            {"type":1,"name":"example.test","login":{"username":"u","password":"  keep  "}},
            {"type":1,"name":"example.test","login":{"password":"p"}},
            {"type":1,"name":"example.test","login":{"username":"u","password":""}},
            {"type":1,"login":{"username":"u","password":"p","uris":[{"uri":"androidapp://com.example"}]}},
            {"type":1,"login":{"username":"u","password":"p","uris":[{"uri":"iosapp://com.example"}]}}
        ]}"""
        val preview = ImportFileReader.parseContent("vault.json", json).getOrThrow()
        assertEquals("  keep  ", preview.passwords.single().password)
        assertEquals(
            listOf(
                SkipReason.MISSING_USERNAME,
                SkipReason.MISSING_PASSWORD,
                SkipReason.MISSING_URL,
                SkipReason.MISSING_URL,
            ),
            preview.skipped.map { it.reason },
        )
    }

    @Test
    fun `whitespace-only passwords are retained by JSON and CSV previews`() {
        val json = """{"items":[{"type":1,"name":"example.test",
            "login":{"username":"u","password":"   "}}]}"""
        val preview = ImportFileReader.parseContent("vault.json", json).getOrThrow()
        assertEquals("   ", preview.passwords.single().password)
        assertTrue(preview.skipped.isEmpty())
        val csv = "url,username,password\nexample.test,u,   \n"
        val csvPreview = ImportFileReader.parseContent("vault.csv", csv).getOrThrow()
        assertEquals("   ", csvPreview.passwords.single().password)
    }

    @Test
    fun `a web URI after an app association is retained`() {
        val json = """{"items":[{"type":1,"login":{"username":"u","password":"p",
            "uris":[{"uri":"androidapp://com.example"},{"uri":"https://example.test"}]}}]}"""
        val preview = ImportFileReader.parseContent("vault.json", json).getOrThrow()
        assertEquals("https://example.test", preview.passwords.single().website)
    }

    @Test
    fun `malformed JSON fields cannot escape as credential-bearing exceptions`() {
        val json = """{"items":[{"type":1,"name":"example.test",
            "login":{"username":"u","password":{"synthetic-secret":"value"}}}]}"""
        val preview = ImportFileReader.parseContent("vault.json", json).getOrThrow()
        assertTrue(preview.passwords.isEmpty())
        assertEquals(SkipReason.MISSING_PASSWORD, preview.skipped.single().reason)
        val invalidHeader = """{"encrypted":{"synthetic-secret":"value"},"items":[]}"""
        val failure = ImportFileReader.parseContent("vault.json", invalidHeader).exceptionOrNull()
        assertTrue(failure is UnrecognisedImportFileException)
        assertTrue(
            failure.message
                .orEmpty()
                .contains("synthetic-secret")
                .not(),
        )
    }

    @Test
    fun `KeePass preview explains incomplete credentials`() {
        val xml = """<KeePassFile><Root><Group><Entry>
            <String><Key>Title</Key><Value>example.test</Value></String>
            <String><Key>Password</Key><Value>p</Value></String>
            </Entry><Entry><String><Key>Title</Key><Value>example.test</Value></String>
            <String><Key>UserName</Key><Value>u</Value></String></Entry>
            </Group></Root></KeePassFile>"""
        val preview = ImportFileReader.parseContent("vault.xml", xml).getOrThrow()
        assertTrue(preview.passwords.isEmpty())
        assertEquals(
            listOf(SkipReason.MISSING_USERNAME, SkipReason.MISSING_PASSWORD),
            preview.skipped.map { it.reason },
        )
    }

    @Test
    fun `an unrecognised file fails with a clear error`() {
        val result = ImportFileReader.parseContent("mystery.txt", "just some prose, nothing structured")
        assertTrue(result.isFailure)
    }
}
