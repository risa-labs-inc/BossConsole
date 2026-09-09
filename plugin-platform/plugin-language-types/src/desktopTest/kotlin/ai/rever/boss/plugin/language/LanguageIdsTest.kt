package ai.rever.boss.plugin.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the table itself, independent of either consumer (`EditorLanguages`,
 * `EditorServiceImpl`). BossConsole#75's own "actual deliverable" is a test that a
 * mapping can't silently regress into a value nothing can render - the piece of that
 * enforceable in this repo, since BossEditor's lexer registry lives in another one.
 */
class LanguageIdsTest {
    @Test
    fun `every extension maps to a non-blank language id`() {
        LanguageIds.extensions().forEach { (extension, language) ->
            assertTrue(language.isNotBlank(), "extension '$extension' maps to a blank language id")
        }
    }

    @Test
    fun `shell extension aliases use the same language id`() {
        // sh/bash/zsh must resolve identically - this exact three-way split is the
        // disagreement that motivated #75 in the first place (this table said `bash`,
        // EditorServiceImpl's independent copy said `shell`, for the same three
        // extensions).
        assertEquals("bash", LanguageIds.forExtension("sh"))
        assertEquals("bash", LanguageIds.forExtension("bash"))
        assertEquals("bash", LanguageIds.forExtension("zsh"))
    }

    @Test
    fun `extension lookup is case-insensitive`() {
        assertEquals("kotlin", LanguageIds.forExtension("KT"))
        assertEquals("kotlin", LanguageIds.forExtension("Kt"))
    }

    @Test
    fun `an unknown extension is null, not a guess`() {
        assertEquals(null, LanguageIds.forExtension("notarealextension"))
    }

    @Test
    fun `detect prefers a file-name pattern over the extension`() {
        assertEquals("dockerfile", LanguageIds.detect("/srv/app/Dockerfile.dev"))
        assertEquals("properties", LanguageIds.detect("/srv/app/.env.local"))
    }

    @Test
    fun `detect falls back to text for anything unmatched`() {
        assertEquals(LanguageIds.TEXT, LanguageIds.detect("/a/plain"))
        assertEquals(LanguageIds.TEXT, LanguageIds.detect("/a/notes.unknownext"))
    }
}
