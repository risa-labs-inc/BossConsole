package ai.rever.boss.components.plugin

import ai.rever.boss.updater.UpdateInfo
import ai.rever.boss.updater.UpdateState
import ai.rever.boss.utils.Version
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostUpdateRemedyTest {
    private val remedy = PluginLoadRemedy.UpdateHost("9.5.9")
    private val info =
        UpdateInfo(
            available = true,
            currentVersion = Version(9, 5, 8),
            latestVersion = Version(9, 5, 9),
            releaseNotes = "",
            downloadUrl = "https://example.invalid/BOSS.dmg",
        )

    @Test
    fun `available offer starts exactly its advertised download`() {
        val downloads = mutableListOf<UpdateInfo>()
        val result = applyHostUpdateRemedy(remedy, UpdateState.UpdateAvailable(info), downloads::add)
        assertTrue(result.isSuccess)
        assertEquals(listOf(info), downloads)
        assertTrue(result.getOrThrow().contains("9.5.9"))
    }

    @Test
    fun `in-flight states acknowledge progress without starting another download`() {
        val downloading = applyHostUpdateRemedy(remedy, UpdateState.Downloading(0.5f)) { error("duplicate download") }
        val ready =
            applyHostUpdateRemedy(remedy, UpdateState.ReadyToInstall("/tmp/BOSS.dmg")) {
                error("duplicate download")
            }
        assertTrue(downloading.getOrThrow().contains("already downloading"))
        assertTrue(ready.getOrThrow().contains("ready to install"))
    }

    @Test
    fun `changed offer cannot download a version that may fail the plugin floor`() {
        val changed = info.copy(latestVersion = Version(9, 5, 7))
        val result = applyHostUpdateRemedy(remedy, UpdateState.UpdateAvailable(changed)) { error("stale offer") }
        assertTrue(result.isFailure)
    }

    @Test
    fun `installation states do not falsely report an unavailable update`() {
        val installing = applyHostUpdateRemedy(remedy, UpdateState.Installing) { error("duplicate download") }
        val restart = applyHostUpdateRemedy(remedy, UpdateState.RestartRequired) { error("duplicate download") }
        assertTrue(installing.getOrThrow().contains("installing"))
        assertTrue(restart.getOrThrow().contains("Restart BOSS"))
    }

    @Test
    fun `update errors retain the actual failure reason`() {
        val result =
            applyHostUpdateRemedy(remedy, UpdateState.Error("network failure")) {
                error("unexpected download")
            }
        assertTrue(
            result
                .exceptionOrNull()
                ?.message
                .orEmpty()
                .contains("network failure"),
        )
    }

    @Test
    fun `starting an update retains rollback but removes the repeated update action`() {
        val rollback = PluginLoadRemedy.RevertPlugin("1.0.0")
        val remedies = listOf(remedy, rollback)
        assertEquals(remedies, remainingLoadRemedies(remedies, updateStarted = false))
        assertEquals(listOf(rollback), remainingLoadRemedies(remedies, updateStarted = true))
        assertTrue(remainingLoadRemedies(listOf(remedy), updateStarted = true).isEmpty())
    }

    @Test
    fun `unavailable states remain failures and never start downloads`() {
        val states =
            listOf(
                UpdateState.Idle,
                UpdateState.CheckingForUpdates,
                UpdateState.UpToDate,
            )
        states.forEach { state ->
            assertTrue(applyHostUpdateRemedy(remedy, state) { error("unexpected download") }.isFailure, "$state")
        }
    }
}
