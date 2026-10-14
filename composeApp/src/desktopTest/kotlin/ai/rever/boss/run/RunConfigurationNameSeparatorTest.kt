package ai.rever.boss.run

import ai.rever.boss.plugin.run.Language
import ai.rever.boss.plugin.run.RunConfiguration
import ai.rever.boss.plugin.run.RunConfigurationType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks down the separator handling in run-configuration name disambiguation.
 *
 * A stored [RunConfiguration.filePath] is an OS-native absolute path, because
 * DesktopMainFunctionDetector assigns `file.absolutePath`. On Windows that is
 * backslash-separated, so neither disambiguation pass may assume a forward slash.
 *
 * Both path shapes are exercised on every CI leg: these are pure string functions
 * with no filesystem access, so the Windows expectations are just as meaningful on
 * a Linux runner as on a Windows one.
 */
class RunConfigurationNameSeparatorTest {
    private fun config(
        name: String,
        filePath: String,
    ) = RunConfiguration(
        id = filePath,
        name = name,
        type = RunConfigurationType.MAIN_FUNCTION,
        filePath = filePath,
        lineNumber = 1,
        language = Language.KOTLIN,
        command = "",
        workingDirectory = "",
    )

    @Test
    fun `windows paths disambiguate two same-named configurations`() {
        val project = """C:\Users\dev\myproject"""
        val configs =
            listOf(
                config("main (Main.kt [myproject])", """C:\Users\dev\myproject\app\Main.kt"""),
                config("main (Main.kt [myproject])", """C:\Users\dev\myproject\lib\Main.kt"""),
            )

        val names = RunConfigurationManager.makeNamesUnique(configs, project).map { it.name }

        assertEquals(
            2,
            names.toSet().size,
            "Disambiguation is the whole purpose of makeNamesUnique, but on Windows paths " +
                "both entries stayed identical: $names",
        )
        assertTrue(names.any { it.contains("app/Main.kt") }, "Expected the app parent in $names")
        assertTrue(names.any { it.contains("lib/Main.kt") }, "Expected the lib parent in $names")
    }

    @Test
    fun `windows label carries the project name, not the absolute project path`() {
        val project = """C:\Users\dev\myproject"""
        val configs =
            listOf(
                config("main (Main.kt)", """C:\Users\dev\myproject\app\Main.kt"""),
                config("main (Main.kt)", """C:\Users\dev\myproject\lib\Main.kt"""),
            )

        val names = RunConfigurationManager.makeNamesUnique(configs, project).map { it.name }

        names.forEach { name ->
            assertFalse(
                name.contains("""C:\Users\dev"""),
                "A run-configuration label must not embed the absolute project path: $name",
            )
        }
        assertTrue(names.all { it.contains("[myproject]") }, "Expected the project name in $names")
    }

    /**
     * `removePrefix` is exact, so a projectPath differing from filePath in drive-letter case does
     * not match and the relativisation is a no-op. `takeLast(2)` of the absolute path still yields
     * a parent/file label, so the fallback cannot leak the full path either.
     */
    @Test
    fun `a project path that is not an exact prefix still cannot leak the absolute path`() {
        val project = """c:\Users\dev\myproject"""
        val configs =
            listOf(
                config("main (Main.kt)", """C:\Users\dev\myproject\app\Main.kt"""),
                config("main (Main.kt)", """C:\Users\dev\myproject\lib\Main.kt"""),
            )

        val names = RunConfigurationManager.makeNamesUnique(configs, project).map { it.name }

        assertEquals(listOf("main (app/Main.kt [myproject])", "main (lib/Main.kt [myproject])"), names)
    }

    @Test
    fun `posix paths keep their existing disambiguation`() {
        val project = "/home/dev/myproject"
        val configs =
            listOf(
                config("main (Main.kt [myproject])", "/home/dev/myproject/app/Main.kt"),
                config("main (Main.kt [myproject])", "/home/dev/myproject/lib/Main.kt"),
            )

        val names = RunConfigurationManager.makeNamesUnique(configs, project).map { it.name }

        assertEquals(listOf("main (app/Main.kt [myproject])", "main (lib/Main.kt [myproject])"), names)
    }

    @Test
    fun `a single configuration is never renamed`() {
        val project = """C:\Users\dev\myproject"""
        val configs = listOf(config("main (Main.kt)", """C:\Users\dev\myproject\app\Main.kt"""))

        assertEquals(configs, RunConfigurationManager.makeNamesUnique(configs, project))
    }

    /**
     * A file directly in the project root has no parent segment to disambiguate with, so its name
     * must be left alone. The empty-segment filter is what makes that hold: the relative path keeps
     * its leading separator, and without the filter it splits to a leading empty segment, clears
     * the `size >= 2` guard on it and produces a label of "/Main.kt".
     */
    @Test
    fun `a windows file at the project root keeps its name`() {
        val project = """C:\Users\dev\myproject"""
        val configs =
            listOf(
                config("main (Main.kt [myproject])", """C:\Users\dev\myproject\Main.kt"""),
                config("main (Main.kt [myproject])", """C:\Users\dev\myproject\app\Main.kt"""),
            )

        val names = RunConfigurationManager.makeNamesUnique(configs, project).map { it.name }

        assertEquals(listOf("main (Main.kt [myproject])", "main (app/Main.kt [myproject])"), names)
    }

    @Test
    fun `a posix file at the project root keeps its name`() {
        val project = "/home/dev/myproject"
        val configs =
            listOf(
                config("main (Main.kt [myproject])", "/home/dev/myproject/Main.kt"),
                config("main (Main.kt [myproject])", "/home/dev/myproject/app/Main.kt"),
            )

        val names = RunConfigurationManager.makeNamesUnique(configs, project).map { it.name }

        assertEquals(listOf("main (Main.kt [myproject])", "main (app/Main.kt [myproject])"), names)
    }

