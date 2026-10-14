package ai.rever.boss.updater

import ai.rever.boss.utils.CodeSourceLocation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URL
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Windows update helper installed the MSI and then simply ended, so "Install
 * update" quit BOSS and nothing came back - while the macOS script always finished
 * with `open` and the Linux one with `nohup .../BOSS`. These pin the relaunch, and
 * the resolution of the launcher path it needs.
 */
class WindowsUpdateRelaunchTest {
    private val msiPath = """C:\Users\Bob\AppData\Local\Temp\boss-updates\BOSS-9.9.9.msi"""
    private val exePath = """C:\Users\Bob\AppData\Local\BOSS\BOSS.exe"""

    private fun script(targetExePath: String?): String {
        val scriptFile =
            UpdateScriptGenerator.generateWindowsUpdateScript(
                msiPath = msiPath,
                appPid = 12345,
                targetExePath = targetExePath,
            )
        try {
            return scriptFile.readText()
        } finally {
            scriptFile.delete()
        }
    }

    // ==================== The script ====================

    @Test
    fun `the Windows script relaunches the installed launcher after a successful install`() {
        val script = script(exePath)

        assertTrue(
            script.contains("""start "" "$exePath""""),
            "The script must launch the installed exe:\n$script",
        )
        assertTrue(
            script.indexOf("msiexec") < script.indexOf("""start "" "$exePath""""),
            "The relaunch must come after the install, not before it",
        )
    }

    /**
     * Ordering after `msiexec` alone would also be satisfied by a relaunch sitting past
     * `:cleanup`, which `goto cleanup` would then skip on the *success* path too. What
     * encodes the control flow is that it sits between the success echo and the label.
     */
    @Test
    fun `the relaunch sits between the success echo and cleanup`() {
        val script = script(exePath)

        val success = script.indexOf("Installation successful!")
        val relaunch = script.indexOf("""start "" "$exePath"""")
        val cleanup = script.indexOf("\n:cleanup")

        assertTrue(success in 0 until relaunch, "Relaunch must follow the success echo:\n$script")
        assertTrue(relaunch < cleanup, "Relaunch must precede :cleanup, or goto would skip it:\n$script")
    }

    /**
     * A major upgrade is only *usually* into the same INSTALLDIR - a per-user install
     * replaced by a per-machine one lands elsewhere - and `start` on a missing path
     * raises a Windows error dialog rather than failing quietly.
     */
    @Test
    fun `the relaunch is guarded by an existence check`() {
        val script = script(exePath)

        assertTrue(
            script.contains("""if exist "$exePath""""),
            "The relaunch should be guarded by if exist:\n$script",
        )
    }

    /**
     * The failure branch hands the user the interactive installer. Relaunching into
     * that would put a BOSS holding its own files open in front of an installer that
     * needs them free.
     */
    @Test
    fun `a failed install does not relaunch`() {
        val script = script(exePath)

        // Sliced on the `goto cleanup` line rather than the first `)`: a path containing
        // a paren would truncate the branch and quietly weaken this, and
        // `C:\Program Files (x86)\...` is not exotic.
        val failureBranch =
            script
                .lines()
                .dropWhile { !it.contains("if not %MSI_RESULT% EQU 0 (") }
                .takeWhile { !it.contains("goto cleanup") }

        assertTrue(failureBranch.isNotEmpty(), "Could not find the failure branch:\n$script")
        assertFalse(
            failureBranch.any { it.contains(exePath) },
            "The failure branch must not relaunch BOSS:\n$failureBranch",
        )
        assertTrue(
            script.lines().any { it.contains("goto cleanup") },
            "The failure branch should skip to cleanup:\n$script",
        )
    }

    /**
     * `/norestart` makes msiexec report 3010 ("installed, wants a reboot") instead of
     * 0 whenever a file was in use, which is routine when replacing a running app's
     * directory. Branching on `NEQ 0` would call that a failure and open an installer
     * the user does not need.
     */
    @Test
    fun `the reboot-pending exit codes count as success`() {
        val script = script(exePath)

        assertTrue(script.contains("if %MSI_RESULT% EQU 3010 set MSI_RESULT=0"), script)
        assertTrue(script.contains("if %MSI_RESULT% EQU 1641 set MSI_RESULT=0"), script)
    }

    /**
     * 3010 means the install completed but the replacement is pending a reboot, so the
     * relaunch can bring back a partially-updated install: "Installation successful!", an
     * app still reporting the old version, and the same update offered again next check.
     * The raw code has to survive the mapping or there is nothing in the log to explain
     * that.
     */
    @Test
    fun `the raw installer code is kept and reported when it differs`() {
        val script = script(exePath)

        assertTrue(
            script.contains("set MSI_RAW=%MSI_RESULT%"),
            "The raw code must be captured before the mapping rewrites it:\n$script",
        )
        val report = script.lines().single { it.startsWith("if not %MSI_RAW% EQU %MSI_RESULT%") }
        assertTrue(report.contains("reboot is pending"), "The report should say why:\n$report")
        assertTrue(
            script.indexOf("Installation successful!") < script.indexOf(report),
            "The reboot note belongs on the success path, after the failure branch has gone",
        )
    }

    /** An unresolvable launcher must not stop the update - it only loses the relaunch. */
    @Test
    fun `an unknown launcher path still produces an installing script`() {
        val script = script(null)

        assertTrue(script.contains("msiexec /i \"$msiPath\""), "The install must still happen:\n$script")
        assertFalse(script.contains("if exist"), "There is nothing to relaunch:\n$script")
        assertTrue(script.contains("please start BOSS manually"), "The log should say why:\n$script")
    }

    /**
     * `trimIndent` runs *after* interpolation, so splicing a multi-line block into an
     * indented template leaves the block's own lines at column 0, makes the common
     * indent 0, and emits every other line still carrying the Kotlin template's
     * indent - including the `:labels` `goto` depends on.
     */
    @Test
    fun `directives and labels sit at column 0`() {
        listOf(script(exePath), script(null)).forEach { script ->
            assertTrue(script.startsWith("@echo off"), "Script should open with an unindented @echo off:\n$script")
            assertTrue(script.lines().contains(":waitloop"), "The waitloop label must not be indented:\n$script")
            assertTrue(script.lines().contains(":cleanup"), "The cleanup label must not be indented:\n$script")
        }
    }

    /**
     * cmd.exe parses a batch file by seeking byte offsets that assume CRLF, so an
     * LF-only file breaks `goto` into a label - and breaks it by jumping somewhere
     * wrong rather than by failing. The failure branch depends on that `goto`.
     */
    @Test
    fun `the batch file is written with CRLF line endings`() {
        val script = script(exePath)

        assertFalse(
            script.contains(Regex("(?<!\r)\n")),
            "Every newline in a .bat must be a CRLF",
        )
        // `core.autocrlf` is on for Windows clones and there is no .gitattributes, so the
        // source file itself arrives with CRLF. That is harmless only because every block
        // goes through `trimIndent`, which rejoins with "\n" - if one ever stops doing so,
        // the CRLF conversion would double the CR and the assertion above would not notice.
        assertFalse(script.contains("\r\r"), "The CRLF conversion must not double the CR")
    }

    /**
     * `launchScript` starts the helper with ProcessBuilder, so its stdin is a pipe, and
     * `timeout` refuses to run at all under redirected input - it exited immediately
     * with "ERROR: Input redirection is not supported", which turned the quit poll into
     * a hot loop and wrote three errors into the one log anybody diagnosing a failed
     * update would read.
     */
    @Test
    fun `the waits survive the redirected stdin the script is launched with`() {
        val script = script(exePath)

        assertFalse(script.contains("timeout /t"), "timeout cannot run under redirected stdin:\n$script")
        assertTrue(script.contains("ping -n 2 127.0.0.1 >NUL"), "The quit poll needs a working sleep:\n$script")
    }

    // ==================== Resolving the launcher ====================
    //
    // Paths are built with File so the parent walk exercises the *host* separator:
    // a literal `C:\...\app\composeApp.jar` has no parent at all on a POSIX CI leg,
    // which would make these pass for the wrong reason there.

    private val tempRoot = File(System.getProperty("java.io.tmpdir"))
    private val installDir = File(tempRoot, "BOSS")
    private val installedLauncher = File(installDir, "BOSS.exe").path
    private val runningJar = File(File(installDir, "app"), "composeApp.jar").path

    /** A real jpackage install: the launcher plus the `app\` and `runtime\` beside it. */
    private val realInstall =
        setOf(
            installedLauncher,
            File(installDir, "app").path,
            File(installDir, "runtime").path,
        )

    @Test
    fun `jpackage app-path wins when it points at something real`() {
        val resolved =
            windowsLauncherPathFor(
                jpackageAppPath = exePath,
                codeSourcePath = runningJar,
                exists = { it == exePath || it in realInstall },
            )

        assertEquals(exePath, resolved, "The property jpackage set should be preferred over any inference")
    }

    /**
     * jpackage sets the property on every packaged launch, but an installation whose
     * directory moved leaves it naming a path that is gone. Falling through to the
     * layout walk is what keeps the relaunch working there.
     */
    @Test
    fun `a stale jpackage app-path falls through to the install layout`() {
        val resolved =
            windowsLauncherPathFor(
                jpackageAppPath = File(File(tempRoot, "Old BOSS"), "BOSS.exe").path,
                codeSourcePath = runningJar,
                exists = { it in realInstall },
            )

        assertEquals(installedLauncher, resolved)
    }

    @Test
    fun `a blank jpackage app-path is ignored`() {
        val resolved =
            windowsLauncherPathFor(
                jpackageAppPath = "",
                codeSourcePath = runningJar,
                exists = { it in realInstall },
            )

        assertEquals(installedLauncher, resolved)
    }

    /**
     * Every other resolver case has exactly one `exists` hit, so a walk that returned the
     * *furthest* match would pass them all. The nearest one is the install we are running
     * from; a further one is somebody else's.
     */
    @Test
    fun `the nearest launcher in the parent chain wins`() {
        val outerLauncher = File(tempRoot, "BOSS.exe").path
        val alsoLooksInstalled = realInstall + outerLauncher + File(tempRoot, "app").path

        val resolved =
            windowsLauncherPathFor(
                jpackageAppPath = null,
                codeSourcePath = runningJar,
                exists = { it in alsoLooksInstalled },
            )

        assertEquals(installedLauncher, resolved, "Resolved the outer launcher instead of the one we run from")
    }

    /**
     * The walk is five deep to cover a jar nested deeper than jpackage's two, but above
     * `<install>` every ancestor is user-writable and the result goes straight to `start`.
     * A bare `BOSS.exe` with no `app\` or `runtime\` beside it is not an install.
     */
    @Test
    fun `a stray exe in a user-writable ancestor is not accepted`() {
        val strayInAppData = File(tempRoot, "BOSS.exe").path

        assertNull(
            windowsLauncherPathFor(
                jpackageAppPath = null,
                codeSourcePath = runningJar,
                exists = { it == strayInAppData },
            ),
            "A directory with no jpackage marker beside the exe must not count as an install",
        )
    }

    /** A development run has no launcher anywhere above it, and must resolve to null. */
    @Test
    fun `a dev run resolves to null rather than guessing`() {
        assertNull(
            windowsLauncherPathFor(
                jpackageAppPath = null,
                codeSourcePath = runningJar,
                exists = { false },
            ),
        )
    }

    @Test
    fun `an absent code source resolves to null`() {
        assertNull(
            windowsLauncherPathFor(
                jpackageAppPath = null,
                codeSourcePath = null,
                exists = { true },
            ),
        )
    }

    /**
     * The most consequential invariant in the feature: a launcher path the validator
     * refuses must lose the *relaunch*, not the *update*. The generator throws on a bad
     * argument, so `getWindowsLauncherPath` swallows the SecurityException and returns
     * null; letting it propagate would trade "installs but does not relaunch" for "does
     * not install", which is strictly worse.
     */
    @Test
    fun `a launcher path the validator refuses yields null, not a throw`(
        @TempDir tempDir: Path,
    ) {
        val root = tempDir.toFile()
        File(root, "BOSS.exe").createNewFile()
        File(root, "sub").mkdirs()
        // Resolves and exists, so it is selected - and carries `..`, which validatePath
        // refuses over the whole path.
        val traversing = File(File(root, "sub"), "..${File.separator}BOSS.exe").path

        withJpackageAppPath(traversing) {
            assertNull(
                UpdateInstaller.getWindowsLauncherPath(),
                "A refused path must degrade to no relaunch rather than aborting the update",
            )
        }
    }

    /**
     * A network-share install, from the code-source URL through the layout walk. On
     * Windows the walk has to start from a path that still names the server; a host
     * with no UNC concept resolves no code source and so no launcher. The old
     * `File(uri.path)` gave `\software\...` on every host, which fails both branches.
     */
    @Test
    fun `a network-share install resolves the launcher on the share`() {
        val codeSource = CodeSourceLocation.fileOf(URL("file://nas01/software/BOSS/app/composeApp.jar"))
        val share = """\\nas01\software\BOSS"""
        val shareInstall = setOf("""$share\BOSS.exe""", """$share\app""", """$share\runtime""")

        val resolved =
            windowsLauncherPathFor(
                jpackageAppPath = null,
                codeSourcePath = codeSource?.path,
                exists = { it in shareInstall },
            )

        if (File.separatorChar == '\\') {
            assertEquals("""$share\BOSS.exe""", resolved)
        } else {
            assertNull(codeSource, "resolved ${codeSource?.path}, which dropped the server")
            assertNull(resolved)
        }
    }

    /**
     * A hidden share's name ends in `$`, which [UpdatePathValidator] refuses anywhere
     * in a path, so that install updates without the relaunch. Pinned so relaxing it
     * is a deliberate change to the validator rather than a side effect.
     */
    @Test
    fun `a launcher on a hidden share updates without a relaunch`(
        @TempDir tempDir: Path,
    ) {
        val hiddenShare = File(tempDir.toFile(), "apps\$").apply { mkdirs() }
        val launcher = File(hiddenShare, "BOSS.exe").apply { createNewFile() }

        withJpackageAppPath(launcher.path) {
            assertNull(UpdateInstaller.getWindowsLauncherPath())
        }
    }

    private fun withJpackageAppPath(
        value: String,
        block: () -> Unit,
    ) {
        val previous: String? = System.getProperty("jpackage.app-path")
        try {
            System.setProperty("jpackage.app-path", value)
            block()
        } finally {
            if (previous == null) {
                System.clearProperty("jpackage.app-path")
            } else {
                System.setProperty("jpackage.app-path", previous)
            }
        }
    }

    // ==================== Launching the helper ====================

    /**
     * ProcessBuilder quotes any argument containing a space, and `start` treats its first
     * quoted token as a window *title*. So `cmd /c start /b "<script>"` never ran the
     * script for an account whose name has a space - no install, no relaunch, nothing in
     * the log. The empty title argument is what makes the path a command again.
     */
    @Test
    fun `the Windows launch command carries the empty title argument`() {
        val command = updaterLaunchCommand("windows 11", """C:\Users\Bob Smith\AppData\Local\Temp\boss-updater\u.bat""")

        assertEquals(
            listOf("cmd", "/c", "start", "", "/b", """C:\Users\Bob Smith\AppData\Local\Temp\boss-updater\u.bat"""),
            command,
        )
        assertEquals("", command[3], "The token after `start` must be the empty title, not the script path")
    }

    @Test
    fun `the posix launch commands are unchanged`() {
        assertEquals(listOf("nohup", "bash", "/tmp/u.sh"), updaterLaunchCommand("mac os x", "/tmp/u.sh"))
        assertEquals(listOf("nohup", "bash", "/tmp/u.sh"), updaterLaunchCommand("linux", "/tmp/u.sh"))
        assertEquals(listOf("bash", "/tmp/u.sh"), updaterLaunchCommand("plan 9", "/tmp/u.sh"))
    }

    @Test
    fun `the bash fallback is the only case reported as unknown`() {
        listOf("mac os x", "darwin", "linux", "windows 11").forEach {
            assertTrue(isKnownUpdaterOs(it), "$it should not warn about an unknown OS")
        }
        assertFalse(isKnownUpdaterOs("plan 9"), "An unrecognised OS should warn")
    }
}
