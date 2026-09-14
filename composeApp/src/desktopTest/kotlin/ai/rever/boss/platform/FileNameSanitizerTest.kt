package ai.rever.boss.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [FileNameSanitizer.sanitize] takes a name chosen by whatever served the download, so
 * every assertion here is about hostile or degenerate input rather than ordinary names.
 *
 * The two cases at the top are the ones that were wrong: a long extension threw out of
 * the download handler, and a name of only dots came back carrying the trailing dot the
 * sanitizer exists to remove.
 *
 * Stated so nobody assumes more coverage than there is: these assert the returned string
 * only. Whether Windows would accept it is not exercised anywhere, and cannot be from a
 * JVM test on another platform.
 */
class FileNameSanitizerTest {
    @Test
    fun `a name whose extension is longer than the limit does not throw`() {
        // "a." plus 300 characters gives an extension of 301. The limit is 255, so the
        // old budget arithmetic reached take(-46), which rejects a negative count.
        val result = FileNameSanitizer.sanitize("a." + "x".repeat(300))

        assertTrue(result.length <= 255, "result was ${result.length} characters")
        assertTrue(result.isNotBlank())
    }

    @Test
    fun `a name of only dots does not come back ending in a dot`() {
        // The fallback appended the extension computed in step 4, which for these inputs
        // is a lone ".", so it handed back "download." and undid the trailing dot
        // removal. A trailing dot is exactly what is invalid on Windows.
        for (input in listOf(".", "..", "...", " . ")) {
            val result = FileNameSanitizer.sanitize(input)
            assertFalse(result.endsWith("."), "sanitize($input) returned $result")
            assertTrue(result.isNotBlank(), "sanitize($input) returned blank")
        }
    }

    @Test
    fun `no input produces a name ending in a dot or a space`() {
        val inputs =
            listOf(
                "report.pdf",
                "CON",
                "CON.txt",
                "..",
                ".",
                "   ",
                "",
                "a." + "x".repeat(300),
                "x".repeat(300) + ".pdf",
                "file. ",
                "file .",
                "../../etc/passwd",
                "/etc/shadow",
            )
        for (input in inputs) {
            val result = FileNameSanitizer.sanitize(input)
            assertFalse(
                result.endsWith(".") || result.endsWith(" "),
                "sanitize($input) returned '$result'",
            )
        }
    }

    @Test
    fun `an extension that cannot fit is abandoned rather than shortened`() {
        // "a." plus 300 characters gives a 301-character "extension", which cannot
        // fit in the 255 limit, so the base name survives and the extension goes.
        assertEquals("a", FileNameSanitizer.sanitize("a." + "x".repeat(300)))
    }

    @Test
    fun `every result stays within the length limit`() {
        val inputs = listOf("x".repeat(300), "x".repeat(300) + ".pdf", "a." + "x".repeat(300))
        for (input in inputs) {
            assertTrue(FileNameSanitizer.sanitize(input).length <= 255)
        }
    }

    @Test
    fun `a long name keeps its extension when the extension fits`() {
        val result = FileNameSanitizer.sanitize("x".repeat(300) + ".pdf")

        assertTrue(result.endsWith(".pdf"), "expected the type to survive, got '$result'")
        assertEquals(255, result.length)
    }

    @Test
    fun `ordinary names are returned unchanged`() {
        assertEquals("report.pdf", FileNameSanitizer.sanitize("report.pdf"))
        assertEquals("my file (1).tar.gz", FileNameSanitizer.sanitize("my file (1).tar.gz"))
    }

    @Test
    fun `path traversal is reduced to the file name`() {
        assertEquals("passwd", FileNameSanitizer.sanitize("../../etc/passwd"))
        assertFalse(FileNameSanitizer.sanitize("/etc/shadow").contains("/"))
        assertFalse(FileNameSanitizer.sanitize("..\\..\\windows\\cmd.exe").contains("\\"))
    }

    @Test
    fun `windows device names are defused whatever their case or padding`() {
        // The check now runs on the segment before the FIRST dot, so this pins the bare
        // names in three cases and the trailing-space padding the top-level trim removes
        // before the check. Extension shapes are covered by the two tests below, which
        // exist because the last-dot version missed every multi-dot name.
        for (name in listOf("CON", "con", "Con", "PRN", "NUL", "COM1", "LPT9")) {
            assertEquals("_$name", FileNameSanitizer.sanitize(name), "sanitize($name)")
        }
        assertEquals("_CON", FileNameSanitizer.sanitize("CON "))
        assertEquals("_CON.txt", FileNameSanitizer.sanitize("CON.txt"))
    }

