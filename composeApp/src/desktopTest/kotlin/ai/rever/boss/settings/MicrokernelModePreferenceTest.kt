package ai.rever.boss.settings

import ai.rever.boss.config.parseEnvVars
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the shared preference-save path BossConsole#472 asks both entry points to use, and the
 * pure off-to-on decision that gates the confirmation dialog.
 *
 * Every test points [MicrokernelModePreference] at an isolated temp file rather than the real
 * `env_vars` - [ai.rever.boss.plugin.pathutils.BossDirectories.resolve] always resolves against
 * the real user home directory with no override seam, so calling the real defaults from a test
 * would read and write this machine's actual preference file.
 */
class MicrokernelModePreferenceTest {
    private val tempFiles = mutableListOf<File>()

    private fun tempEnvFile(): File {
        val dir =
            kotlin.io.path
                .createTempDirectory("microkernel-mode-test")
                .toFile()
        return File(dir, "env_vars").also { tempFiles.add(it) }
    }

    @BeforeTest
    fun resetSingletonState() {
        // MicrokernelModePreference.startupEnabledLatched is deliberately latched once per real
        // process with no other reset seam - without this, whichever test happens to call
        // refresh() first in this JVM run would leak its snapshot into every test after it.
        MicrokernelModePreference.forgetStartupSnapshotForTest()
    }

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { it.parentFile?.deleteRecursively() }
        tempFiles.clear()
        MicrokernelModePreference.forgetStartupSnapshotForTest()
    }

    @Test
    fun `a missing env_vars file reads as disabled`() =
        runTest {
            val file = tempEnvFile()
            assertFalse(MicrokernelModePreference.isEnabled(file))
        }

    @Test
    fun `enabling then reading back from the same file reflects the write`() =
        runTest {
            val file = tempEnvFile()

            assertTrue(MicrokernelModePreference.setEnabled(true, file).isSuccess)
            assertTrue(MicrokernelModePreference.isEnabled(file))

            // "Survives a reload" means a fresh read of the file, not a cached in-memory value -
            // there is no engine instance here to hold one, so the read has to hit disk to pass.
            assertTrue(MicrokernelModePreference.isEnabled(file))
        }

    @Test
    fun `disabling comments the line out rather than removing it`() =
        runTest {
            val file = tempEnvFile()
            MicrokernelModePreference.setEnabled(true, file)

            assertTrue(MicrokernelModePreference.setEnabled(false, file).isSuccess)
            assertFalse(MicrokernelModePreference.isEnabled(file))
            assertTrue(file.readText().contains("# BOSS_MODE=KERNEL"), "the key should stay, just commented")
        }

    @Test
    fun `writing the preference does not disturb other lines already in the file`() =
        runTest {
            val file = tempEnvFile()
            file.parentFile.mkdirs()
            file.writeText("OTHER_KEY=value\nBOSS_LOG_LEVEL=DEBUG\n")

            assertTrue(MicrokernelModePreference.setEnabled(true, file).isSuccess)

            val lines = file.readLines()
            assertTrue(lines.contains("OTHER_KEY=value"))
            assertTrue(lines.contains("BOSS_LOG_LEVEL=DEBUG"))
            assertTrue(MicrokernelModePreference.isEnabled(file))
        }

    @Test
    fun `a failed write is reported as failure, not thrown or silently ignored`() =
        runTest {
            // A directory sitting at the target path: the write inside has nothing it can
            // atomically become, so it fails the same way an unwritable real config dir would.
            val file = tempEnvFile().also { it.mkdirs() }

            val result = MicrokernelModePreference.setEnabled(true, file)

            assertTrue(result.isFailure)
            // The on-disk state (still a directory, still reading as disabled) must not have
            // silently drifted despite the failure.
            assertFalse(MicrokernelModePreference.isEnabled(file))
        }

    @Test
    fun `confirmation is needed only for an explicit off-to-on request`() {
        assertTrue(needsMicrokernelModeConfirmation(currentlyEnabled = false, nextEnabled = true))
        assertFalse(needsMicrokernelModeConfirmation(currentlyEnabled = true, nextEnabled = false))
        assertFalse(needsMicrokernelModeConfirmation(currentlyEnabled = false, nextEnabled = false))
        assertFalse(needsMicrokernelModeConfirmation(currentlyEnabled = true, nextEnabled = true))
    }

    @Test
    fun `the confirmation message states the mode is experimental without overclaiming`() {
        // Loose content pins rather than a full-string match: wording may be polished later,
        // but the two load-bearing claims - "experimental" and no promise of guaranteed isolation
        // - must survive that, since they are what #472 is actually asking this dialog to say.
        assertTrue(MICROKERNEL_MODE_CONFIRMATION_MESSAGE.contains("experimental", ignoreCase = true))
        assertTrue(MICROKERNEL_MODE_CONFIRMATION_MESSAGE.contains("does not guarantee"))
    }

    @Test
    fun `similarly named keys survive and duplicate mode assignments cannot defeat disable`() =
        runTest {
            val file = tempEnvFile()
            file.writeText("BOSS_MODE_EXTRA=keep\n\t# BOSS_MODE=KERNEL\nBOSS_MODE=KERNEL\nOTHER=keep\n")
            assertTrue(MicrokernelModePreference.setEnabled(false, file).isSuccess)
            assertFalse(MicrokernelModePreference.isEnabled(file))
            assertTrue(file.readLines().contains("BOSS_MODE_EXTRA=keep"))
            assertTrue(file.readLines().contains("OTHER=keep"))
        }

    @Test
    fun `last active assignment determines saved mode`() =
        runTest {
            val file = tempEnvFile()
            file.writeText("BOSS_MODE=KERNEL\nBOSS_MODE=MONOLITH\n")
            assertFalse(MicrokernelModePreference.isEnabled(file))
        }

    @Test
    fun `menu and settings observe only successful saves and a later success clears failure`() =
        runTest {
            // Both surfaces collect the same StateFlow - this asserts on that one flow's value,
            // which is what "settings" and "menu" being the same object under the hood means;
            // see the two-independent-collectors test below for the property that actually
            // matters, that neither's own composition state can diverge from the shared source.
            val state = MicrokernelModePreference.saveState
            MicrokernelModePreference.saveAndPublish(false) { Result.success(Unit) }
            MicrokernelModePreference.saveAndPublish(true) { Result.failure(java.io.IOException("blocked")) }
            assertEquals(false, state.value.enabled)
            assertTrue(state.value.saveFailed)
            assertEquals("Microkernel Mode (save failed - retry)", microkernelModeMenuLabel(state.value))

            MicrokernelModePreference.saveAndPublish(true) { Result.success(Unit) }
            assertEquals(true, state.value.enabled)
            assertFalse(state.value.saveFailed)
            // No refresh() happened in this test, so there is no startup snapshot to compare
            // against yet - "restart required" cannot be claimed for a value nothing has been
            // read as running under. See the refresh()-based tests below for that behavior.
            assertFalse(state.value.needsRestart)
            assertEquals("Microkernel Mode", microkernelModeMenuLabel(state.value))
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `two active collectors receive successful saves and failures`() =
        runTest {
            val settingsSeen = mutableListOf<MicrokernelModeSaveState>()
            val menuSeen = mutableListOf<MicrokernelModeSaveState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                MicrokernelModePreference.saveState.collect { settingsSeen.add(it) }
            }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                MicrokernelModePreference.saveState.collect { menuSeen.add(it) }
            }
            MicrokernelModePreference.saveAndPublish(true) { Result.success(Unit) }
            MicrokernelModePreference.saveAndPublish(false) { Result.failure(java.io.IOException("blocked")) }
            assertEquals(settingsSeen, menuSeen)
            assertEquals(3, settingsSeen.size)
            assertEquals(true, settingsSeen[1].enabled)
            assertEquals(true, settingsSeen.last().enabled)
            assertTrue(settingsSeen.last().saveFailed)
        }

    @Test
    fun `restart is needed only when the saved value differs from what the process started with`() {
        // Pure - this is the exact comparand that was wrong: comparing against a live
        // ConfigLoader read (which never reflects env_vars) instead of the value this process
        // actually saw when it started.
        assertFalse(MicrokernelModeSaveState(enabled = true, startupEnabled = true).needsRestart)
        assertFalse(MicrokernelModeSaveState(enabled = false, startupEnabled = false).needsRestart)
        assertTrue(MicrokernelModeSaveState(enabled = true, startupEnabled = false).needsRestart)
        assertTrue(MicrokernelModeSaveState(enabled = false, startupEnabled = true).needsRestart)
        // Before the first refresh(), there is nothing to compare against yet.
        assertFalse(MicrokernelModeSaveState(enabled = true, startupEnabled = null).needsRestart)
        assertFalse(MicrokernelModeSaveState(enabled = null, startupEnabled = false).needsRestart)
    }

    @Test
    fun `the startup snapshot latches on the first refresh and a later refresh cannot move it`() =
        runTest {
            val file = tempEnvFile()
            MicrokernelModePreference.setEnabled(false, file)

            MicrokernelModePreference.refresh(file)
            assertEquals(false, MicrokernelModePreference.saveState.value.startupEnabled)

            // The file changes underneath (e.g. someone hand-edits it, or a save from this same
            // "process" runs) - a second refresh must still report the ORIGINAL startup value.
            MicrokernelModePreference.setEnabled(true, file)
            MicrokernelModePreference.refresh(file)
            assertEquals(true, MicrokernelModePreference.saveState.value.enabled)
            assertEquals(false, MicrokernelModePreference.saveState.value.startupEnabled)
            assertTrue(MicrokernelModePreference.saveState.value.needsRestart)
        }

    @Test
    fun `an actual restart - forgetting the snapshot and reading the new file - clears the notice`() =
        runTest {
            val file = tempEnvFile()
            MicrokernelModePreference.setEnabled(false, file)
            MicrokernelModePreference.refresh(file)
            MicrokernelModePreference.setEnabled(true, file)
            MicrokernelModePreference.refresh(file)
            assertTrue(
                MicrokernelModePreference.saveState.value.needsRestart,
                "sanity: notice is up before the restart",
            )

            // The one thing an actual restart does that this test can't otherwise simulate:
            // a fresh process has no latched snapshot yet.
            MicrokernelModePreference.forgetStartupSnapshotForTest()
            assertNull(MicrokernelModePreference.saveState.value.startupEnabled)

            MicrokernelModePreference.refresh(file)
            assertEquals(true, MicrokernelModePreference.saveState.value.startupEnabled)
            assertFalse(MicrokernelModePreference.saveState.value.needsRestart)
            assertEquals("Microkernel Mode", microkernelModeMenuLabel(MicrokernelModePreference.saveState.value))
        }

    @Test
    fun `refresh also clears a stale save failure`() =
        runTest {
            val file = tempEnvFile()
            MicrokernelModePreference.saveAndPublish(true) { Result.failure(java.io.IOException("blocked")) }
            assertTrue(MicrokernelModePreference.saveState.value.saveFailed)

            MicrokernelModePreference.refresh(file)
            assertFalse(MicrokernelModePreference.saveState.value.saveFailed)
        }

    @Test
    fun `shared consent cancels without writing and confirms once`() {
        val consent = MicrokernelModeConfirmation()
        var writes = 0
        consent.request()
        assertTrue(consent.pending)
        assertEquals(0, writes)
        consent.cancel()
        consent.confirm { writes++ }
        assertFalse(consent.pending)
        assertEquals(0, writes)
        consent.request()
        consent.confirm { writes++ }
        consent.confirm { writes++ }
        assertFalse(consent.pending)
        assertEquals(1, writes)
    }

    @Test
    fun `failed atomic publication preserves original bytes and removes temporary file`() {
        val file = tempEnvFile()
        file.writeText("OTHER=preserved\nBOSS_MODE=KERNEL\n")
        val before = file.readBytes().toList()
        assertFailsWith<java.io.IOException> {
            writeModeFile(file, "replacement") { source, _ ->
                assertEquals("replacement", source.toFile().readText())
                throw java.io.IOException("simulated move failure")
            }
        }
        assertEquals(before, file.readBytes().toList())
        assertEquals(listOf("env_vars"), file.parentFile.list()!!.toList())
    }

    @Test
    fun `atomic publication preserves existing posix access permissions`() {
        val file = tempEnvFile()
        file.writeText("OTHER=preserved\n")
        val path = file.toPath()
        if (java.nio.file.Files
                .getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView::class.java) == null
        ) {
            return
        }
        val permissions =
            java.nio.file.attribute.PosixFilePermissions
                .fromString("rw-------")
        java.nio.file.Files
            .setPosixFilePermissions(path, permissions)
        writeModeFile(file, "OTHER=preserved\nBOSS_MODE=KERNEL\n")
        assertEquals(
            permissions,
            java.nio.file.Files
                .getPosixFilePermissions(path),
        )
    }

    @Test
    fun `a failed refresh preserves the saved value and reports it as a read failure, not a save failure`() =
        runTest {
            val file = tempEnvFile()
            MicrokernelModePreference.setEnabled(true, file)
            MicrokernelModePreference.refresh(file)
            file.delete()
            file.mkdir()
            MicrokernelModePreference.refresh(file)
            assertEquals(true, MicrokernelModePreference.saveState.value.enabled)
            assertEquals(true, MicrokernelModePreference.saveState.value.startupEnabled)
            // Nothing was written here - refresh() only reads - so this must not be reported
            // through the same flag a failed write uses (BossConsole#481 review): the two surfaces
            // this drives say "check that BOSS can write" / "save failed - retry" for saveFailed,
            // which is actively wrong advice for a fault that never attempted a write.
            assertTrue(MicrokernelModePreference.saveState.value.readFailed)
            assertFalse(MicrokernelModePreference.saveState.value.saveFailed)
            assertEquals(
                "Microkernel Mode (unavailable)",
                microkernelModeMenuLabel(MicrokernelModePreference.saveState.value),
            )
        }

    @Test
    fun `a read failure on the very first refresh is distinguishable from a save failure`() =
        runTest {
            // No prior successful refresh here - enabled and startupEnabled both stay null, which
            // is exactly the case the review flagged: both controls end up disabled, so a
            // "save failed - retry" label would invite a retry neither surface can perform.
            val file = tempEnvFile().also { it.mkdir() }
            MicrokernelModePreference.refresh(file)
            assertNull(MicrokernelModePreference.saveState.value.enabled)
            assertNull(MicrokernelModePreference.saveState.value.startupEnabled)
            assertTrue(MicrokernelModePreference.saveState.value.readFailed)
            assertEquals(
                "Microkernel Mode (unavailable)",
                microkernelModeMenuLabel(MicrokernelModePreference.saveState.value),
            )
        }

    @Test
    fun `export-prefixed assignments are read and can be toggled off in place`() =
        runTest {
            val file = tempEnvFile()
            file.writeText("export BOSS_MODE=KERNEL\n")
            assertTrue(MicrokernelModePreference.isEnabled(file), "export BOSS_MODE=KERNEL should read as enabled")

            assertTrue(MicrokernelModePreference.setEnabled(false, file).isSuccess)
            assertFalse(MicrokernelModePreference.isEnabled(file))
            // Replaced in place rather than appended - the pre-fix behavior could not find the
            // export-prefixed assignment at all and appended a second, unrelated line instead,
            // leaving the original export line (still active) untouched underneath it.
            assertEquals(listOf("# BOSS_MODE=KERNEL"), file.readLines())
        }

    @Test
    fun `indented comment lines are comments in the settings reader too`() =
        runTest {
            // The disabled state is written as `# BOSS_MODE=KERNEL`; an indented copy is a
            // comment as well. Before BossConsole#450's fix this line read as ENABLED in the
            // settings reader while the ConfigLoader reader (and every runtime gate) saw a
            // comment - the UI and the runtime disagreed about one file.
            val file = tempEnvFile()
            file.writeText("  # BOSS_MODE=KERNEL\n")
            assertFalse(MicrokernelModePreference.isEnabled(file))
        }

    @Test
    fun `settings reader and ConfigLoader reader agree on the same file`() =
        runTest {
            // Both in-repo readers of env_vars must agree line by line (BossConsole#450's
            // review): Settings shows the mode ON exactly when the runtime gate would resolve
            // KERNEL for the very same file. If either reader regrows a private parser, this
            // fails on the line where they diverge.
            val samples =
                listOf(
                    "BOSS_MODE=KERNEL",
                    "export BOSS_MODE=KERNEL",
                    "# BOSS_MODE=KERNEL",
                    "  # BOSS_MODE=KERNEL",
                    "BOSS_MODE=MONOLITH",
                    "BOSS_MODE=KERNEL\nBOSS_MODE=MONOLITH",
                )
            for (sample in samples) {
                val file = tempEnvFile()
                file.writeText(sample + "\n")
                val configLoaderView =
                    parseEnvVars(file.readLines()).getProperty("BOSS_MODE")?.trim()?.uppercase() == "KERNEL"
                val settingsView = MicrokernelModePreference.isEnabled(file)
                assertEquals(configLoaderView, settingsView, "readers disagree on: $sample")
            }
        }

    @Test
    fun `a read-only target is refused before any temp file is created`() {
        val file = tempEnvFile()
        file.writeText("BOSS_MODE=KERNEL\n")
        if (!file.setWritable(false)) return // best-effort: some CI filesystems ignore this
        try {
            // Confirmed empirically to differ by platform (BossConsole#481 review): POSIX
            // rename() only cares about directory permissions, so a read-only target in a
            // writable directory is technically replaceable there - but Windows' ATOMIC_MOVE
            // (MoveFileEx/MOVEFILE_REPLACE_EXISTING) honors the target's own read-only attribute
            // and fails to replace it regardless of directory permissions, which this test caught
            // directly (AccessDeniedException) before writeModeFile gained its own precondition
            // for it. Keeping the target check - not replacing it with a parent-only check -
            // means both platforms fail the same clear, early way instead of Windows failing deep
            // inside Files.move with a less specific exception.
            assertFailsWith<java.io.IOException> {
                writeModeFile(file, "BOSS_MODE=KERNEL\nEXTRA=1\n")
            }
            assertEquals(listOf("env_vars"), file.parentFile.list()!!.toList())
        } finally {
            file.setWritable(true)
        }
    }
}
