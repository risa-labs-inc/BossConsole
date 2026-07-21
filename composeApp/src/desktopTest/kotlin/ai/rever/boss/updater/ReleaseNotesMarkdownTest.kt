package ai.rever.boss.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReleaseNotesMarkdownTest {

    // The shape our release workflow actually generates (see the 9.2.21 notes).
    private val realNotes = """
        # 🚀 BOSS 9.2.21

        ## 📦 Downloads

        | Platform | Architecture | Package |
        |----------|--------------|---------|
        | **macOS** | Universal (Apple Silicon + Intel via Rosetta) | `BOSS-9.2.21-Universal.dmg` |
        | **Windows** | x64 | `BOSS-9.2.21.msi` |
        | **Windows** | ARM64 | `BOSS-9.2.21-arm64.msi` |
    """.trimIndent()

    @Test
    fun `parses generated release notes into heading and table blocks`() {
        val blocks = parseReleaseNotes(realNotes)
        assertEquals(3, blocks.size)

        val h1 = blocks[0] as NotesBlock.Heading
        assertEquals(1, h1.level)
        assertEquals("🚀 BOSS 9.2.21", h1.text)

        val h2 = blocks[1] as NotesBlock.Heading
        assertEquals(2, h2.level)
        assertEquals("📦 Downloads", h2.text)

        val table = blocks[2] as NotesBlock.Table
        assertEquals(listOf("Platform", "Architecture", "Package"), table.header)
        assertEquals(3, table.rows.size)
        assertEquals(listOf("**macOS**", "Universal (Apple Silicon + Intel via Rosetta)", "`BOSS-9.2.21-Universal.dmg`"), table.rows[0])
    }

    @Test
    fun `parses lists, code fences, breaks, and paragraphs`() {
        val blocks = parseReleaseNotes(
            """
            Intro line one
            continues here.

            - first fix
            - second fix
              - nested detail
            1. step one

            ---

            ```
            some literal
            ```
            """.trimIndent()
        )
        assertEquals(
            listOf("Intro line one continues here."),
            blocks.filterIsInstance<NotesBlock.Paragraph>().map { it.text }
        )
        val items = blocks.filterIsInstance<NotesBlock.ListItem>()
        assertEquals(listOf("•" to 0, "•" to 0, "•" to 1, "1." to 0), items.map { it.marker to it.indent })
        assertTrue(blocks.any { it is NotesBlock.ThematicBreak })
        assertEquals("some literal", (blocks.first { it is NotesBlock.CodeBlock } as NotesBlock.CodeBlock).code)
    }

    @Test
    fun `inline markdown maps to styled spans`() {
        val styled = buildInlineMarkdown("**bold** and `code` and [site](https://example.com) plain")
        assertEquals("bold and code and site plain", styled.text)
        // bold + code spans present, plus the link's own styling
        assertTrue(styled.spanStyles.size >= 2)
        assertEquals(1, styled.getLinkAnnotations(0, styled.length).size)
    }

    @Test
    fun `plain text passes through untouched`() {
        val text = "snake_case stays, 3 * 4 too"
        assertEquals(text, buildInlineMarkdown(text).text)
    }

    @Test
    fun `unterminated fence still yields a code block`() {
        val blocks = parseReleaseNotes("```\ndangling")
        assertEquals(listOf(NotesBlock.CodeBlock("dangling")), blocks)
    }
}
