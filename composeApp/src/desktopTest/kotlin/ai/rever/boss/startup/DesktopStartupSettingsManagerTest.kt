package ai.rever.boss.startup

import ai.rever.boss.plugin.pathutils.BossDirectories
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The startup settings manager, which unlike its siblings has no settingsFile seam: its path
 * is fixed at object initialisation, so these tests work with desktopTest's user.home
 * isolation and exercise the real ~/.boss/startup-settings.json it resolves there.
 *
 * PROCESS-GLOBAL: this suite mutates the manager's in-memory state and its settings file in
 * the shared test home, so it must not run in parallel with itself or anything else that
 * reads startup-settings.json. Correct today because desktopTest runs one class at a time.
 */
class DesktopStartupSettingsManagerTest {
    private val settingsFile: File = BossDirectories.resolve("startup-settings.json")

    @BeforeTest
    fun ensureRegularFile() {
        // The replace-stage failure test swaps the path for a directory; restore order no
        // matter which order the methods run in.
        if (settingsFile.isDirectory) {
            assertTrue(settingsFile.delete(), "a leftover directory target should be removable")
        }
    }

    /**
     * atomicWriteText writes a unique sibling "startup-settings.json.<random>.tmp" and moves
     * it onto the target; a successful write takes it away and a failed one deletes it.
     * Either way, none remain.
     */
    private fun strayTempFiles(): List<File> {
        val temps =
            settingsFile.parentFile?.listFiles { file ->
                file.name.startsWith("${settingsFile.name}.") && file.name.endsWith(".tmp")
            }
        return temps.orEmpty().toList()
    }

    @Test
    fun `an update persists through temp and rename and survives a reload`() {
        runBlocking {
            StartupSettingsManager.updateSettings(StartupSettings(workspaceLoadTimeoutMs = 4321L))
            StartupSettingsManager.loadSettings()
        }

        assertEquals(
            4321L,
            StartupSettingsManager.currentSettings.value.workspaceLoadTimeoutMs,
            "a reload of a persisted update must land on the same value",
        )
        assertTrue(settingsFile.exists(), "the settings file should have been written")
        assertTrue(settingsFile.readText().contains("4321"), settingsFile.readText())
        assertEquals(
            0,
            strayTempFiles().size,
            "a successful write must move its temp away, not leave it behind",
        )
    }

    /**
     * The race the load fence exists for, without having to win a real race: the load's
     * epoch snapshot is taken, the mutation lands "while the disk read is in flight", and
     * only then does the load complete with the pre-mutation snapshot. This is the window
     * the initial async load races at startup when the settings screen writes before the
     * first disk read returns.
     */
    @Test
    fun `a load that raced a mutation is discarded instead of clobbering it`() {
        val epochAtLoadStart = StartupSettingsManager.currentMutationEpoch()
        runBlocking {
            StartupSettingsManager.updateSettings(StartupSettings(workspaceLoadTimeoutMs = 5555L))
        }

        // The stale load completes with what the disk held before the mutation.
        StartupSettingsManager.applyLoadedIfUnchanged(
            StartupSettings(workspaceLoadTimeoutMs = 1111L),
            epochAtLoadStart,
        )
        assertEquals(
            5555L,
            StartupSettingsManager.currentSettings.value.workspaceLoadTimeoutMs,
            "a load that raced a mutation must not overwrite the newer change",
        )
        assertTrue(settingsFile.readText().contains("5555"), settingsFile.readText())

        // The fence drops only loads that actually raced: one snapshotted after the last
        // change still publishes, or a reload would silently do nothing forever.
        val epochAfterChange = StartupSettingsManager.currentMutationEpoch()
        StartupSettingsManager.applyLoadedIfUnchanged(
            StartupSettings(workspaceLoadTimeoutMs = 2222L),
            epochAfterChange,
        )
        assertEquals(2222L, StartupSettingsManager.currentSettings.value.workspaceLoadTimeoutMs)

        // A real reload of the disk the mutation persisted lands back on the mutation's
        // value: last write wins, and the state self-heals.
        runBlocking { StartupSettingsManager.loadSettings() }
        assertEquals(5555L, StartupSettingsManager.currentSettings.value.workspaceLoadTimeoutMs)
    }

    @Test
    fun `concurrent updates leave disk at the final in-memory value`() =
        runBlocking {
            coroutineScope {
                launch { StartupSettingsManager.updateSettings(StartupSettings(workspaceLoadTimeoutMs = 1010L)) }
                launch { StartupSettingsManager.updateSettings(StartupSettings(workspaceLoadTimeoutMs = 2020L)) }
            }

            val persisted = settingsFile.readText()
            assertTrue(
                persisted.contains(
                    StartupSettingsManager.currentSettings.value.workspaceLoadTimeoutMs
                        .toString(),
                ),
                "the serialized write order must match the final in-memory update",
            )
        }

    /**
     * Interrupting the old writeText in place could leave a truncated file that the next
     * launch parses as a fresh install. The temp-and-rename write cannot: the target is
     * replaced only by the final atomic move, so a failure at any earlier stage leaves the
     * previous file byte-for-byte intact. Pointing the settings path at a directory makes
     * that final move fail on every platform - a file cannot be renamed over a directory -
     * with the temp stage already complete, the "crash mid-write" shape.
     */
    @Test
    fun `a persistence failure leaves the previous file intact with no partial write`() {
        runBlocking {
            StartupSettingsManager.updateSettings(StartupSettings(workspaceLoadTimeoutMs = 7777L))
        }
        val before = settingsFile.readText()
        assertTrue(before.contains("7777"), "the seeded file should hold the seed value")

        assertTrue(settingsFile.delete(), "the seeded file should be removable")
        assertTrue(settingsFile.mkdir(), "the blocked target must exist as a directory")
        try {
            // Persistence is best-effort: the failure is logged and swallowed, and the
            // in-memory change still stands.
            runBlocking {
                StartupSettingsManager.updateSettings(StartupSettings(workspaceLoadTimeoutMs = 8888L))
            }
            assertEquals(
                8888L,
                StartupSettingsManager.currentSettings.value.workspaceLoadTimeoutMs,
                "the session's change must survive a persistence failure",
            )
        } finally {
            // Restore a regular file so anything reading the path after this sees sane state.
            settingsFile.delete()
            settingsFile.writeText(before)
        }

        assertTrue(settingsFile.isFile, "the existing target must survive the failed write")
        assertEquals(
            before,
            settingsFile.readText(),
            "a failed write must leave the previous settings byte-for-byte intact",
        )
        assertEquals(
            0,
            strayTempFiles().size,
            "a failed write must clean up its temp sibling",
        )
    }
}
