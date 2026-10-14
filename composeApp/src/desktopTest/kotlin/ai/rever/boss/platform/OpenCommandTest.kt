package ai.rever.boss.platform

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * What [openCommand] hands the OS.
 *
 * The Windows arm used to be `cmd /c start "" <path>`, which puts a shell between BOSS and the
 * file: the JDK quotes an argument only when it contains a space, so anything cmd treats as
 * punctuation survived into the command line. A downloaded `Q&A.pdf` in a space-free directory
 * did not open, and the text after `&` was run as a command. The path reaches this code from
 * the downloads panel's Open and from a file link clicked in terminal output, so its spelling
 * is not always the user's own.
 *
 * The Windows cases pass [EXPLORER] explicitly, so they read the same on a Windows runner (where
 * the default resolves from `%SystemRoot%`) as anywhere else.
 */
class OpenCommandTest {
    private fun windows(path: String) = openCommand("Windows 11", path, explorer = EXPLORER)

    @Test
    fun `windows opens through explorer, with no shell in the middle`() {
        val command = windows("""C:\Users\dev\Downloads\report.pdf""")

        assertContentEquals(arrayOf(EXPLORER, """C:\Users\dev\Downloads\report.pdf"""), command)
    }

    @Test
    fun `a windows path keeps its shell punctuation, in one argument`() {
        // No space anywhere, so the JDK would have passed this to cmd unquoted.
        val path = """C:\Users\dev\Downloads\R&D^notes%TEMP%.pdf"""

        val command = windows(path)!!

        assertContentEquals(arrayOf(EXPLORER, path), command)
        assertFalse(command.any { it == "cmd" || it == "start" }, command.joinToString(" "))
    }

    // The case that already worked under cmd, so a regression here would be the quiet one. The
    // double space also keeps openFile clear of revealInFileManager's documented double-space
    // defect, which comes from its single-string exec.
    @Test
    fun `a windows path with spaces, doubled ones included, is still one argument`() {
        val path = """C:\Users\dev\My  Docs\R&D report.pdf"""

        assertContentEquals(arrayOf(EXPLORER, path), windows(path))
    }

    @Test
    fun `a UNC path is passed through whole`() {
        val path = """\\server\share\R&D.pdf"""

        assertContentEquals(arrayOf(EXPLORER, path), windows(path))
    }

    // Safe because openFile passes an absolute path, so the switch-like name is never the start of
    // the operand - see openCommand's precondition.
    @Test
    fun `a file named like an explorer switch stays part of its path`() {
        val path = """C:\Users\dev\Downloads\-n,select.pdf"""

        assertContentEquals(arrayOf(EXPLORER, path), windows(path))
    }

    // Every case above passes EXPLORER explicitly, so none of them would notice the production
    // default going back to a bare "explorer.exe". This one pins the default openFile really uses.
    // It needs a real SystemRoot, so it runs on the Windows CI runner and is skipped elsewhere.
    @Test
    fun `by default windows launches the explorer under SystemRoot`() {
        assumeTrue(
            System
                .getProperty("os.name")
                .orEmpty()
                .lowercase()
                .contains("windows"),
            "needs Windows",
        )
        val systemRoot = System.getenv("SystemRoot")
        assumeTrue(systemRoot != null, "SystemRoot is unset")
        val explorer = """${checkNotNull(systemRoot).trimEnd('\\')}\explorer.exe"""
        assumeTrue(File(explorer).isFile, "no explorer.exe under SystemRoot on this machine")
        val path = """C:\Users\dev\Downloads\report.pdf"""

        assertContentEquals(arrayOf(explorer, path), openCommand("Windows 11", path))
    }

    @Test
    fun `macOS and linux are unchanged`() {
        val mac = "/Users/dev/Downloads/R&D.pdf"
        val linux = "/home/dev/R&D.pdf"

        assertContentEquals(arrayOf("open", mac), openCommand("Mac OS X", mac))
        assertContentEquals(arrayOf("xdg-open", linux), openCommand("Linux", linux))
    }

    @Test
    fun `an OS with no launcher gets no command rather than a guess`() {
        assertNull(openCommand("SunOS", "/export/home/dev/report.pdf"))
    }

    private companion object {
        const val EXPLORER = """C:\Windows\explorer.exe"""
    }
}
