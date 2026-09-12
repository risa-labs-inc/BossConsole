package ai.rever.boss.plugin.browser

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Settings > Browser > Downloads > "Warn before downloading executable files".
 *
 * Unlike the four toggles [BrowserSettingsMirrorTest] covers, this one has no system-property
 * mirror: FluckEngine's download handler lives in the host, not a separately classloaded plugin,
 * so a plain read of [BrowserSettings.warnForExecutables] is enough to reach it without a restart.
 *
 * The round-trip tests point [BrowserSettingsManager.settingsFile] at a temp file - the same
 * seam [DefaultAppsSettingsManager] uses - so they exercise the real saveSettings/load path
 * rather than a re-statement of the assignment.
 */
class BrowserSettingsWarnForExecutablesTest {
    @TempDir
    lateinit var dir: File

    private lateinit var originalFile: File

    // The whole persisted shape, snapshotted and restored per test. reloadForTest() rewrites
    // every field the manager applies - including the four that mirror to system properties -
    // so restoring one field would leave the other ten (and their mirrors) at whatever a temp
    // file last loaded.
    private lateinit var original: PersistedState

    private data class PersistedState(
        val userAgent: String?,
        val customUserAgent: String?,
        val currentProfile: String,
        val availableProfiles: List<String>,
        val maxInitRetries: Int,
        val maxRecoveryAttempts: Int,
        val discretePasswordFill: Boolean,
        val suggestPasswords: Boolean,
        val offerToSavePasswords: Boolean,
        val showShareButton: Boolean,
        val warnForExecutables: Boolean,
    )

    private fun snapshot() =
        PersistedState(
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

    @BeforeEach
    fun pointAtTempFile() {
        // Reading settingsFile first may trigger the manager's init, which loads the REAL
        // ~/.boss/browser-settings.json. The snapshot must come after that read: a value
        // captured at field-initialization time would hold the pre-load default (the same
        // trap BrowserSettingsMirrorTest documents) and the restore would then clobber a
        // developer's own settings.
        originalFile = BrowserSettingsManager.settingsFile
        original = snapshot()
        BrowserSettingsManager.settingsFile = File(dir, "browser-settings.json")
    }

    @AfterEach
    fun restore() {
        BrowserSettingsManager.settingsFile = originalFile
        with(original) {
            BrowserSettings.userAgent = userAgent
            BrowserSettings.customUserAgent = customUserAgent
            BrowserSettings.currentProfile = currentProfile
            BrowserSettings.availableProfiles.clear()
            BrowserSettings.availableProfiles.addAll(availableProfiles)
            BrowserSettings.maxInitRetries = maxInitRetries
            BrowserSettings.maxRecoveryAttempts = maxRecoveryAttempts
            BrowserSettings.discretePasswordFill = discretePasswordFill
            BrowserSettings.suggestPasswords = suggestPasswords
            BrowserSettings.offerToSavePasswords = offerToSavePasswords
            BrowserSettings.showShareButton = showShareButton
            BrowserSettings.warnForExecutables = warnForExecutables
        }
    }

    @Test
    fun `the persisted shape defaults to on`() {
        // The declared in-memory default cannot be observed after init has loaded the real
        // settings file, so this pins the shape's default - the half that decides what an
        // upgrade whose file lacks the key loads as.
        assertTrue(BrowserSettingsData().warnForExecutables)
    }

    @Test
    fun `both values survive the real save and load`() {
        for (value in listOf(false, true)) {
            BrowserSettings.warnForExecutables = value
            runBlocking { BrowserSettingsManager.saveSettings() }
            // Move the in-memory value away first, so the assertion measures the reload
            // rather than the assignment: deleting the load-apply in BrowserSettingsManager
            // must fail this test.
            BrowserSettings.warnForExecutables = !value
            BrowserSettingsManager.reloadForTest()
            assertEquals(
                value,
                BrowserSettings.warnForExecutables,
                "warnForExecutables=$value did not survive saveSettings + reload",
            )
        }
    }

    @Test
    fun `a settings file predating the key loads as on, so an upgrade keeps the warning`() {
        BrowserSettings.warnForExecutables = false
        // The shape an earlier build wrote: every field except warnForExecutables.
        BrowserSettingsManager.settingsFile.writeText(
            """
            {
                "currentProfile": "browser-profile",
                "availableProfiles": [
                    "browser-profile"
                ]
            }
            """.trimIndent(),
        )
        BrowserSettingsManager.reloadForTest()
        assertTrue(BrowserSettings.warnForExecutables, "a file without the key must load as on")
    }

    @Test
    fun `a corrupt settings file does not crash the load or clobber the in-memory value`() {
        BrowserSettings.warnForExecutables = false
        BrowserSettingsManager.settingsFile.writeText("{ not json")
        BrowserSettingsManager.reloadForTest()
        assertFalse(BrowserSettings.warnForExecutables, "a failed load must keep the in-memory value")
    }
}
