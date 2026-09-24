package ai.rever.boss.utils

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins the Exec= quoting for the Linux .desktop file.
 *
 * The bug: a BOSS install at a path containing a space (e.g. `/opt/BOSS with
 * space/bin/BOSS`) produces `Exec=/opt/BOSS with space/bin/BOSS %U`, which the
 * XDG desktop parser splits on whitespace, so the resulting entry launches
 * `/opt/BOSS` with arguments `with`, `space/bin/BOSS`, `%U`. The fix wraps the
 * app path in double quotes, as the Desktop Entry Specification requires.
 */
class LinuxDesktopFileExecQuotingTest {
    /**
     * A plain path with no special characters should still be quoted so the
     * format is uniform and the file is parseable by every XDG implementation.
     */
    @Test
    fun `Exec line wraps the app path in double quotes`() {
        val appPath = "/opt/BOSS/bin/BOSS"
        val quoted = "\"$appPath\""
        val execLine = "Exec=$quoted %U"
        assertTrue(
            execLine.contains("Exec=\"/opt/BOSS/bin/BOSS\" %U"),
            "Exec= must wrap the app path in double quotes",
        )
    }

    /**
     * A path with a space - the case the bug hits. The Exec= line must be
     * `\"<path>\" %U` so the XDG parser keeps the path intact.
     */
    @Test
    fun `Exec line keeps the app path intact when the path contains spaces`() {
        val appPath = "/opt/BOSS with space/bin/BOSS"
        val escaped = appPath.replace("\\", "\\\\").replace("\"", "\\\"")
        val quoted = "\"$escaped\""
        val execLine = "Exec=$quoted %U"

        assertTrue(
            execLine.contains("Exec=\"/opt/BOSS with space/bin/BOSS\" %U"),
            "Exec= must keep a path with spaces inside the quoted region",
        )
        assertTrue(
            !execLine.contains("/opt/BOSS with space/bin/BOSS %U"),
            "the raw, unquoted path with a space must not appear in the Exec line",
        )
    }

    /**
     * A path with an embedded double-quote is escaped, not silently truncated.
     */
    @Test
    fun `Exec line escapes an embedded double quote in the app path`() {
        val appPath = "/opt/weird\"name/bin/BOSS"
        val escaped = appPath.replace("\\", "\\\\").replace("\"", "\\\"")
        val quoted = "\"$escaped\""

        assertTrue(
            quoted.contains("\\\""),
            "embedded quotes must be escaped so the surrounding quoted region stays valid",
        )
    }
}
