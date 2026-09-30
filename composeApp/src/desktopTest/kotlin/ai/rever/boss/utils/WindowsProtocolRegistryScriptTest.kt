package ai.rever.boss.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The script is pure, which lets non-Windows CI verify the one registry value that makes
 * `boss://` callbacks work. A malformed command registers successfully but launches nothing.
 */
class WindowsProtocolRegistryScriptTest {
    private val exe = """C:\Program Files\BOSS\BOSS.exe"""

    @Test
    fun `script identifies itself as a registry import file`() {
        val script = WindowsProtocolRegistryScript.buildScript(exe)

        assertTrue(
            script.startsWith("Windows Registry Editor Version 5.00"),
            "the script is missing the registry-import header",
        )
        assertTrue(
            script.contains("[$PROTOCOL_KEY]"),
            "the script writes a different protocol root than cleanup removes",
        )
        assertTrue(script.contains("\"URL Protocol\"=\"\""), "the URL Protocol marker is missing")
    }

    @Test
    fun `command value round trips through cleanup executable parser`() {
        assertEquals(
            exe,
            WindowsProtocolCleanup.extractExecutablePath(WindowsProtocolRegistryScript.commandValue(exe)),
        )
    }

    @Test
    fun `open command keeps a spaced executable and URL placeholder in one registry value`() {
        val script = WindowsProtocolRegistryScript.buildScript(exe)

        assertTrue(
            script.contains("""@="\"C:\\Program Files\\BOSS\\BOSS.exe\" \"%1\""""),
            "the shell\\open\\command value is wrong:\n$script",
        )
    }

    @Test
    fun `icon and command escape registry special characters`() {
        val script = WindowsProtocolRegistryScript.buildScript("""C:\A"quoted"\BOSS.exe""")

        assertTrue(
            script.contains("""@="C:\\A\"quoted\"\\BOSS.exe,0"""),
            "the icon path is not escaped for a registry script",
        )
        assertTrue(
            script.contains("""@="\"C:\\A\"quoted\"\\BOSS.exe\" \"%1\""""),
            "the shell command is not escaped for a registry script",
        )
    }
}
