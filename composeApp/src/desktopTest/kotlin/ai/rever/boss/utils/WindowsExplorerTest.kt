package ai.rever.boss.utils

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [windowsExplorer] names Windows' own Explorer by full path, so `CreateProcess`'s search order -
 * the application's directory and the current directory come before `System32` - cannot pick up a
 * same-named binary instead. It falls back to the bare name whenever the full path is not safe to
 * use.
 */
class WindowsExplorerTest {
    private val present = setOf("""C:\Windows\explorer.exe""", """\\host\share\Windows\explorer.exe""")

    private fun resolve(systemRoot: String?) = windowsExplorer(systemRoot) { it in present }

    @Test
    fun `explorer resolves under SystemRoot`() {
        assertEquals("""C:\Windows\explorer.exe""", resolve("""C:\Windows"""))
        assertEquals("""C:\Windows\explorer.exe""", resolve("""C:\Windows\"""))
        assertEquals("""\\host\share\Windows\explorer.exe""", resolve("""\\host\share\Windows"""))
    }

    @Test
    fun `an unusable SystemRoot falls back to the bare name`() {
        assertEquals("explorer.exe", resolve(null))
        assertEquals("explorer.exe", resolve(""))
        // Each of these is rejected on its shape alone, so the file is reported present: a
        // fallback here must come from the SystemRoot check, not from a missing explorer.
        // Relative, or drive-relative: resolved against a directory nobody chose.
        assertEquals("explorer.exe", windowsExplorer("""Windows""") { true })
        assertEquals("explorer.exe", windowsExplorer("""C:Windows""") { true })
        // Whitespace would split the executable in revealInFileManager's single-string exec.
        assertEquals("explorer.exe", windowsExplorer("""C:\My Windows""") { true })
    }

    @Test
    fun `a SystemRoot without explorer falls back to the bare name`() {
        assertEquals("explorer.exe", resolve("""D:\Elsewhere"""))
    }
}
