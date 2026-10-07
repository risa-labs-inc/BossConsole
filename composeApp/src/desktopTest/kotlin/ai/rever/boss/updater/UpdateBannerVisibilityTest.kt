package ai.rever.boss.updater

import ai.rever.boss.utils.Version
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [drawsBanner] to the states [UpdateBanner] actually draws for.
 *
 * The layout asks this question to decide where the macOS traffic lights go: a banner that is up is
 * the topmost chrome, so it takes the clearance and the bar or columns below it give theirs up. Get
 * the answer wrong in either direction and the defect is silent and only on macOS - "yes" for a
 * state that draws nothing strips the real clearance and puts the buttons on a tab bar's Favorites
 * shelf; "no" for one that does draw leaves an empty band under the banner, which is the bug this
 * was written for.
 *
 * [drawsBanner] is exhaustive with no `else`, so a NEW state cannot compile without an answer. That
 * covers additions. What it cannot cover is an existing state changing whether it draws, which is
 * what the list below is for.
 */
class UpdateBannerVisibilityTest {
    private val drawing =
        listOf(
            UpdateState.UpdateAvailable(
                UpdateInfo(
                    available = true,
                    currentVersion = Version(major = 9, minor = 4, patch = 32),
                    latestVersion = Version(major = 9, minor = 4, patch = 33),
                    releaseNotes = "",
                    downloadUrl = "https://example.invalid/BOSS.dmg",
                ),
            ),
            UpdateState.Downloading(progress = 0.5f),
            UpdateState.ReadyToInstall(downloadPath = "/tmp/BOSS.dmg"),
            UpdateState.RestartRequired,
            UpdateState.Error(message = "no"),
        )

    private val silent =
        listOf(
            UpdateState.Idle,
            UpdateState.CheckingForUpdates,
            UpdateState.UpToDate,
            UpdateState.Installing,
            UpdateState.InstallOnNextRestart,
        )

    @Test
    fun `every banner state draws`() {
        drawing.forEach { assertTrue(it.drawsBanner(), "$it should draw a banner") }
    }

    @Test
    fun `every other state draws nothing`() {
        silent.forEach { assertFalse(it.drawsBanner(), "$it should draw no banner") }
    }

    @Test
    fun `automatic mode hides banners and update prompts for every state`() {
        (drawing + silent).forEach {
            assertFalse(it.drawsBanner(automaticUpdates = true))
            assertEquals(UpdateState.Idle, it.bannerState(true))
        }
        assertFalse(shouldShowUpdatePrompt(requested = true, automaticUpdates = true))
    }

    @Test
    fun `the two lists cover every state`() {
        // Without this, a state added to UpdateState and answered in drawsBanner would compile and
        // never be tested - the exhaustive `when` forces an answer, not a correct one.
        val covered = (drawing + silent).map { it::class }.toSet()
        val declared = UpdateState::class.sealedSubclasses.toSet()
        assertEquals(declared, covered, "UpdateState members not covered by this test")
    }
}