    /** extractFileName and the split both accept either separator, so a mixed path works too. */
    @Test
    fun `mixed separators are handled`() {
        val project = """C:/Users\dev/myproject"""
        val configs =
            listOf(
                config("main (Main.kt)", """C:/Users\dev/myproject\app/Main.kt"""),
                config("main (Main.kt)", """C:/Users\dev/myproject/lib\Main.kt"""),
            )

        val names = RunConfigurationManager.makeNamesUnique(configs, project).map { it.name }

        assertEquals(listOf("main (app/Main.kt [myproject])", "main (lib/Main.kt [myproject])"), names)
    }

    /** A trailing separator on the project path must still yield the project name. */
    @Test
    fun `a project path with a trailing separator still yields the project name`() {
        val project = """C:\Users\dev\myproject\"""
        val configs =
            listOf(
                config("main (Main.kt)", """C:\Users\dev\myproject\app\Main.kt"""),
                config("main (Main.kt)", """C:\Users\dev\myproject\lib\Main.kt"""),
            )

        val names = RunConfigurationManager.makeNamesUnique(configs, project).map { it.name }

        assertEquals(listOf("main (app/Main.kt [myproject])", "main (lib/Main.kt [myproject])"), names)
    }

    /**
     * A doubled interior separator reaches these functions through a hand-edited
     * run-configurations.json. Without the empty-segment filter takeLast(2) picks up the empty
     * segment and the label becomes "/Main.kt".
     */
    @Test
    fun `a doubled separator in a scanned path does not produce an empty segment`() {
        val project = """C:\p"""
        val configs =
            listOf(
                config("main (Main.kt)", """C:\p\app\\Main.kt"""),
                config("main (Main.kt)", """C:\p\lib\Main.kt"""),
            )

        val names = RunConfigurationManager.makeNamesUnique(configs, project).map { it.name }

        assertEquals(listOf("main (app/Main.kt [p])", "main (lib/Main.kt [p])"), names)
    }

    @Test
    fun `stored windows names disambiguate on reload`() {
        val configs =
            listOf(
                config("main (Main.kt)", """C:\Users\dev\myproject\app\Main.kt"""),
                config("main (Main.kt)", """C:\Users\dev\myproject\lib\Main.kt"""),
            )

        val names = RunConfigurationManager.makeStoredNamesUnique(configs).map { it.name }

        assertEquals(listOf("main (app/Main.kt)", "main (lib/Main.kt)"), names)
    }

    @Test
    fun `stored posix names keep their existing disambiguation`() {
        val configs =
            listOf(
                config("main (Main.kt)", "/home/dev/myproject/app/Main.kt"),
                config("main (Main.kt)", "/home/dev/myproject/lib/Main.kt"),
            )

        val names = RunConfigurationManager.makeStoredNamesUnique(configs).map { it.name }

        assertEquals(listOf("main (app/Main.kt)", "main (lib/Main.kt)"), names)
    }

    /**
     * Stored names are toShortNameWithProject output, so they carry a bracketed project name. The
     * trailing-group regex would consume that bracket, so makeStoredNamesUnique puts it back for
     * parity with makeNamesUnique. loadSettingsSync assigns these names to _currentSettings and
     * the next saveSettings persists them, so dropping it would not be display-only.
     */
    @Test
    fun `stored windows names keep the bracketed project suffix`() {
        val configs =
            listOf(
                config("main (Main.kt [myproject])", """C:\Users\dev\myproject\app\Main.kt"""),
                config("main (Main.kt [myproject])", """C:\Users\dev\myproject\lib\Main.kt"""),
            )

        val names = RunConfigurationManager.makeStoredNamesUnique(configs).map { it.name }

        assertEquals(listOf("main (app/Main.kt [myproject])", "main (lib/Main.kt [myproject])"), names)
    }

    @Test
    fun `stored posix names keep the bracketed project suffix`() {
        val configs =
            listOf(
                config("main (Main.kt [myproject])", "/home/dev/myproject/app/Main.kt"),
                config("main (Main.kt [myproject])", "/home/dev/myproject/lib/Main.kt"),
            )

        val names = RunConfigurationManager.makeStoredNamesUnique(configs).map { it.name }

        assertEquals(listOf("main (app/Main.kt [myproject])", "main (lib/Main.kt [myproject])"), names)
    }

    @Test
    fun `a doubled separator in a stored path does not produce an empty segment`() {
        val configs =
            listOf(
                config("main (Main.kt)", """C:\p\app\\Main.kt"""),
                config("main (Main.kt)", """C:\p\lib\Main.kt"""),
            )

        val names = RunConfigurationManager.makeStoredNamesUnique(configs).map { it.name }

        assertEquals(listOf("main (app/Main.kt)", "main (lib/Main.kt)"), names)
    }

    /**
     * makeStoredNamesUnique runs on every launch and loadSettingsSync persists its output, so a
     * non-idempotent rewrite would make labels drift on each start.
     */
    @Test
    fun `stored name rewriting is idempotent`() {
        val configs =
            listOf(
                config("main (Main.kt [myproject])", """C:\Users\dev\myproject\app\Main.kt"""),
                config("main (Main.kt [myproject])", """C:\Users\dev\myproject\lib\Main.kt"""),
            )

        val once = RunConfigurationManager.makeStoredNamesUnique(configs)
        val twice = RunConfigurationManager.makeStoredNamesUnique(once)

        assertEquals(once.map { it.name }, twice.map { it.name })
    }
}
