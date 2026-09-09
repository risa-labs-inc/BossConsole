package ai.rever.boss.components.window_panel

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Path comparison for tab reuse.
 *
 * Clicking a file that is already open must focus that tab. It opened a second
 * one instead because the check was raw string equality and the two callers
 * spell the same path differently.
 */
class TabPathsTest {
    @Test
    fun `a doubled separator compares equal to a single one`(
        @TempDir dir: File,
    ) {
        val file = File(dir, "a.kt").also { it.writeText("x") }
        // What the git provider produced from a project path ending in "/".
        assertEquals(
            TabPaths.normalize(file.absolutePath),
            TabPaths.normalize("${dir.absolutePath}//a.kt"),
        )
    }

    @Test
    fun `a dot segment resolves to the same file`(
        @TempDir dir: File,
    ) {
        File(dir, "sub").mkdirs()
        val file = File(dir, "sub/a.kt").also { it.writeText("x") }
        assertEquals(
            TabPaths.normalize(file.absolutePath),
            TabPaths.normalize("${dir.absolutePath}/sub/./a.kt"),
        )
        assertEquals(
            TabPaths.normalize(file.absolutePath),
            TabPaths.normalize("${dir.absolutePath}/sub/../sub/a.kt"),
        )
    }

    @Test
    fun `a trailing separator does not change the identity`(
        @TempDir dir: File,
    ) {
        assertEquals(TabPaths.normalize(dir.absolutePath), TabPaths.normalize("${dir.absolutePath}/"))
    }

    @Test
    fun `different files stay different`(
        @TempDir dir: File,
    ) {
        File(dir, "a.kt").writeText("x")
        File(dir, "b.kt").writeText("y")
        assertNotEquals(
            TabPaths.normalize("${dir.absolutePath}/a.kt"),
            TabPaths.normalize("${dir.absolutePath}/b.kt"),
        )
    }

    @Test
    fun `an unresolvable path still compares consistently with itself`() {
        // The file need not exist - a tab can outlive its file.
        assertEquals(
            TabPaths.normalize("/nope/does/not/exist.kt"),
            TabPaths.normalize("/nope/does//not/exist.kt"),
        )
    }

    @Test
    fun `a blank path is blank, not the working directory`() {
        assertEquals("", TabPaths.normalize(""))
        assertEquals("", TabPaths.normalize("   "))
    }

    @Test
    fun `a backslash is a filename character on POSIX, not a separator`() {
        // Translating it unconditionally turned `a\bak.kt` into `a/bak.kt`, which can
        // canonicalise onto a DIFFERENT real file and focus the wrong tab.
        if (File.separatorChar == '\\') return // on Windows the translation is correct
        assertNotEquals(
            TabPaths.normalize("/x/a\\bak.kt"),
            TabPaths.normalize("/x/a/bak.kt"),
        )
    }

    @Test
    fun `lexical cleanup keeps a UNC host prefix while collapsing inner separators`() {
        // //server/share must not flatten to /server/share - two tabs on different
        // servers would compare equal. Exercised directly: on POSIX normalize() only
        // reaches the lexical path when canonicalPath throws.
        assertEquals("//server/share/x", TabPaths.lexicalClean("//server/share//x"))
        assertNotEquals(TabPaths.lexicalClean("//server/share"), TabPaths.lexicalClean("/server/share"))
    }

    @Test
    fun `on the windows branch a backslash is a separator and a UNC path keeps its host`() {
        // The separator is passed explicitly: on the Linux/macOS runners the
        // default ('/') makes this branch unreachable.
        assertEquals("/server/share/x", TabPaths.lexicalClean("\\server/share/x", '\\'))
        assertEquals("//server/share/x", TabPaths.lexicalClean("\\\\server\\share\\x", '\\'))
        assertNotEquals(
            TabPaths.lexicalClean("\\\\server\\share", '\\'),
            TabPaths.lexicalClean("\\server\\share", '\\'),
        )
    }

    @Test
    fun `on the posix branch a backslash stays a filename character`() {
        assertEquals("/x/a\\bak.kt", TabPaths.lexicalClean("/x/a\\bak.kt", '/'))
    }
}