    @Test
    fun `a trailing dot does not hide a real extension from the truncation`() {
        // Before the trailing-dot trim, the extension of "a<252 x>.pdf..." is the lone dot
        // after the last one. Carrying that stale copy into the cut dropped the real ".pdf"
        // even though it fit, leaving a 253-character extensionless name.
        val result = FileNameSanitizer.sanitize("a" + "x".repeat(252) + ".pdf...")
        assertEquals("a" + "x".repeat(250) + ".pdf", result)
        assertEquals(255, result.length)
    }

    @Test
    fun `truncation cannot shrink a padded name onto a bare device name`() {
        // Step 4's own trailing-space trim now defuses this input before truncation
        // runs, so the cut-level defuse it was written for is exercised by the
        // tight-limit test below instead. The assertion still pins the end-to-end shape.
        assertEquals("_CON", FileNameSanitizer.sanitize("CON ." + "x".repeat(255)))
    }

    @Test
    fun `a redo that itself defuses lands exactly at the limit`() {
        // The only path where a defused cut survives into the returned value, and the
        // one where the 255 bound is exactly tight: the budget lands inside the run of
        // spaces, so both the 255 cut and the 254 redo trim onto "CON" and both defuse.
        val result = FileNameSanitizer.sanitize("CON  X." + "x".repeat(249))
        assertEquals("_CON ." + "x".repeat(249), result)
        assertEquals(255, result.length)
    }

    @Test
    fun `a device name with any number of extensions is still defused`() {
        // Win32 stops the device comparison at the FIRST dot, so NUL.txt and CON.tar.gz
        // are both the device. Comparing the segment before the LAST dot saw "CON.tar",
        // which is not reserved, and let the name through untouched.
        assertEquals("_CON.tar.gz", FileNameSanitizer.sanitize("CON.tar.gz"))
        assertEquals("_NUL.tar.gz", FileNameSanitizer.sanitize("NUL.tar.gz"))
        assertEquals("_CON.txt", FileNameSanitizer.sanitize("CON.txt"))
        assertEquals("CONSOLE.txt", FileNameSanitizer.sanitize("CONSOLE.txt"))
    }

    @Test
    fun `truncation cannot manufacture a device name out of one that was not`() {
        // "CONSOLE." followed by 251 characters is not a device: step 4 sees CONSOLE.
        // The first cut to the limit leaves "CON." and the rest - the console - so the
        // redo runs one character shorter and shortens the base to "CO." rather than
        // defusing it; the pinned shape rules out the defused form as well.
        val result = FileNameSanitizer.sanitize("CONSOLE." + "x".repeat(251))
        assertEquals("CO." + "x".repeat(251), result)
    }

    @Test
    fun `no input produces a name Windows would resolve to a device`() {
        val reserved =
            setOf("CON", "PRN", "AUX", "NUL") +
                (1..9).map { "COM$it" } + (1..9).map { "LPT$it" }
        val inputs =
            listOf(
                "CON",
                "con",
                "CON ",
                "CON.txt",
                "CON.tar.gz",
                "NUL.tar.gz",
                "CONSOLE." + "x".repeat(251),
                "CON ." + "x".repeat(255),
                "COM1.a.b",
                "lpt9.tar.gz",
                "aux.x.y.z",
            )
        for (input in inputs) {
            val result = FileNameSanitizer.sanitize(input)
            assertFalse(
                result.substringBefore('.').trimEnd(' ').uppercase() in reserved,
                "sanitize($input) returned '$result', which Windows resolves to a device",
            )
        }
    }

    @Test
    fun `a blank name becomes a usable default`() {
        assertEquals("download", FileNameSanitizer.sanitize(""))
        assertEquals("download", FileNameSanitizer.sanitize("   "))
    }

    @Test
    fun `control characters are dropped rather than replaced`() {
        // A bell character inside the name must vanish, not become an underscore, or
        // every name carrying one would gain a stray separator.
        assertEquals("report.pdf", FileNameSanitizer.sanitize("re\u0007port.pdf"))
    }

    @Test
    fun `executable detection is case insensitive and reads the final extension`() {
        assertTrue(FileNameSanitizer.isExecutableFile("setup.EXE"))
        assertTrue(FileNameSanitizer.isExecutableFile("invoice.pdf.exe"))
        assertTrue(FileNameSanitizer.isExecutableFile("script.Sh"))
        assertFalse(FileNameSanitizer.isExecutableFile("report.pdf"))
        assertFalse(FileNameSanitizer.isExecutableFile("noextension"))
    }
}
