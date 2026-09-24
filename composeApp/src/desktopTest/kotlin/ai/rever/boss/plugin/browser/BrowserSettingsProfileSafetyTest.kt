package ai.rever.boss.plugin.browser

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

class BrowserSettingsProfileSafetyTest {
    @TempDir
    lateinit var dir: File

    private lateinit var originalFile: File
    private lateinit var original: BrowserSettingsData
    private val json = Json { prettyPrint = true }

    @BeforeEach
    fun pointAtTempFile() {
        originalFile = BrowserSettingsManager.settingsFile
        original = snapshot()
        BrowserSettingsManager.settingsFile = File(dir, "browser-settings.json")
    }

    @AfterEach
    fun restore() {
        BrowserSettingsManager.settingsFile = originalFile
        apply(original)
    }

    @Test
    fun `load drops traversal values and falls back to the default profile`() {
        val corrupt =
            original.copy(
                currentProfile = "../../Documents",
                availableProfiles =
                    listOf(
                        "../../Documents",
                        "..\\..\\Documents",
                        "browser-profile-work",
                        "browser-profile-work",
                    ),
            )
        BrowserSettingsManager.settingsFile.writeText(json.encodeToString(corrupt))

        BrowserSettingsManager.reloadForTest()

        assertEquals(BrowserProfilePaths.DEFAULT_PROFILE_ID, BrowserSettings.currentProfile)
        assertEquals(
            listOf("browser-profile", "browser-profile-work"),
            BrowserSettings.availableProfiles,
        )
    }

    @Test
    fun `save refuses to persist invalid in-memory profile values`() {
        BrowserSettings.currentProfile = "/tmp/outside"
        BrowserSettings.availableProfiles.clear()
        BrowserSettings.availableProfiles.addAll(listOf("/tmp/outside", "browser-profile-safe"))

        runBlocking { BrowserSettingsManager.saveSettings() }

        val saved = json.decodeFromString<BrowserSettingsData>(BrowserSettingsManager.settingsFile.readText())
        assertEquals(BrowserProfilePaths.DEFAULT_PROFILE_ID, saved.currentProfile)
        assertEquals(listOf("browser-profile", "browser-profile-safe"), saved.availableProfiles)
    }

    private fun snapshot() =
        BrowserSettingsData(
            userAgent = BrowserSettings.userAgent,
            customUserAgent = BrowserSettings.customUserAgent,
            currentProfile = BrowserSettings.currentProfile,
            availableProfiles = BrowserSettings.availableProfiles.toList(),
            maxInitRetries = BrowserSettings.maxInitRetries,
            maxRecoveryAttempts = BrowserSettings.maxRecoveryAttempts,
            discretePasswordFill = BrowserSettings.discretePasswordFill,
            suggestPasswords = BrowserSettings.suggestPasswords,
            offerToSavePasswords = BrowserSettings.offerToSavePasswords,
            showShareButton = BrowserSettings.showShareButton,
            warnForExecutables = BrowserSettings.warnForExecutables,
        )

    private fun apply(settings: BrowserSettingsData) {
        BrowserSettings.userAgent = settings.userAgent
        BrowserSettings.customUserAgent = settings.customUserAgent
        BrowserSettings.currentProfile = settings.currentProfile
        BrowserSettings.availableProfiles.clear()
        BrowserSettings.availableProfiles.addAll(settings.availableProfiles)
        BrowserSettings.maxInitRetries = settings.maxInitRetries
        BrowserSettings.maxRecoveryAttempts = settings.maxRecoveryAttempts
        BrowserSettings.discretePasswordFill = settings.discretePasswordFill
        BrowserSettings.suggestPasswords = settings.suggestPasswords
        BrowserSettings.offerToSavePasswords = settings.offerToSavePasswords
        BrowserSettings.showShareButton = settings.showShareButton
        BrowserSettings.warnForExecutables = settings.warnForExecutables
    }
}
