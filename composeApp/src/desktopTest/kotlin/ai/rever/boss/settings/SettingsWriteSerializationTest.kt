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
 * Each writer sets a DISTINCT value (not a toggled boolean, which has only two states and so lets a
 * torn or stale write coincide with memory by luck) and the test asserts the file both decodes
 * cleanly and equals the final published state. Without the mutex the concurrent, non-atomic
 * `writeText`s interleave into a file that fails to decode; with the mutex but the encode moved
 * outside the lock, a stale snapshot lands last and the decoded value differs from memory.
 *
 * Each manager resolves its file through `BossDirectories`, which desktop tests redirect to a fresh
 * per-task `user.home`, so this reads the same file the singleton wrote without touching `~/.boss`.
 */
class SettingsWriteSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        const val WRITERS = 64

        // Several rounds so a real race is caught reliably: the fixed code is deterministically
        // correct and passes every round, while one unserialized burst is enough to fail.
        const val ROUNDS = 20
    }

    @Test
    fun `concurrent performance saves leave a complete file matching memory`() =
        runBlocking(Dispatchers.Default) {
            val file = BossDirectories.resolve("performance-settings.json")
            repeat(ROUNDS) { round ->
                // Dispatch every writer before any of them mutates, so the race window is maximal.
                val start = CompletableDeferred<Unit>()
                val jobs =
                    (1..WRITERS).map { index ->
                        async {
                            start.await()
                            // A distinct interval per writer so a stale/torn write is visible. This
                            // field is validated with coerceAtLeast(100), so every value is preserved
                            // distinct (unlike the clamped-to-a-small-max threshold fields).
                            PerformanceSettingsManager.updateSettings(
                                PerformanceSettingsManager.currentSettings.value.copy(
                                    memorySampleIntervalMs = (100 + round * WRITERS + index).toLong(),
                                ),
                            )
                        }
                    }
                start.complete(Unit)
                jobs.awaitAll()

                assertTrue(file.exists(), "settings file should have been written")
                val onDisk = json.decodeFromString<PerformanceSettings>(file.readText())
                assertEquals(
                    PerformanceSettingsManager.currentSettings.value,
                    onDisk,
                    "round $round: the on-disk state must parse and equal the last published state",
                )
            }
        }

    @Test
    fun `concurrent focus-mode saves leave a complete file matching memory`() =
        runBlocking(Dispatchers.Default) {
            val file = BossDirectories.resolve("focus-mode-settings.json")
            repeat(ROUNDS) { round ->
                val start = CompletableDeferred<Unit>()
                val jobs =
                    (1..WRITERS).map { index ->
                        async {
                            start.await()
                            // A distinct reveal-delay value per writer, same reason as above.
                            FocusModeSettingsManager.updateSettings(
                                FocusModeSettingsManager.currentSettings.value.copy(
                                    revealDelayMs = (round * WRITERS + index).toLong(),
                                ),
                            )
                        }
                    }
                start.complete(Unit)
                jobs.awaitAll()

                assertTrue(file.exists(), "settings file should have been written")
                val onDisk =
                    FocusModeSettings.storageJson.decodeFromString(FocusModeSettings.serializer(), file.readText())
                assertEquals(
                    FocusModeSettingsManager.currentSettings.value,
                    onDisk,
                    "round $round: the on-disk state must parse and equal the last published state",
                )
            }
        }
}
