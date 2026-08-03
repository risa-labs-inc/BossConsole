package ai.rever.boss.updater

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Hardening tests for the update helper scripts (Issue #37):
 * - installer logs and scripts live in the **platform** temp directory, not `/tmp`
 * - helper scripts are 0700, not 0755
 */
class UpdateScriptGeneratorHardeningTest {
    @TempDir
    lateinit var tempDir: Path

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    @Test
    fun `updater temp dir resolves inside the platform temp directory`() {
        val resolved = resolveUpdaterTempDir("/custom/tmp")

        assertEquals(UPDATER_TEMP_DIR_NAME, resolved.name)
        assertEquals(File("/custom/tmp").path, resolved.parentFile.path)
    }

    /**
     * The regression itself: `/tmp/boss-updater` was hardcoded, so on Windows the
     * log directory resolved to a `C:\tmp\boss-updater` that does not exist and
     * installer logging went nowhere.
     */
    @Test
    fun `updater temp dir is not hardcoded to slash tmp for a Windows temp directory`() {
        val windowsTemp = "C:\\Users\\example\\AppData\\Local\\Temp"

        val resolved = resolveUpdaterTempDir(windowsTemp)

        assertTrue(
            resolved.path.startsWith(windowsTemp),
            "Resolved path should sit under the supplied temp directory, was: ${resolved.path}",
        )
        assertFalse(
            resolved.path.startsWith("/tmp"),
            "The updater directory must not fall back to a hardcoded /tmp",
        )
    }

    @Test
    fun `updater temp dir defaults to the java io tmpdir property`() {
        val expectedParent = File(System.getProperty("java.io.tmpdir")).path

        assertEquals(expectedParent, resolveUpdaterTempDir().parentFile.path)
    }

    @Test
    fun `installer log file lives in the platform temp directory`() {
        val logFile = resolveUpdaterLogFile(timestamp = 1234567890, tempDirPath = "/custom/tmp")

        assertEquals("update-1234567890.log", logFile.name)
        assertEquals(resolveUpdaterTempDir("/custom/tmp").path, logFile.parentFile.path)
    }

    @Test
    fun `installer log file defaults to the java io tmpdir property`() {
        val logFile = resolveUpdaterLogFile(timestamp = 42)

        assertEquals(
            resolveUpdaterTempDir(System.getProperty("java.io.tmpdir")).path,
            logFile.parentFile.path,
            "Log file should follow java.io.tmpdir, not a hardcoded /tmp",
        )
    }

    @Test
    fun `the OS floor guard precedes the destructive delete`() {
        assumeTrue(!isWindows, "The macOS helper script is only generated on POSIX hosts")

        val scriptFile =
            UpdateScriptGenerator.generateMacOSUpdateScript(
                dmgPath = "/tmp/update.dmg",
                targetAppPath = "/Applications/BOSS.app",
                appPid = 12345,
            )

        try {
            val script = scriptFile.readText()
            val guardAt = script.indexOf("LSMinimumSystemVersion")
            // Match the command, not the word: the guard's own comment says
            // "before an irreversible rm -rf" and sits earlier in the script, so a
            // bare indexOf("rm -rf") finds the prose and reports a false failure.
            // The target path is shell-escaped, so the command is `rm -rf '...'`.
            val deleteAt = script.indexOf("rm -rf '")

            assertTrue(guardAt >= 0, "The macOS floor guard must be present in the generated script")
            assertTrue(deleteAt >= 0, "Expected the script to remove the old app bundle")
            // Ordering is the whole property. The guard refuses a build this Mac
            // cannot launch; after the rm -rf it would refuse it having already
            // deleted the working install, leaving the user with nothing. Cheap to
            // break by reordering during an unrelated edit, and nothing else
            // notices.
            assertTrue(
                guardAt < deleteAt,
                "The OS floor guard must run BEFORE the old app is deleted, not after",
            )
            assertTrue(
                script.contains("sw_vers"),
                "The guard needs the running macOS version to compare against",
            )
        } finally {
            scriptFile.delete()
        }
    }

    @Test
    fun `generated helper script is owner only`() {
        assumeTrue(!isWindows, "POSIX permissions are not applicable on Windows")

        val scriptFile =
            UpdateScriptGenerator.generateMacOSUpdateScript(
                dmgPath = "/tmp/update.dmg",
                targetAppPath = "/Applications/BOSS.app",
                appPid = 12345,
            )

        try {
            val permissions = Files.getPosixFilePermissions(scriptFile.toPath())
            assertEquals(
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
                permissions,
                "Helper scripts are executed with elevated privileges from a shared temp directory: 0700 only",
            )
            assertTrue(scriptFile.canExecute(), "The owner must still be able to run the script")
        } finally {
            scriptFile.delete()
        }
    }

    @Test
    fun `generated script is written under the platform temp directory`() {
        val scriptFile =
            UpdateScriptGenerator.generateMacOSUpdateScript(
                dmgPath = "/tmp/update.dmg",
                targetAppPath = "/Applications/BOSS.app",
                appPid = 12345,
            )

        try {
            assertEquals(
                resolveUpdaterTempDir().canonicalPath,
                scriptFile.parentFile.canonicalPath,
            )
        } finally {
            scriptFile.delete()
        }
    }

    @Test
    fun `generated Linux script restricts the askpass helper to the owner`() {
        val scriptFile =
            UpdateScriptGenerator.generateLinuxDebUpdateScript(
                debPath = "/tmp/BOSS-9.9.9-amd64.deb",
                appPid = 12345,
            )

        try {
            val script = scriptFile.readText()
            assertTrue(
                script.contains("chmod 700 \"\$ASKPASS_SCRIPT\""),
                "The askpass helper runs under sudo from a shared temp dir; it must be 0700",
            )
            assertFalse(
                script.contains("chmod +x \"\$ASKPASS_SCRIPT\""),
                "chmod +x leaves the askpass helper group/other-executable",
            )
        } finally {
            scriptFile.delete()
        }
    }

    /**
     * `chmod 700` fixes the mode but not the name: the log and askpass files were
     * still created by `cat >` / `tee -a` at a guessable path in a world-writable
     * /tmp, so a pre-created symlink got followed (and then chmod'd). They must be
     * created with mktemp inside the updater's own 0700 directory.
     */
    @Test
    fun `generated Linux scripts create temp files with mktemp inside the updater directory`() {
        val updaterDir = resolveUpdaterTempDir().absolutePath
        listOf(
            UpdateScriptGenerator.generateLinuxDebUpdateScript("/tmp/BOSS-9.9.9-amd64.deb", 12345),
            UpdateScriptGenerator.generateLinuxRpmUpdateScript("/tmp/BOSS-9.9.9-amd64.rpm", 12345),
        ).forEach { scriptFile ->
            try {
                val script = scriptFile.readText()
                assertFalse(
                    script.contains("\"/tmp/boss-update-debug-"),
                    "Log file must not be a predictable /tmp path",
                )
                assertFalse(
                    script.contains("\"/tmp/boss-askpass-"),
                    "Askpass helper must not be a predictable /tmp path",
                )
                assertTrue(
                    script.contains("mktemp '$updaterDir'/boss-update-debug-XXXXXX"),
                    "Log file should be mktemp'd inside the updater's 0700 directory",
                )
                assertTrue(
                    script.contains("mktemp '$updaterDir'/boss-askpass-XXXXXX"),
                    "Askpass helper should be mktemp'd inside the updater's 0700 directory",
                )
            } finally {
                scriptFile.delete()
            }
        }
    }

    // ==================== Owner-only directories, fail closed ====================

    @Test
    fun `createRestrictedDir creates a fresh directory owner-only`() {
        assumeTrue(!isWindows, "POSIX permissions are not applicable on Windows")
        val dir = File(tempDir.toFile(), "fresh-updater-dir")

        createRestrictedDir(dir)

        assertTrue(dir.isDirectory)
        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
            Files.getPosixFilePermissions(dir.toPath()),
        )
    }

    /**
     * The realistic case: the directory already exists from an older build with
     * 0755. We own it, so it gets tightened rather than rejected.
     */
    @Test
    fun `createRestrictedDir tightens an existing directory it owns`() {
        assumeTrue(!isWindows, "POSIX permissions are not applicable on Windows")
        val dir = File(tempDir.toFile(), "pre-existing").also { it.mkdirs() }
        Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))

        createRestrictedDir(dir)

        assertEquals(
            PosixFilePermissions.fromString("rwx------"),
            Files.getPosixFilePermissions(dir.toPath()),
        )
    }

    /**
     * A symlink where the updater directory should be is someone else redirecting
     * our privileged writes. Fail closed rather than following it.
     */
    @Test
    fun `createRestrictedDir refuses a symlinked directory`() {
        assumeTrue(!isWindows, "Symlink semantics differ on Windows")
        val realDir = File(tempDir.toFile(), "real-target").also { it.mkdirs() }
        val link = File(tempDir.toFile(), "linked-updater-dir")
        try {
            Files.createSymbolicLink(link.toPath(), realDir.toPath())
        } catch (e: java.io.IOException) {
            assumeTrue(false, "Could not create a symlink: ${e.message}")
        }

        val exception = assertThrows<SecurityException> { createRestrictedDir(link) }
        assertTrue(
            exception.message?.contains("symlink") == true,
            "Expected a symlink rejection, got: ${exception.message}",
        )
    }

    @Test
    fun `createRestrictedDir refuses a path that is a regular file`() {
        val notADir = File(tempDir.toFile(), "not-a-dir").also { it.writeText("x") }

        assertThrows<SecurityException> { createRestrictedDir(notADir) }
    }

    // ==================== Validator split: path vs filename ====================

    /**
     * The whole-path denylist made a legitimate Windows account name a permanent
     * auto-update failure: `C:\Users\Bob!\...` was rejected as
     * "command separator characters", with no way for the user to fix it. Only the
     * filename component - the part the release catalog chose - gets the full
     * denylist now.
     */
    @Test
    fun `a Windows account name with batch metacharacters still updates`() {
        listOf(
            "C:\\Users\\Bob!\\AppData\\Local\\Temp\\boss-updates\\BOSS-9.9.9.msi",
            "C:\\Users\\A&B\\AppData\\Local\\Temp\\boss-updates\\BOSS-9.9.9.msi",
            "C:\\Users\\100%Bob\\AppData\\Local\\Temp\\boss-updates\\BOSS-9.9.9.msi",
            "C:\\Users\\Bob^Jr\\AppData\\Local\\Temp\\boss-updates\\BOSS-9.9.9.msi",
        ).forEach { msiPath ->
            val scriptFile =
                UpdateScriptGenerator.generateWindowsUpdateScript(
                    msiPath = msiPath,
                    appPid = 12345,
                )
            try {
                // The escaper is what makes the directory safe: quoted, % doubled.
                val script = scriptFile.readText()
                assertTrue(
                    script.contains("\"" + msiPath.replace("%", "%%") + "\""),
                    "The MSI path should be quoted and percent-escaped in the batch file",
                )
            } finally {
                scriptFile.delete()
            }
        }
    }

    @Test
    fun `the same metacharacters in the artifact name are still rejected`() {
        listOf(
            "C:\\Users\\Bob\\Temp\\BOSS-9.9.9!evil!.msi",
            "C:\\Users\\Bob\\Temp\\BOSS-9.9.9%PATH%.msi",
            "C:\\Users\\Bob\\Temp\\BOSS-9.9.9&calc.msi",
            "C:\\Users\\Bob\\Temp\\BOSS-9.9.9^x.msi",
        ).forEach { msiPath ->
            assertThrows<SecurityException>("Should reject artifact name in: $msiPath") {
                UpdateScriptGenerator.generateWindowsUpdateScript(msiPath = msiPath, appPid = 12345)
            }
        }
    }

    @Test
    fun `traversal and newlines are still rejected anywhere in the path`() {
        assertThrows<SecurityException> {
            UpdateScriptGenerator.generateWindowsUpdateScript("C:\\Users\\..\\..\\x\\BOSS.msi", 12345)
        }
        assertThrows<SecurityException> {
            UpdateScriptGenerator.generateWindowsUpdateScript("C:\\Users\\Bob\nevil\\BOSS.msi", 12345)
        }
    }

    @Test
    fun `fileNameComponent splits both separator styles`() {
        assertEquals("BOSS-9.9.9.msi", UpdatePathValidator.fileNameComponent("C:\\Users\\Bob!\\BOSS-9.9.9.msi"))
        assertEquals(
            "BOSS-9.9.9.dmg",
            UpdatePathValidator.fileNameComponent("/var/folders/x/boss-updates/BOSS-9.9.9.dmg"),
        )
        assertEquals("BOSS.dmg", UpdatePathValidator.fileNameComponent("BOSS.dmg"))
    }
}
