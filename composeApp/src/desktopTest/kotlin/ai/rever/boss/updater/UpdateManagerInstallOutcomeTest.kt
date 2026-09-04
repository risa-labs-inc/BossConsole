package ai.rever.boss.updater

import ai.rever.boss.utils.Version
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class UpdateManagerInstallOutcomeTest {
    private lateinit var settingsDir: File
    private lateinit var manager: UpdateManager
    private var dismissedBefore: String? = null
    private var installOutcome = InstallOutcome(succeeded = false)
    private var installedPath: String? = null

    @BeforeTest
    fun setUp() {
        settingsDir = createTempDirectory("update-settings-").toFile()
        UpdateSettingsFiles.settingsFileOverride = File(settingsDir, "update-settings.json")
        dismissedBefore = UpdateSettings.lastDismissedVersion
        UpdateSettings.lastDismissedVersion = null
        manager =
            UpdateManager(
                UpdateInstallOperation { downloadPath ->
                    installedPath = downloadPath
                    installOutcome
                },
            )
    }

    @AfterTest
    fun tearDown() {
        manager.shutdown()
        UpdateSettings.lastDismissedVersion = dismissedBefore
        UpdateSettingsFiles.settingsFileOverride = null
        settingsDir.deleteRecursively()
    }

    @Test
    fun `unsupported OS refusal dismisses only that version and keeps the error`() =
        runBlocking {
            val update = update("9.5.9")
            val message = "This update requires macOS 13.0 or later"
            val downloadPath = "C:/updates/BOSS-9.5.9.dmg"
            installOutcome =
                InstallOutcome(
                    succeeded = false,
                    errorMessage = message,
                    failureReason = InstallFailureReason.UnsupportedOs,
                )
            manager.stageDownloadedUpdate(update, downloadPath)

            assertFalse(manager.installUpdate(downloadPath))

            assertEquals(downloadPath, installedPath)
            assertEquals("9.5.9", UpdateSettings.lastDismissedVersion)
            assertTrue(File(settingsDir, "update-settings.json").readText().contains("9.5.9"))
            val state = assertIs<UpdateState.Error>(manager.updateState.value)
            assertEquals(message, state.message)
            assertNotEquals("9.5.10", UpdateSettings.lastDismissedVersion)
        }

    @Test
    fun `generic install failure remains visible without dismissing the version`() =
        runBlocking {
            val update = update("9.5.9")
            val downloadPath = "C:/updates/BOSS-9.5.9.dmg"
            installOutcome = InstallOutcome(succeeded = false, errorMessage = "Could not mount update")
            manager.stageDownloadedUpdate(update, downloadPath)

            assertFalse(manager.installUpdate(downloadPath))

            assertNull(UpdateSettings.lastDismissedVersion)
            assertEquals(downloadPath, installedPath)
            assertEquals("Could not mount update", assertIs<UpdateState.Error>(manager.updateState.value).message)
        }

    private fun update(version: String): UpdateInfo {
        val latest = Version.parse(version)!!
        return UpdateInfo(
            available = true,
            currentVersion = Version.parse("9.5.8")!!,
            latestVersion = latest,
            releaseNotes = "",
        )
    }
}
