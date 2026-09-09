package ai.rever.boss.git

import ai.rever.boss.plugin.api.DiffLineKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Fixture-driven tests for [UnifiedDiffParser]. The fixtures are hand-written
 * captures of real `git diff` output shapes (multi-file, rename, binary,
 * created file, missing hunk counts, no-newline marker).
 */
class UnifiedDiffParserTest {
    private val singleFileFixture =
        """
        diff --git a/src/A.kt b/src/A.kt
        index 1111111..2222222 100644
        --- a/src/A.kt
        +++ b/src/A.kt
        @@ -10,7 +10,8 @@ class A {
             val x = 1
        -val y = 2
        +val y = 3
        +val z = 4
             fun f() {}

        -    val w = 9
        +    val w = 10
             fun g() {}
        """.trimIndent()

    @Test
    fun `single file parses path counts and hunk line numbers`() {
        val files = UnifiedDiffParser.parse(singleFileFixture)
        assertEquals(1, files.size)
        val file = files.single()
        assertEquals("src/A.kt", file.path)
        assertNull(file.oldPath)
        assertFalse(file.isBinary)
        assertEquals(3, file.additions)
        assertEquals(2, file.deletions)
        assertEquals(1, file.hunks.size)

        val hunk = file.hunks.single()
        assertEquals(10, hunk.oldStart)
        assertEquals(7, hunk.oldLines)
        assertEquals(10, hunk.newStart)
        assertEquals(8, hunk.newLines)
        assertEquals(9, hunk.lines.size)

        // First context line keeps both line numbers.
        val first = hunk.lines.first()
        assertEquals(DiffLineKind.CONTEXT, first.kind)
        assertEquals(10, first.oldLine)
        assertEquals(10, first.newLine)

        val removed = hunk.lines.first { it.kind == DiffLineKind.REMOVED }
        assertEquals("val y = 2", removed.text)
        assertEquals(11, removed.oldLine)
        assertNull(removed.newLine)

        val added = hunk.lines.first { it.kind == DiffLineKind.ADDED }
        assertEquals("val y = 3", added.text)
        assertEquals(11, added.newLine)
        assertNull(added.oldLine)

        // Last context line: old 15 / new 16.
        val last = hunk.lines.last()
        assertEquals(15, last.oldLine)
        assertEquals(16, last.newLine)
    }

    @Test
    fun `blank context line is kept with both line numbers`() {
        val hunk =
            UnifiedDiffParser
                .parse(singleFileFixture)
                .single()
                .hunks
                .single()
        val blank = hunk.lines.first { it.text.isEmpty() }
        assertEquals(DiffLineKind.CONTEXT, blank.kind)
        assertEquals(13, blank.oldLine)
        assertEquals(14, blank.newLine)
    }

    @Test
    fun `multi file stream parses both files in order`() {
        val input =
            singleFileFixture +
                "\n" +
                """
                diff --git a/notes.txt b/notes.txt
                index 3333333..4444444 100644
                --- a/notes.txt
                +++ b/notes.txt
                @@ -1,2 +1,2 @@
                 keep
                -drop
                +take
                """.trimIndent()
        val files = UnifiedDiffParser.parse(input)
        assertEquals(2, files.size)
        assertEquals("src/A.kt", files[0].path)
        assertEquals("notes.txt", files[1].path)
        assertEquals(1, files[1].additions)
        assertEquals(1, files[1].deletions)
    }

    @Test
    fun `rename is captured with old and new path and no hunks`() {
        val input =
            """
            diff --git a/old.txt b/new.txt
            rename from old.txt
            rename to new.txt
            """.trimIndent()
        val file = UnifiedDiffParser.parse(input).single()
        assertEquals("new.txt", file.path)
        assertEquals("old.txt", file.oldPath)
        assertTrue(file.hunks.isEmpty())
    }

    @Test
    fun `binary file is flagged without hunks`() {
        val input =
            """
            diff --git a/img.png b/img.png
            index 5555555..6666666 100644
            Binary files a/img.png and b/img.png differ
            """.trimIndent()
        val file = UnifiedDiffParser.parse(input).single()
        assertEquals("img.png", file.path)
        assertTrue(file.isBinary)
        assertTrue(file.hunks.isEmpty())
        assertEquals(0, file.additions)
        assertEquals(0, file.deletions)
    }

