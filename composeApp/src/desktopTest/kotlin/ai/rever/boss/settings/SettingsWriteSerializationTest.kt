package ai.rever.boss.settings

import ai.rever.boss.focusmode.FocusModeSettings
import ai.rever.boss.focusmode.FocusModeSettingsManager
import ai.rever.boss.performance.PerformanceSettings
import ai.rever.boss.performance.PerformanceSettingsManager
import ai.rever.boss.plugin.pathutils.BossDirectories
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The settings managers serialize their writes with a `Mutex` and encode the current state inside
 * the lock, so overlapping saves cannot race each other into a torn or stale file. Verified on the
 * two managers that load synchronously in their init (no async load to race the test's writes);
 * the write path is identical across the manager-less managers.
 *
 * Each manager resolves its file through `BossDirectories`, which desktop tests redirect to a fresh
 * per-task `user.home`, so this reads the same file the singleton wrote without touching `~/.boss`.
 */
class SettingsWriteSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `concurrent performance saves leave a complete file matching memory`() =
        runBlocking(Dispatchers.Default) {
            // Dispatch every writer before any of them mutates, so the race window is maximal.
            val start = CompletableDeferred<Unit>()
            val jobs =
                (1..40).map { index ->
                    async {
                        start.await()
                        PerformanceSettingsManager.updateSettings(
                            PerformanceSettingsManager.currentSettings.value.copy(showIndicator = index % 2 == 0),
                        )
                    }
                }
            start.complete(Unit)
            jobs.awaitAll()

            val file = BossDirectories.resolve("performance-settings.json")
            assertTrue(file.exists(), "settings file should have been written")
            val onDisk = json.decodeFromString<PerformanceSettings>(file.readText())
            assertEquals(
                PerformanceSettingsManager.currentSettings.value,
                onDisk,
                "the on-disk state must parse and equal the last published state",
            )
        }

    @Test
    fun `concurrent focus-mode saves leave a complete file matching memory`() =
        runBlocking(Dispatchers.Default) {
            val start = CompletableDeferred<Unit>()
            val jobs =
                (1..40).map { index ->
                    async {
                        start.await()
                        FocusModeSettingsManager.updateSettings(
                            FocusModeSettingsManager.currentSettings.value.copy(enabled = index % 2 == 0),
                        )
                    }
                }
            start.complete(Unit)
            jobs.awaitAll()

            val file = BossDirectories.resolve("focus-mode-settings.json")
            assertTrue(file.exists(), "settings file should have been written")
            val onDisk = FocusModeSettings.storageJson.decodeFromString(FocusModeSettings.serializer(), file.readText())
            assertEquals(
                FocusModeSettingsManager.currentSettings.value,
                onDisk,
                "the on-disk state must parse and equal the last published state",
            )
        }
}
