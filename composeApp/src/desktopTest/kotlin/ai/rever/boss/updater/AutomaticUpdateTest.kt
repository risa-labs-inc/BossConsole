package ai.rever.boss.updater

import ai.rever.boss.utils.Version
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutomaticUpdateTest {
    private val originalAutomatic = UpdateSettings.autoUpdateEnabled
    private val originalDismissed = UpdateSettings.lastDismissedVersion
    private val managers = mutableListOf<UpdateManager>()
    private val info =
        UpdateInfo(
            true,
            Version(1, 0, 0),
            Version(1, 1, 0),
            "",
            "https://example.invalid/BOSS.dmg",
        )

    @AfterTest
    fun cleanup() {
        managers.forEach { it.shutdown() }
        UpdateSettings.autoUpdateEnabled = originalAutomatic
        UpdateSettings.lastDismissedVersion = originalDismissed
    }

    private fun manager(
        download: suspend (UpdateInfo, (Float) -> Unit) -> String? = { _, _ -> "/tmp/BOSS.dmg" },
        schedule: suspend (String) -> InstallOutcome = { InstallOutcome(true) },
    ): UpdateManager =
        UpdateManager(
            installOperation =
                UpdateInstallOperation {
                    error("Automatic mode must not invoke immediate installation")
                },
            checkOperation = { info },
            downloadOperation = download,
            scheduleOperation = UpdateInstallOperation(schedule),
        ).also { managers += it }

    private suspend fun awaitState(
        manager: UpdateManager,
        predicate: (UpdateState) -> Boolean,
    ) {
        withTimeout(5_000) {
            while (!predicate(manager.updateState.value)) delay(10)
        }
    }

    @Test
    fun `release defaults on debug defaults off and test flag opts in`() {
        assertTrue(defaultAutoUpdateEnabled("release", false))
        assertFalse(defaultAutoUpdateEnabled("debug", false))
        assertFalse(defaultAutoUpdateEnabled(null, false))
        assertTrue(defaultAutoUpdateEnabled("debug", true))
    }

    @Test
    fun `explicit disabled preference survives serialization`() {
        val json = Json { encodeDefaults = true }
        val encoded =
            json.encodeToString(
                UpdateSettingsData.serializer(),
                UpdateSettingsData(autoUpdateEnabled = false),
            )
        assertTrue(encoded.contains("\"autoUpdateEnabled\":false"))
        assertFalse(json.decodeFromString(UpdateSettingsData.serializer(), encoded).autoUpdateEnabled)
    }

    @Test
    fun `automatic mode downloads once and schedules without an immediate install or prompt`() =
        runBlocking {
            UpdateSettings.autoUpdateEnabled = true
            UpdateSettings.lastDismissedVersion = null
            val scheduled = AtomicInteger()
            val manager =
                manager(schedule = {
                    scheduled.incrementAndGet()
                    InstallOutcome(true)
                })
            manager.startAutomaticUpdates()
            manager.startAutomaticUpdates()
            awaitState(manager) { it == UpdateState.InstallOnNextRestart }
            assertEquals(1, scheduled.get())
            assertFalse(manager.showUpdateDialog.value)
            manager.checkForUpdates(force = true)
            manager.resetState()
            assertEquals(UpdateState.InstallOnNextRestart, manager.updateState.value)
            assertEquals(1, scheduled.get())
            assertTrue(manager.downloadUpdate(info) is UpdateResult.Error)
        }

    @Test
    fun `disabled automatic mode retains the manual update offer`() =
        runBlocking {
            UpdateSettings.autoUpdateEnabled = false
            UpdateSettings.lastDismissedVersion = null
            val manager = manager(download = { _, _ -> error("Disabled mode must not download") })
            manager.checkForUpdates()
            assertTrue(manager.updateState.value is UpdateState.UpdateAvailable)
            assertTrue(manager.showUpdateDialog.value)
        }

    @Test
    fun `enabling automatic mode begins downloading without restarting the app`() =
        runBlocking {
            UpdateSettings.autoUpdateEnabled = false
            UpdateSettings.lastDismissedVersion = null
            val manager = manager()
            manager.startAutomaticUpdates()
            UpdateSettings.autoUpdateEnabled = true
            awaitState(manager) { it == UpdateState.InstallOnNextRestart }
            assertFalse(manager.showUpdateDialog.value)
        }

    @Test
    fun `enabling automatic mode schedules an already downloaded newer update`() =
        runBlocking {
            UpdateSettings.autoUpdateEnabled = false
            UpdateSettings.lastDismissedVersion = null
            val manager = manager(download = { _, _ -> error("The staged update must not be downloaded again") })
            manager.stageDownloadedUpdate(info, "/tmp/BOSS.dmg")
            manager.startAutomaticUpdates()
            UpdateSettings.autoUpdateEnabled = true
            awaitState(manager) { it == UpdateState.InstallOnNextRestart }
        }

    @Test
    fun `disabling while downloading leaves the artifact available for manual installation`() =
        runBlocking {
            UpdateSettings.autoUpdateEnabled = true
            UpdateSettings.lastDismissedVersion = null
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val manager =
                manager(
                    download = { _, _ ->
                        started.complete(Unit)
                        finish.await()
                        "/tmp/BOSS.dmg"
                    },
                    schedule = { error("Disabled mode must not schedule an installer") },
                )
            manager.startAutomaticUpdates()
            withTimeout(5_000) { started.await() }
            UpdateSettings.autoUpdateEnabled = false
            finish.complete(Unit)
            awaitState(manager) { it is UpdateState.ReadyToInstall }
            assertTrue(manager.updateState.value is UpdateState.ReadyToInstall)
        }

    @Test
    fun `download failure remains visible in settings without scheduling installation`() =
        runBlocking {
            UpdateSettings.autoUpdateEnabled = true
            UpdateSettings.lastDismissedVersion = null
            val manager = manager(download = { _, _ -> null }, schedule = { error("No artifact to install") })
            manager.startAutomaticUpdates()
            awaitState(manager) { it is UpdateState.Error }
            assertFalse(manager.showUpdateDialog.value)
            assertFalse(manager.updateState.value.drawsBanner(automaticUpdates = true))
        }
}
