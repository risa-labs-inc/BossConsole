package ai.rever.boss.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the user-PATH merge contract (#1058): the CLI installer must append
 * the bin directory to the USER-scope PATH exactly once, never duplicate it,
 * and never inherit entries from the system-scope view the old
 * `setx PATH "%bin%;%PATH%"` wrote wholesale into HKCU.
 */
class CliInstallerUserPathMergeTest {
    @Test
    fun `appends the bin dir once to an existing user path`() {
        val existingUserPath = "C:\\Users\\dev\\tools\\bin;C:\\Users\\dev\\scoop\\shims"
        val binDir = "C:\\Users\\dev\\.boss\\bin"
        val merged = CLIInstaller.mergeUserPath(existingUserPath, binDir)
        assertEquals(
            "C:\\Users\\dev\\tools\\bin;C:\\Users\\dev\\scoop\\shims;C:\\Users\\dev\\.boss\\bin",
            merged,
        )
    }

    @Test
    fun `does not duplicate an entry already present`() {
        val existing = "C:\\Users\\dev\\tools;C:\\Users\\dev\\.boss\\bin"
        val merged = CLIInstaller.mergeUserPath(existing, "C:\\Users\\dev\\.boss\\bin")
        assertEquals(existing, merged, "an already-present bin dir must leave the PATH untouched")
    }

    @Test
    fun `duplicate detection is case-insensitive and trailing-slash tolerant`() {
        val existing = "c:\\users\\dev\\.boss\\bin\\"
        val merged = CLIInstaller.mergeUserPath(existing, "C:\\Users\\dev\\.boss\\bin")
        assertEquals(existing, merged, "Windows paths differ only by case/slash must not duplicate")
    }

    @Test
    fun `an empty user path becomes just the bin dir`() {
        assertEquals("C:\\Users\\dev\\.boss\\bin", CLIInstaller.mergeUserPath("", "C:\\Users\\dev\\.boss\\bin"))
    }

    @Test
    fun `a system-scope-only PATH passed by mistake is not merged entry-wise`() {
        // The regression the old code made: %PATH% (system+user merged view)
        // was written into user scope. The merge function must not be used on
        // that view - it appends, never unions - and must never reorder or
        // drop existing entries.
        val existing = "C:\\Windows\\System32;C:\\Windows;C:\\Users\\dev\\.boss\\bin"
        val merged = CLIInstaller.mergeUserPath(existing, "C:\\Users\\dev\\.boss\\bin")
        assertEquals(existing, merged)
        assertFalse(merged.startsWith(";"))
        assertTrue(merged.endsWith(".boss\\bin"))
    }
}