    @Test
    fun `header without explicit old count defaults to one line`() {
        val input =
            """
            diff --git a/b.txt b/b.txt
            index 7777777..8888888 100644
            --- a/b.txt
            +++ b/b.txt
            @@ -12 +12,2 @@
            -old12
            +new12
            """.trimIndent()
        val hunk =
            UnifiedDiffParser
                .parse(input)
                .single()
                .hunks
                .single()
        assertEquals(12, hunk.oldStart)
        assertEquals(1, hunk.oldLines)
        assertEquals(12, hunk.newStart)
        assertEquals(2, hunk.newLines)
        assertEquals(2, hunk.lines.size)
        assertEquals(12, hunk.lines.first().oldLine)
        assertEquals(12, hunk.lines.last().newLine)
    }

    @Test
    fun `created file numbers lines from one and skips the no-newline marker`() {
        val input =
            """
            diff --git a/c.txt b/c.txt
            new file mode 100644
            index 0000000..9999999
            --- /dev/null
            +++ b/c.txt
            @@ -0,0 +1,3 @@
            +line1
            +line2
            +line3
            \ No newline at end of file
            """.trimIndent()
        val file = UnifiedDiffParser.parse(input).single()
        assertEquals("c.txt", file.path)
        assertEquals(3, file.additions)
        assertEquals(0, file.deletions)
        val lines = file.hunks.single().lines
        assertEquals(3, lines.size)
        assertEquals(listOf(1, 2, 3), lines.map { it.newLine })
        assertTrue(lines.all { it.kind == DiffLineKind.ADDED })
    }

    @Test
    fun `empty input yields no files`() {
        assertTrue(UnifiedDiffParser.parse("").isEmpty())
        assertTrue(UnifiedDiffParser.parse("\n\n").isEmpty())
    }

    @Test
    fun `raw unified text is preserved per file`() {
        val files = UnifiedDiffParser.parse(singleFileFixture)
        val raw = files.single().rawUnified
        assertTrue(raw.startsWith("diff --git a/src/A.kt b/src/A.kt"))
        assertTrue(raw.contains("@@ -10,7 +10,8 @@"))
        assertTrue(raw.contains("+val z = 4"))
    }

    // ---- regressions found in independent review ----

    @Test
    fun `a quoted non-ascii path is decoded as utf-8, not per-escape chars`() {
        // Real `git diff` output with core.quotepath on (the default).
        val input =
            "diff --git \"a/caf\\303\\251.txt\" \"b/caf\\303\\251.txt\"\n" +
                "index ce01362..5ea2ed4 100644\n" +
                "--- \"a/caf\\303\\251.txt\"\n" +
                "+++ \"b/caf\\303\\251.txt\"\n" +
                "@@ -1 +1 @@\n" +
                "-hello\n" +
                "+changed\n"
        val file = UnifiedDiffParser.parse(input).single()
        assertEquals("café.txt", file.path)
    }

    @Test
    fun `a quoted path does not throw`() {
        val input = "diff --git \"a/\\346\\227\\245.kt\" \"b/\\346\\227\\245.kt\"\n@@ -1 +1 @@\n-a\n+b\n"
        assertEquals(1, UnifiedDiffParser.parse(input).size)
    }

    @Test
    fun `rawUnified stops at the file boundary`() {
        val input =
            "diff --git a/one.kt b/one.kt\n@@ -1 +1 @@\n-a\n+b\n" +
                "diff --git a/two.kt b/two.kt\n@@ -1 +1 @@\n-c\n+d\n"
        val files = UnifiedDiffParser.parse(input)
        assertEquals(2, files.size)
        assertTrue(files[0].rawUnified.contains("one.kt"), files[0].rawUnified)
        assertTrue(!files[0].rawUnified.contains("two.kt"), "file 0 leaked file 1: ${files[0].rawUnified}")
    }

    @Test
    fun `a trailing newline does not append a phantom context line`() {
        // Process output always ends with a newline; fixtures using trimIndent() do not.
        val input = "diff --git a/a.kt b/a.kt\n@@ -1,2 +1,2 @@\n one\n-two\n+three\n"
        val hunk =
            UnifiedDiffParser
                .parse(input)
                .single()
                .hunks
                .single()
        assertEquals(3, hunk.lines.size, hunk.lines.map { it.text }.toString())
    }

    @Test
    fun `a merge commit's combined diff yields no hunks rather than wrong ones`() {
        val input =
            "diff --cc merged.kt\n" +
                "index 1111111,2222222..3333333\n" +
                "@@@ -1,2 -1,2 +1,3 @@@\n" +
                "++both\n" +
                "+ ours\n"
        val files = UnifiedDiffParser.parse("diff --git a/merged.kt b/merged.kt\n" + input.substringAfter("\n"))
        assertTrue(files.single().hunks.isEmpty(), "combined diff was parsed as unified: ${files.single().hunks}")
    }
}
