package ai.rever.boss.components.plugin.providers

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the language mapping this provider reports to plugins and to the
 * `editor_detect_language` MCP tool.
 *
 * Two things were wrong and both were silent. Extension-less files could never be
 * identified — `Dockerfile` and `Makefile` have no extension for
 * `substringAfterLast('.')` to find — and the extension was read from the whole
 * path, so a dot in a parent directory leaked into the result.
 *
 * The mapping is duplicated in the editor-tab plugin's `LanguageDetection`, which
 * is what actually picks the lexer. These assertions are the cheap way to notice
 * the two drifting apart, since a mismatch surfaces only as the tool naming one
 * language while the editor highlights another.
 */
class EditorLanguageDetectionTest {

    private val provider = EditorContentProviderImpl()

    @Test
    fun `extension-less container and make files are identified`() {
        assertEquals("dockerfile", provider.detectLanguage("/srv/app/Dockerfile"))
        assertEquals("dockerfile", provider.detectLanguage("/srv/app/Containerfile"))
        assertEquals("makefile", provider.detectLanguage("/srv/app/Makefile"))
        assertEquals("makefile", provider.detectLanguage("/srv/app/GNUmakefile"))
    }

    @Test
    fun `a filename pattern beats the extension`() {
        // `.dev` must not win over the Dockerfile name.
        assertEquals("dockerfile", provider.detectLanguage("/srv/app/Dockerfile.dev"))
        assertEquals("dockerfile", provider.detectLanguage("/srv/app/Dockerfile.prod"))
        assertEquals("properties", provider.detectLanguage("/srv/app/.env.local"))
    }

    @Test
    fun `the dockerfile extension form is identified`() {
        assertEquals("dockerfile", provider.detectLanguage("/srv/app/build.dockerfile"))
    }

    @Test
    fun `a dot in a parent directory does not leak into the extension`() {
        assertEquals("makefile", provider.detectLanguage("/srv/v1.2/Makefile"))
        assertEquals("yaml", provider.detectLanguage("/srv/v1.2/values.yaml"))
        assertEquals("text", provider.detectLanguage("/srv/v1.2/README"))
    }

    @Test
    fun `windows separators are handled`() {
        assertEquals("dockerfile", provider.detectLanguage("C:\\src\\app\\Dockerfile"))
    }

    @Test
    fun `previously working mappings are unchanged`() {
        assertEquals("kotlin", provider.detectLanguage("/a/Main.kt"))
        assertEquals("yaml", provider.detectLanguage("/a/Chart.yaml"))
        assertEquals("json", provider.detectLanguage("/a/package.json"))
        assertEquals("markdown", provider.detectLanguage("/a/README.md"))
        assertEquals("bash", provider.detectLanguage("/a/run.sh"))
        assertEquals("text", provider.detectLanguage("/a/notes.unknownext"))
        assertEquals("text", provider.detectLanguage("/a/plain"))
    }
}
