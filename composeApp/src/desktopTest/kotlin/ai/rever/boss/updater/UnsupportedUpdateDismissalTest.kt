package ai.rever.boss.updater

import ai.rever.boss.utils.Version
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UnsupportedUpdateDismissalTest {
    private val update =
        UpdateInfo(
            available = true,
            currentVersion = Version(9, 5, 8),
            latestVersion = Version(9, 6, 0),
            releaseNotes = "",
        )

    @Test
    fun `unsupported release is dismissed after installer refuses it`() {
        val outcome =
            InstallOutcome(
                succeeded = false,
                errorMessage = "This update requires macOS 13.0 or later",
                dismissVersion = true,
            )

        assertEquals(update.latestVersion, versionToDismiss(outcome, update))
    }

    @Test
    fun `ordinary install failure remains retryable`() {
        val outcome = InstallOutcome(succeeded = false, errorMessage = "DMG mounting failed")

        assertNull(versionToDismiss(outcome, update))
    }

    @Test
    fun `successful install never dismisses a release`() {
        val outcome = InstallOutcome(succeeded = true, dismissVersion = true)

        assertNull(versionToDismiss(outcome, update))
    }
}
