package ai.rever.boss.run

import ai.rever.boss.plugin.run.Language
import ai.rever.boss.plugin.run.RunConfiguration
import ai.rever.boss.plugin.run.RunConfigurationSettings
import ai.rever.boss.plugin.run.RunConfigurationType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Concurrency and persistence tests for [RunConfigurationManager].
 *
 * Regression coverage for the lost-update races and torn writes fixed in
 * [ai.rever.boss.run.RunConfigurationManager] (issue #754, landed as #755):
 * concurrent mutations (add, update, remove) must serialize under the settings
 * mutex without dropping configurations, and every completed mutation must be
 * persisted atomically so the decoded on-disk state always matches memory.
 *
 * Each test runs against a hermetic temp file via [RunConfigurationManager.resetForTesting]
 * and restores the singleton to the real settings file when it finishes, so other tests in
 * the same JVM (which use the real file) are unaffected.
 *
 * Hardening follow-ups (#754): update-vs-update interleavings on the same and on different
 * configurations, persistence failures injected at the temp-file and replace stages (the
 * previous settings file must survive byte-for-byte), and a concurrent reader that must
 * never observe a torn file while updates and saves are in flight.
 */
class RunConfigurationConcurrencyTest {
    private lateinit var tempDir: File
    private lateinit var tempFile: File
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    @BeforeTest
    fun setUp() {
        // A private directory: the file starts non-existent, and teardown is a single
        // recursive delete instead of a scan of the shared system temp dir.
        tempDir = Files.createTempDirectory("run-config-test-").toFile()
        tempFile = File(tempDir, "run-configurations.json")
        RunConfigurationManager.resetForTesting(tempFile)
    }

    @AfterTest
    fun tearDown() {
        // Point the singleton back at the user's file and reload, so tests that run after
        // this class observe the same state the app would. With no argument this restores the
        // production path captured once in the manager, so the literal cannot drift.
        RunConfigurationManager.resetForTesting()
        // atomicWriteText writes a unique sibling "<name>.<random>.tmp" inside tempDir and
        // moves it into place; only a JVM kill mid-write could leave one behind.
        tempDir.deleteRecursively()
    }

    private fun createConfig(index: Int) =
        RunConfiguration(
            id = "config-$index",
            name = "Main $index",
            type = RunConfigurationType.MAIN_FUNCTION,
            filePath = "/path/to/project/src/Main$index.kt",
            lineNumber = index,
            language = Language.KOTLIN,
            command = "run $index",
            workingDirectory = "/path/to/project",
            // Keep round-trip assertions independent of the timestamp's time-varying default.
            timestamp = index.toLong(),
        )

    @Test
    fun `concurrent additions do not drop configurations`() =
        runBlocking(Dispatchers.Default) {
            val count = 50
            // All coroutines are dispatched before any of them mutates, so the race window is
            // maximal even on low-core runners where the dispatcher would otherwise serialize
            // them; same start-gate pattern as RunConfigurationPersistenceTest.
            val start = CompletableDeferred<Unit>()
            val jobs =
                (1..count).map { index ->
                    async {
                        start.await()
                        RunConfigurationManager.addConfiguration(createConfig(index))
                    }
                }
            start.complete(Unit)
            jobs.awaitAll()

            val inMemoryConfigs = RunConfigurationManager.currentSettings.value.configurations
            assertEquals(
                count,
                inMemoryConfigs.size,
                "Expected all $count configurations to be retained in memory without race overwrites",
            )

            // Verify disk persistence
            assertTrue(tempFile.exists(), "Settings file should have been created on disk")
            val diskContent = tempFile.readText()
            val diskSettings = json.decodeFromString<RunConfigurationSettings>(diskContent)
            assertEquals(
                count,
                diskSettings.configurations.size,
                "Expected all $count configurations to be persisted to disk",
            )
        }

    @Test
    fun `concurrent duplicate additions add only once`() =
        runBlocking(Dispatchers.Default) {
            val duplicateConfig = createConfig(1)
            val count = 20
            val start = CompletableDeferred<Unit>()
            val jobs =
                (1..count).map {
                    async {
                        start.await()
                        RunConfigurationManager.addConfiguration(duplicateConfig)
                    }
                }
            start.complete(Unit)
            jobs.awaitAll()

            val inMemoryConfigs = RunConfigurationManager.currentSettings.value.configurations
            assertEquals(
                1,
                inMemoryConfigs.size,
                "Duplicate filePath configurations should be deduplicated under concurrency",
            )

            assertTrue(tempFile.exists(), "Settings file should have been created on disk")
            val diskContent = tempFile.readText()
            val diskSettings = json.decodeFromString<RunConfigurationSettings>(diskContent)
            assertEquals(1, diskSettings.configurations.size)
        }

    @Test
    fun `concurrent mixed add update and remove maintain integrity`() =
        runBlocking(Dispatchers.Default) {
            // Seed with 20 initial configurations
            val initialCount = 20
            for (i in 1..initialCount) {
                RunConfigurationManager.addConfiguration(createConfig(i))
            }
            assertEquals(initialCount, RunConfigurationManager.currentSettings.value.configurations.size)

            // Concurrently perform:
            // 1. Add 10 new configs (indices 21..30)
            // 2. Update 5 existing configs (indices 1..5)
            // 3. Remove 5 existing configs (indices 6..10)
            val start = CompletableDeferred<Unit>()
            val addJobs =
                (21..30).map { i ->
                    async {
                        start.await()
                        RunConfigurationManager.addConfiguration(createConfig(i))
                    }
                }
            val updateJobs =
                (1..5).map { i ->
                    async {
                        start.await()
                        RunConfigurationManager.updateConfiguration(
                            createConfig(i).copy(command = "updated-command-$i"),
                        )
                    }
                }
            val removeJobs =
                (6..10).map { i ->
                    async {
                        start.await()
                        RunConfigurationManager.removeConfiguration("config-$i")
                    }
                }

            start.complete(Unit)
            (addJobs + updateJobs + removeJobs).awaitAll()

            val finalConfigs = RunConfigurationManager.currentSettings.value.configurations
            // Expected size: 20 initial + 10 added - 5 removed = 25
            assertEquals(25, finalConfigs.size, "Final count should accurately reflect additions and removals")

            // Verify updates were applied
            for (i in 1..5) {
                val updated = finalConfigs.find { it.id == "config-$i" }
                assertEquals("updated-command-$i", updated?.command)
            }

            // Verify removals were applied
            for (i in 6..10) {
                val removed = finalConfigs.find { it.id == "config-$i" }
                assertNull(removed)
            }

            // Verify disk persistence matches in-memory state
            assertTrue(tempFile.exists(), "Settings file should have been created on disk")
            val diskContent = tempFile.readText()
            val diskSettings = json.decodeFromString<RunConfigurationSettings>(diskContent)
            assertEquals(25, diskSettings.configurations.size)
        }

    @Test
    fun `concurrent removeConfiguration races updateConfiguration on same configuration`() =
        runBlocking(Dispatchers.Default) {
            for (round in 1..10) {
                val config = createConfig(100 + round)
                RunConfigurationManager.addConfiguration(config)
                assertEquals(1, RunConfigurationManager.currentSettings.value.configurations.size)

                // Race remove vs update on the exact same configuration
                val start = CompletableDeferred<Unit>()
                val removeJob =
                    async {
                        start.await()
                        RunConfigurationManager.removeConfiguration(config.id)
                    }
                val updateJob =
                    async {
                        start.await()
                        RunConfigurationManager.updateConfiguration(
                            config.copy(command = "updated-command-round-$round"),
                        )
                    }

                start.complete(Unit)
                awaitAll(removeJob, updateJob)

                val configs = RunConfigurationManager.currentSettings.value.configurations
                // Under serialized execution:
                // - If remove runs first, update's map finds nothing to replace -> configs is empty.
                // - If update runs first, update replaces config, then remove filters it out -> configs is empty.
                // In an unsynchronized race without the mutex, update can overwrite remove's
                // result and resurrect the config.
                assertEquals(
                    0,
                    configs.size,
                    "Configuration should be cleanly removed regardless of race order, round $round",
                )

                assertTrue(tempFile.exists(), "Settings file should have been written on disk")
                val diskSettings = json.decodeFromString<RunConfigurationSettings>(tempFile.readText())
                assertEquals(0, diskSettings.configurations.size)

                // Explicitly confirm updateConfiguration on an already-deleted ID:
                // - Completes normally without throwing any exception
                // - Does not resurrect or insert the missing configuration (safe no-op)
                RunConfigurationManager.updateConfiguration(config)
                assertEquals(
                    0,
                    RunConfigurationManager.currentSettings.value.configurations.size,
                    "updateConfiguration on deleted ID must safely no-op without inserting or modifying",
                )
            }
        }

    @Test
    fun `lastUsedConfigId is cleared when config is removed mid-race with update to different config`() =
        runBlocking(Dispatchers.Default) {
            for (round in 1..10) {
                val configA = createConfig(1000 + round)
                val configB = createConfig(2000 + round)

                // Initialize with configA and configB, with lastUsedConfigId pointing to configA
                val initialSettings =
                    RunConfigurationSettings(
                        configurations = listOf(configA, configB),
                        lastUsedConfigId = configA.id,
                        recentConfigIds = listOf(configA.id, configB.id),
                    )
                tempFile.writeText(json.encodeToString(RunConfigurationSettings.serializer(), initialSettings))
                RunConfigurationManager.resetForTesting(tempFile)

                assertEquals(configA.id, RunConfigurationManager.currentSettings.value.lastUsedConfigId)

                // Race: remove configA while concurrently updating configB
                val start = CompletableDeferred<Unit>()
                val removeJob =
                    async {
                        start.await()
                        RunConfigurationManager.removeConfiguration(configA.id)
                    }
                val updateJob =
                    async {
                        start.await()
                        RunConfigurationManager.updateConfiguration(
                            configB.copy(command = "updated-b-$round"),
                        )
                    }

                start.complete(Unit)
                awaitAll(removeJob, updateJob)

                val finalSettings = RunConfigurationManager.currentSettings.value
                // Verify lastUsedConfigId was cleared (not resurrected by configB update)
                assertNull(
                    finalSettings.lastUsedConfigId,
                    "lastUsedConfigId must be cleared despite a concurrent update to configB (round $round)",
                )

                // Verify recentConfigIds no longer contains configA
                assertTrue(configA.id !in finalSettings.recentConfigIds)
                assertTrue(configB.id in finalSettings.recentConfigIds)

                // Verify configA is gone and configB was updated
                assertEquals(1, finalSettings.configurations.size)
                assertEquals(configB.id, finalSettings.configurations[0].id)
                assertEquals("updated-b-$round", finalSettings.configurations[0].command)

                // Verify disk persistence matches in-memory state
                assertTrue(tempFile.exists(), "Settings file should have been written on disk")
                val diskSettings = json.decodeFromString<RunConfigurationSettings>(tempFile.readText())
                assertNull(diskSettings.lastUsedConfigId)
                assertEquals(1, diskSettings.configurations.size)
            }
        }

    @Test
    fun `loadSettingsSync gracefully handles corrupt json without crashing`() {
        tempFile.writeText("{ corrupted unparseable json content ... ")
        RunConfigurationManager.resetForTesting(tempFile)

        val settings = RunConfigurationManager.currentSettings.value
        assertEquals(0, settings.configurations.size, "Corrupt file should recover to empty configurations")
    }

    @Test
    fun `concurrent updates of the same configuration apply exactly one whole payload`() =
        runBlocking(Dispatchers.Default) {
            for (round in 1..25) {
                val seeded = createConfig(3000 + round)
                val bystander = createConfig(3100 + round)
                RunConfigurationManager.addConfiguration(seeded)
                RunConfigurationManager.addConfiguration(bystander)

                // Two writers race to replace the SAME configuration with different complete
                // payloads. The paired fields tell a whole payload apart from a hypothetical
                // field-level merge of both writes.
                val payloadA = seeded.copy(command = "alpha-round-$round", workingDirectory = "/wd/alpha")
                val payloadB = seeded.copy(command = "beta-round-$round", workingDirectory = "/wd/beta")

                val start = CompletableDeferred<Unit>()
                val first =
                    async {
                        start.await()
                        RunConfigurationManager.updateConfiguration(payloadA)
                    }
                val second =
                    async {
                        start.await()
                        RunConfigurationManager.updateConfiguration(payloadB)
                    }
                start.complete(Unit)
                awaitAll(first, second)

                val configs = RunConfigurationManager.currentSettings.value.configurations
                assertEquals(
                    2,
                    configs.size,
                    "round $round: the race must not duplicate the updated configuration",
                )
                val updated = configs.single { it.id == seeded.id }
                val isWholePayloadA =
                    updated.command == payloadA.command && updated.workingDirectory == payloadA.workingDirectory
                val isWholePayloadB =
                    updated.command == payloadB.command && updated.workingDirectory == payloadB.workingDirectory
                val describe =
                    "round $round: command=${updated.command} workingDirectory=${updated.workingDirectory}"
                assertTrue(
                    isWholePayloadA || isWholePayloadB,
                    "round $round: the survivor must be exactly one whole payload, not a merge ($describe)",
                )
                assertEquals(
                    bystander,
                    configs.single { it.id == bystander.id },
                    "round $round: a same-configuration race must not disturb the bystander",
                )

                // Whatever won in memory must be exactly what was persisted.
                val diskSettings = json.decodeFromString<RunConfigurationSettings>(tempFile.readText())
                assertEquals(RunConfigurationManager.currentSettings.value, diskSettings)

                // Remove this round's entries so the next round starts from a clean list.
                RunConfigurationManager.removeConfiguration(seeded.id)
                RunConfigurationManager.removeConfiguration(bystander.id)
            }
        }

    @Test
    fun `interleaved updates of different configurations lose neither writer`() =
        runBlocking(Dispatchers.Default) {
            // Issue #754's report: an update to one configuration silently reverted a DIFFERENT
            // configuration written concurrently, because each update rebuilt the whole list
            // from a possibly stale snapshot. Two sustained writers plus an untouched bystander
            // pin the fixed read-modify-write: every serialized update starts from latest state.
            val configA = createConfig(5001)
            val configB = createConfig(5002)
            val bystander = createConfig(5003)
            RunConfigurationManager.addConfiguration(configA)
            RunConfigurationManager.addConfiguration(configB)
            RunConfigurationManager.addConfiguration(bystander)

            val rounds = 12
            val start = CompletableDeferred<Unit>()
            val writerA =
                async {
                    start.await()
                    repeat(rounds) { index ->
                        RunConfigurationManager.updateConfiguration(
                            configA.copy(command = "a-${index + 1}"),
                        )
                    }
                }
            val writerB =
                async {
                    start.await()
                    repeat(rounds) { index ->
                        RunConfigurationManager.updateConfiguration(
                            configB.copy(command = "b-${index + 1}"),
                        )
                    }
                }
            start.complete(Unit)
            awaitAll(writerA, writerB)

            val finalConfigs = RunConfigurationManager.currentSettings.value.configurations
            assertEquals(3, finalConfigs.size, "Neither writer may add, drop, or duplicate entries")
            assertEquals(
                "a-$rounds",
                finalConfigs.single { it.id == configA.id }.command,
                "Writer A's final update must survive writer B's concurrent updates",
            )
            assertEquals(
                "b-$rounds",
                finalConfigs.single { it.id == configB.id }.command,
                "Writer B's final update must survive writer A's concurrent updates",
            )
            assertEquals(
                bystander,
                finalConfigs.single { it.id == bystander.id },
                "An untouched configuration must not be clobbered by either writer",
            )

            // The persisted state must be the same complete snapshot memory ended on.
            val diskSettings = json.decodeFromString<RunConfigurationSettings>(tempFile.readText())
            assertEquals(RunConfigurationManager.currentSettings.value, diskSettings)
        }

    @Test
    fun `a persistence failure at the temp-file stage leaves the previous file intact`() {
        // Removing the parent directory's write permission makes File.createTempFile - the
        // first step of atomicWriteText - fail before the target is ever touched. POSIX
        // permission bits are what actually block the creation, so this only runs where they
        // are enforced; the replace-stage test below still covers failed-write cleanup everywhere.
        assumeTrue(!System.getProperty("os.name").lowercase().contains("win"), "POSIX-only failure injection")

        val seeded = createConfig(6001)
        runBlocking {
            RunConfigurationManager.addConfiguration(seeded)
        }
        val before = tempFile.readText()
        assertTrue(before.isNotBlank(), "The seeded settings file should hold valid JSON")

        val permissionChanged = tempDir.setWritable(false)
        try {
            val probe =
                runCatching {
                    File.createTempFile("permission-probe-", ".tmp", tempDir).also { it.delete() }
                }
            assumeTrue(
                permissionChanged && probe.isFailure,
                "This environment does not enforce the unwritable-directory failure injection",
            )
            // Persistence failures are logged and swallowed (best-effort persistence): the
            // caller must not see an exception.
            runBlocking {
                RunConfigurationManager.updateConfiguration(seeded.copy(command = "never-persisted"))
            }
        } finally {
            tempDir.setWritable(true)
        }

        assertEquals(
            before,
            tempFile.readText(),
            "A failed write must leave the previous settings byte-for-byte intact",
        )
        // The survivor still decodes cleanly: no truncated or otherwise torn JSON remains.
        val diskSettings = json.decodeFromString<RunConfigurationSettings>(tempFile.readText())
        assertEquals(listOf(seeded.id), diskSettings.configurations.map { it.id })
        // The edit lives on in memory: persistence failed, the session's change did not.
        assertEquals(
            "never-persisted",
            RunConfigurationManager.currentSettings.value.configurations
                .single { it.id == seeded.id }
                .command,
        )
        // atomicWriteText failed before creating its temp sibling, so nothing was left behind.
        val strayTempFiles = tempDir.listFiles { file -> file.name.endsWith(".tmp") }.orEmpty()
        assertEquals(0, strayTempFiles.size, "A failed write must not leave a temp file behind")
    }

    @Test
    fun `a persistence failure at the replace stage leaves no partial state or temp litter`() {
        // The settings path points at an existing directory. atomicWriteText still creates and
        // fills its temp sibling, but the atomic replace of the target then fails on every
        // platform (a file cannot be renamed over a directory) - the "crash mid-write" shape
        // with the temp stage already completed.
        val blockedTarget = File(tempDir, "blocked-target")
        assertTrue(blockedTarget.mkdir(), "The blocked target must start as an empty directory")
        RunConfigurationManager.resetForTesting(blockedTarget)

        val seeded = createConfig(7001)
        runBlocking {
            // Both calls hit the failing write; neither may throw or leave memory torn.
            RunConfigurationManager.addConfiguration(seeded)
            RunConfigurationManager.updateConfiguration(seeded.copy(command = "cannot-persist"))
        }

        assertTrue(blockedTarget.isDirectory, "The blocked target must be left untouched")
        assertEquals(
            1,
            RunConfigurationManager.currentSettings.value.configurations.size,
            "Memory must still apply the session's edits while persistence keeps failing",
        )
        assertEquals(
            "cannot-persist",
            RunConfigurationManager.currentSettings.value.configurations
                .single()
                .command,
        )
        // The temp sibling that failed its move must have been cleaned up, not abandoned.
        val strayTempFiles = tempDir.listFiles { file -> file.name.endsWith(".tmp") }.orEmpty()
        assertEquals(0, strayTempFiles.size, "A failed write must clean up its temp file")
    }

    @Test
    fun `a reader never observes a torn settings file while updates and saves are in flight`() =
        runBlocking(Dispatchers.Default) {
            val configA = createConfig(8001)
            val configB = createConfig(8002)
            RunConfigurationManager.addConfiguration(configA)
            RunConfigurationManager.addConfiguration(configB)
            assertTrue(tempFile.exists(), "The seeded settings file must exist before the reader starts")

            val rounds = 40
            val start = CompletableDeferred<Unit>()
            val readerReady = CompletableDeferred<Unit>()
            val writersFinished = CompletableDeferred<Unit>()

            // The updater mutates while the saver re-persists the current snapshot, so saves
            // genuinely interleave with updates. The mutex serializes each whole
            // read-modify-write-persist, and atomicWriteText replaces the target with one
            // complete serialization of some snapshot, so two writes can never interleave
            // bytes into the same file.
            val updater =
                async {
                    start.await()
                    readerReady.await()
                    repeat(rounds) { index ->
                        RunConfigurationManager.updateConfiguration(configA.copy(command = "a-${index + 1}"))
                        RunConfigurationManager.updateConfiguration(configB.copy(command = "b-${index + 1}"))
                    }
                }
            val saver =
                async {
                    start.await()
                    readerReady.await()
                    repeat(rounds) {
                        RunConfigurationManager.saveSettings()
                    }
                }
            val reader =
                async(Dispatchers.IO) {
                    start.await()
                    var reads = 0
                    var torn = 0
                    var lastTornSample: String? = null

                    fun sampleOnce() {
                        // Observe actual on-disk snapshots. Windows can refuse a replacement
                        // while this handle is open, even with NIO delete sharing.
                        val text = Files.readString(tempFile.toPath())
                        val decoded = runCatching { json.decodeFromString<RunConfigurationSettings>(text) }
                        if (decoded.isFailure) {
                            torn++
                            lastTornSample = if (text.isEmpty()) "<empty target>" else text.take(120)
                        }
                        reads++
                    }

                    sampleOnce()
                    readerReady.complete(Unit)
                    while (isActive && !writersFinished.isCompleted) sampleOnce()
                    assertEquals(
                        0,
                        torn,
                        "Every read must see one complete snapshot; last torn sample: $lastTornSample",
                    )
                    if (!isActive) return@async
                    assertTrue(reads > 0, "The reader must have sampled the file")
                }

            start.complete(Unit)
            awaitAll(updater, saver)
            writersFinished.complete(Unit)
            reader.await()

            // Windows can refuse replacement while the reader holds the destination.
            // Persistence is best-effort during contention; every observed snapshot must
            // still be complete. After the reader closes, a save must persist final state.
            RunConfigurationManager.saveSettings()
            assertSettingsFileMatchesMemory()
        }

    private fun assertSettingsFileMatchesMemory() {
        val diskSettings = json.decodeFromString<RunConfigurationSettings>(tempFile.readText())
        assertEquals(RunConfigurationManager.currentSettings.value, diskSettings)
    }
}
