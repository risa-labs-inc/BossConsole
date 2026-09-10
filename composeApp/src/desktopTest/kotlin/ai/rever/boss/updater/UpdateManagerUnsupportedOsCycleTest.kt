package ai.rever.boss.updater

import ai.rever.boss.utils.Version
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BossConsole#119: an install refused as unsupported-OS must be remembered so an automatic
 * check stops re-offering (and re-downloading) that exact version, while a forced/manual check
 * still offers it and a different, newer version is unaffected.
 *
 * Exercised through the real [UpdateManager] check -> download -> install -> recheck path via
 * controlled service operations, not just [UpdateSettings.lastDismissedVersion] or
 * [claimStagedUpdate] in isolation - those already have coverage elsewhere
 * ([StagedUpdateClaimTest]), and neither proves the manager actually wires the two together.
 */
class UpdateManagerUnsupportedOsCycleTest {
    private val currentVersion = Version(major = 9, minor = 5, patch = 2)
    private val refusedVersion = Version(major = 9, minor = 5, patch = 3)
    private val newerVersion = Version(major = 9, minor = 5, patch = 4)

    private var savedDismissed: String? = null
    private lateinit var settingsDir: File
    private var manager: UpdateManager? = null

    @BeforeTest
    fun setUp() {
        settingsDir = createTempDirectory("unsupported-cycle-settings").toFile()
        UpdateSettingsFiles.settingsFileOverride = File(settingsDir, "update-settings.json")
        savedDismissed = UpdateSettings.lastDismissedVersion
        UpdateSettings.lastDismissedVersion = null
    }

    @AfterTest
    fun tearDown() {
        manager?.shutdown()
        UpdateSettings.lastDismissedVersion = savedDismissed
        UpdateSettingsFiles.settingsFileOverride = null
        settingsDir.deleteRecursively()
    }

    private fun infoFor(latest: Version) =
        UpdateInfo(
            available = true,
            currentVersion = currentVersion,
            latestVersion = latest,
            releaseNotes = "",
        )

    @Test
    fun `unsupported-OS refusal suppresses only that version on the next automatic check`() =
        runBlocking {
            var offered = refusedVersion
            var downloads = 0
            val manager =
                UpdateManager(
                    UpdateInstallOperation {
                        InstallOutcome(
                            succeeded = false,
                            errorMessage = "This update requires macOS 26 or later - this Mac runs macOS 14.",
                            failureReason = InstallFailureReason.UnsupportedOs,
                        )
                    },
                    checkOperation = { infoFor(offered) },
                    downloadOperation = { _, _ ->
                        downloads++
                        File(settingsDir, "BOSS-unsupported.dmg").absolutePath
                    },
                ).also { this@UpdateManagerUnsupportedOsCycleTest.manager = it }

            // An automatic check surfaces the refused version normally - nothing dismissed yet.
            manager.checkForUpdates(force = false)
            assertTrue(manager.updateState.value is UpdateState.UpdateAvailable, "baseline: version is on offer")

            // Stage it (as the banner's "Update Now" would) so installUpdate has something to claim.
            manager.downloadUpdate(infoFor(refusedVersion))
            assertTrue(manager.updateState.value is UpdateState.ReadyToInstall, "staged before install")

            // Install refuses it as unsupported-OS.
            val installed = manager.installUpdate(File(settingsDir, "BOSS-unsupported.dmg").absolutePath)
            assertFalse(installed, "an unsupported-OS refusal is not a successful install")

            val errorState = manager.updateState.value
            assertTrue(errorState is UpdateState.Error, "the refusal must stay visible, not reset to Idle")
            assertEquals("This update requires macOS 26 or later - this Mac runs macOS 14.", errorState.message)
            assertEquals(
                refusedVersion.toString(),
                UpdateSettings.lastDismissedVersion,
                "the refused version must be persisted as dismissed",
            )

            manager.dismissDialogOnly()

            // An automatic check must now stay quiet about the exact same version.
            manager.checkForUpdates(force = false)
            assertEquals(
                UpdateState.Idle,
                manager.updateState.value,
                "an automatic check must not re-offer (or re-download) a version already refused as unsupported",
            )

            assertFalse(manager.showUpdateDialog.value)
            assertEquals(1, downloads)

            // A forced/manual check must still offer it - the same bypass a user dismissal gets.
            manager.checkForUpdates(force = true)
            assertTrue(manager.updateState.value is UpdateState.UpdateAvailable, "a forced check bypasses the refusal")

            // A different, newer version must remain eligible on an ordinary automatic check.
            offered = newerVersion
            manager.checkForUpdates(force = false)
            val state = manager.updateState.value
            assertTrue(state is UpdateState.UpdateAvailable, "an unrelated newer version must not be suppressed")
            assertEquals(newerVersion, state.updateInfo.latestVersion)
        }

    @Test
    fun `an ordinary install failure does not persist a dismissal`() =
        runBlocking {
            val manager =
                UpdateManager(
                    UpdateInstallOperation {
                        InstallOutcome(succeeded = false, errorMessage = "Disk full")
                    },
                    checkOperation = { infoFor(refusedVersion) },
                    downloadOperation = { _, _ -> File(settingsDir, "BOSS-ordinary.dmg").absolutePath },
                ).also { this@UpdateManagerUnsupportedOsCycleTest.manager = it }

            manager.checkForUpdates(force = false)
            manager.downloadUpdate(infoFor(refusedVersion))
            manager.installUpdate(File(settingsDir, "BOSS-ordinary.dmg").absolutePath)

            val errorState = manager.updateState.value
            assertTrue(errorState is UpdateState.Error)
            assertEquals("Disk full", errorState.message)
            assertEquals(
                null,
                UpdateSettings.lastDismissedVersion,
                "an ordinary failure must not suppress future checks",
            )

            // The version must still be offered on the very next automatic check.
            manager.checkForUpdates(force = false)
            assertTrue(manager.updateState.value is UpdateState.UpdateAvailable)
        }
}
