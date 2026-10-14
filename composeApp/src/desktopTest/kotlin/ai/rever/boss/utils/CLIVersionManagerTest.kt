package ai.rever.boss.utils

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Header parsing for the installed CLI script version.
 *
 * The regression this pins: the extractor must keep the pre-release suffix so the installed
 * version compares equal to `AppVersion.CURRENT.toString()` on pre-release builds. Capturing only
 * `major.minor.patch` made `needsCLIUpdate()` fire on every launch of such a build.
 */
class CLIVersionManagerTest {
    @Test
    fun `extracts a stable version from each header style`() {
        assertEquals("8.13.4", cliVersionFromScriptLine("# Version: 8.13.4"))
        assertEquals("8.13.4", cliVersionFromScriptLine("REM Version: 8.13.4"))
        assertEquals("8.13.4", cliVersionFromScriptLine("    Version: 8.13.4"))
    }

    @Test
    fun `keeps the full pre-release suffix`() {
        assertEquals("9.5.21-alpha.1", cliVersionFromScriptLine("# Version: 9.5.21-alpha.1"))
        assertEquals("9.5.21-beta.2", cliVersionFromScriptLine("REM Version: 9.5.21-beta.2"))
        assertEquals("1.2.3-rc.10", cliVersionFromScriptLine("Version: 1.2.3-rc.10"))
    }

    @Test
    fun `round-trips the versions the scripts actually stamp`() {
        // The generated CLI scripts write `# Version: <app.version>`; extraction must return exactly
        // <app.version> for both stable and pre-release, or the equality check in isCLIVersionCurrent
        // can never hold.
        for (version in listOf("9.5.21", "9.5.21-alpha.1", "10.0.0-rc.3")) {
            assertEquals(version, cliVersionFromScriptLine("# Version: $version"))
        }
    }

    @Test
    fun `returns null when the line has no version header`() {
        assertNull(cliVersionFromScriptLine("#!/usr/bin/env bash"))
        assertNull(cliVersionFromScriptLine("echo 'BOSS CLI'"))
        assertNull(cliVersionFromScriptLine("if (\$PSVersionTable.PSVersion -ge [Version]\"7.3\")"))
    }
}
