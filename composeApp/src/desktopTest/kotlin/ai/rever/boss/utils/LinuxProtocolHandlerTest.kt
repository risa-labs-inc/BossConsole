package ai.rever.boss.utils

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxProtocolHandlerTest {
    private val dir: File = Files.createTempDirectory("boss-apps").toFile()
    private val commands = mutableListOf<List<String>>()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** A fake xdg toolchain whose `query default` answers [holder]; every other command succeeds. */
    private fun tools(holder: String?): (List<String>) -> String? =
        { command ->
            commands += command
            if (command.take(3) == listOf("xdg-mime", "query", "default")) holder else ""
        }

    private fun register(
        holder: String?,
        osName: String = "Linux",
        launcher: String? = "/opt/boss/bin/BOSS",
    ) = LinuxProtocolHandler.register(osName, launcher, dir, tools(holder))

    private val entry get() = File(dir, LinuxProtocolHandler.DESKTOP_FILE_NAME)

    @Test
    fun `the entry hands boss links to the launcher and stays out of menus`() {
        val entry = checkNotNull(LinuxProtocolHandler.desktopEntryFor("/opt/boss/bin/BOSS"))
        assertTrue("Exec=/opt/boss/bin/BOSS %u" in entry.lines())
        assertTrue("MimeType=x-scheme-handler/boss;" in entry.lines())
        assertTrue("NoDisplay=true" in entry.lines())
        // Never a browser candidate: that is boss.desktop's job, and only on request.
        assertTrue(entry.lines().none { it.startsWith("Categories=") })
    }

    @Test
    fun `a launcher path with a space is quoted`() {
        assertEquals("\"/home/me/My Apps/BOSS\"", LinuxProtocolHandler.execQuoted("/home/me/My Apps/BOSS"))
    }

    @Test
    fun `a percent sign is doubled so it cannot start a field code`() {
        assertEquals("/opt/100%%/BOSS", LinuxProtocolHandler.execQuoted("/opt/100%/BOSS"))
        assertEquals("\"/opt/100%% sure/BOSS\"", LinuxProtocolHandler.execQuoted("/opt/100% sure/BOSS"))
    }

    @Test
    fun `a launcher path needing escapes or a second line is refused rather than half escaped`() {
        assertNull(LinuxProtocolHandler.desktopEntryFor("/opt/\$HOME/BOSS"))
        assertNull(LinuxProtocolHandler.desktopEntryFor("/opt/a\"b/BOSS"))
        assertNull(LinuxProtocolHandler.desktopEntryFor("/opt/a\nExec=evil/BOSS"))
        assertNull(LinuxProtocolHandler.desktopEntryFor("/opt/a\rb/BOSS"))
    }

    @Test
    fun `an unclaimed scheme is claimed with our own entry`() {
        assertEquals(LinuxProtocolHandler.Outcome.REGISTERED, register(holder = ""))
        assertTrue(entry.readText().contains("Exec=/opt/boss/bin/BOSS %u"))
        val claim = listOf("xdg-mime", "default", LinuxProtocolHandler.DESKTOP_FILE_NAME, "x-scheme-handler/boss")
        assertTrue(claim in commands)
    }

    @Test
    fun `a handler someone else set up is left alone`() {
        assertEquals(LinuxProtocolHandler.Outcome.HELD_ELSEWHERE, register(holder = "boss.desktop\n"))
        assertFalse(entry.exists())
        assertTrue(commands.none { it.getOrNull(1) == "default" })
    }

    @Test
    fun `our own current entry is not rewritten`() {
        register(holder = "")
        commands.clear()

        assertEquals(LinuxProtocolHandler.Outcome.UP_TO_DATE, register(holder = LinuxProtocolHandler.DESKTOP_FILE_NAME))
        assertEquals(1, commands.size)
    }

    @Test
    fun `our own entry is refreshed when the launcher moved`() {
        register(holder = "")

        val outcome = register(holder = LinuxProtocolHandler.DESKTOP_FILE_NAME, launcher = "/usr/lib/boss/bin/BOSS")

        assertEquals(LinuxProtocolHandler.Outcome.REGISTERED, outcome)
        assertTrue(entry.readText().contains("Exec=/usr/lib/boss/bin/BOSS %u"))
    }

    @Test
    fun `without the xdg tools our entry is written once, not on every launch`() {
        // Every command "fails", as on a machine with no xdg-utils installed.
        val absent: (List<String>) -> String? = { command ->
            commands += command
            null
        }

        fun launch() = LinuxProtocolHandler.register("Linux", "/opt/boss/bin/BOSS", dir, absent)

        assertEquals(LinuxProtocolHandler.Outcome.REGISTERED, launch())
        val written = entry.lastModified()
        commands.clear()

        assertEquals(LinuxProtocolHandler.Outcome.UP_TO_DATE, launch())
        assertEquals(1, commands.size, "only the query may run: $commands")
        assertEquals(written, entry.lastModified())
    }

    @Test
    fun `nothing is touched off Linux or without a packaged launcher`() {
        assertEquals(LinuxProtocolHandler.Outcome.NOT_LINUX, register(holder = "", osName = "FreeBSD"))
        assertEquals(LinuxProtocolHandler.Outcome.NO_LAUNCHER, register(holder = "", launcher = null))
        assertEquals(LinuxProtocolHandler.Outcome.UNUSABLE_PATH, register(holder = "", launcher = "/opt/\$x/BOSS"))
        assertTrue(commands.isEmpty())
        assertFalse(entry.exists())
    }
}
