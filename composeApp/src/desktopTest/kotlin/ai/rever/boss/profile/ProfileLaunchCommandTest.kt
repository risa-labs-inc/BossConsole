package ai.rever.boss.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileLaunchCommandTest {
    private val link = "boss://workspace?path=%2Fx.json"

    @Test
    fun `a packaged launcher is re-run with only the open request`() {
        val launcher = "/Applications/BOSS.app/Contents/MacOS/BOSS"
        val command = ProfileLaunchCommand.commandFor(launcher, listOf("-Xmx2g"), "work", listOf(link))
        assertEquals(listOf(launcher, link), command)
        assertNull(ProfileLaunchCommand.javaClasspath(command), "a packaged launcher needs no class path")
    }

    @Test
    fun `a development JVM keeps its flags, names the profile and drops what describes this process`() {
        val flags =
            listOf(
                "-Xmx2g",
                "-Dboss.dev.mode=true",
                "-agentlib:jdwp=transport=dt_socket,server=y,address=5005",
                "-Xdebug",
                "-Dboss.profile=launcher-own",
            )
        val command = ProfileLaunchCommand.commandFor("/opt/jdk/bin/java", flags, "work", listOf(link))
        assertEquals(
            listOf(
                "/opt/jdk/bin/java",
                "-Xmx2g",
                "-Dboss.dev.mode=true",
                "-Dboss.profile=work",
                "ai.rever.boss.MainKt",
                link,
            ),
            command,
        )
        assertTrue(ProfileLaunchCommand.javaClasspath(command) != null)
    }

    @Test
    fun `java launchers are recognised on every platform`() {
        assertTrue(ProfileLaunchCommand.isJavaLauncher("/usr/lib/jvm/bin/java"))
        assertTrue(ProfileLaunchCommand.isJavaLauncher("C:\\jdk\\bin\\javaw.exe"))
        assertTrue(ProfileLaunchCommand.isJavaLauncher("C:\\JDK\\BIN\\JAVA.EXE"))
        assertFalse(ProfileLaunchCommand.isJavaLauncher("/opt/boss/bin/BOSS"))
        assertFalse(ProfileLaunchCommand.isJavaLauncher("/opt/javafoo/bin/boss"))
    }

    @Test
    fun `on macOS and Linux the launch is detached through sh, with every argument passed separately`() {
        val command = listOf("/opt/boss/bin/BOSS", "boss://workspace?path=/a b;rm -rf ~")
        assertEquals(
            listOf("/bin/sh", "-c", "\"\$@\" &", "boss-profile") + command,
            ProfileLaunchCommand.detached(command, windows = false).command(),
        )
        assertEquals(command, ProfileLaunchCommand.detached(command, windows = true).command())
    }
}
