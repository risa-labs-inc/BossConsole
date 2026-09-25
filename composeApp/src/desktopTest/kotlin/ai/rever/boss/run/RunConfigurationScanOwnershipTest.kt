package ai.rever.boss.run

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunConfigurationScanOwnershipTest {
    private lateinit var directory: File

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("run-scan-ownership-").toFile()
        RunConfigurationManager.resetForTesting(File(directory, "settings.json"))
    }

    @AfterTest
    fun tearDown() {
        RunConfigurationManager.resetForTesting()
        directory.deleteRecursively()
    }

    private class PendingScan(
        val path: String,
    ) {
        val entered = CompletableDeferred<Unit>()
        val result = CompletableDeferred<List<RunConfiguration>>()

        fun start(scope: CoroutineScope) =
            scope.async {
                RunConfigurationManager.scanProject(path) {
                    assertEquals(path, it)
                    entered.complete(Unit)
                    result.await()
                }
            }
    }

    private fun config(project: String) =
        RunConfiguration(
            id = project,
            name = "main ($project)",
            type = RunConfigurationType.MAIN_FUNCTION,
            filePath = "/$project/main.py",
            lineNumber = 1,
            language = Language.PYTHON,
            command = "python main.py",
            workingDirectory = "/$project",
        )

    @Test
    fun `older scan cannot replace the latest project results`() =
        runBlocking {
            withTimeout(10_000) {
                val old = PendingScan("old")
                val oldJob = old.start(this)
                old.entered.await()
                RunConfigurationManager.scanProject("new") { listOf(config("new")) }
                old.result.complete(listOf(config("old")))
                oldJob.await()
                assertEquals(listOf("new"), RunConfigurationManager.detectedConfigurations.value.map { it.id })
                assertFalse(RunConfigurationManager.isScanning.value)
                assertNull(RunConfigurationManager.lastError.value)
            }
        }

    @Test
    fun `older completion cannot end a newer scan's busy state`() =
        runBlocking {
            withTimeout(10_000) {
                val old = PendingScan("old")
                val oldJob = old.start(this)
                old.entered.await()
                val new = PendingScan("new")
                val newJob = new.start(this)
                new.entered.await()
                old.result.complete(listOf(config("old")))
                oldJob.await()
                assertTrue(RunConfigurationManager.isScanning.value)
                assertTrue(RunConfigurationManager.detectedConfigurations.value.isEmpty())
                new.result.complete(listOf(config("new")))
                newJob.await()
                assertEquals(listOf("new"), RunConfigurationManager.detectedConfigurations.value.map { it.id })
            }
        }

    @Test
    fun `older failure cannot overwrite current results or error`() =
        runBlocking {
            withTimeout(10_000) {
                val old = PendingScan("old")
                val oldJob = old.start(this)
                old.entered.await()
                RunConfigurationManager.scanProject("new") { error("new failure") }
                old.result.completeExceptionally(IllegalStateException("old failure"))
                oldJob.await()
                assertEquals("Failed to scan project: new failure", RunConfigurationManager.lastError.value)
                assertFalse(RunConfigurationManager.isScanning.value)
            }
        }

    @Test
    fun `clear invalidates pending results and allows a subsequent scan`() =
        runBlocking {
            withTimeout(10_000) {
                val old = PendingScan("old")
                val oldJob = old.start(this)
                old.entered.await()
                RunConfigurationManager.clearDetected()
                assertFalse(RunConfigurationManager.isScanning.value)
                old.result.complete(listOf(config("old")))
                oldJob.await()
                assertTrue(RunConfigurationManager.detectedConfigurations.value.isEmpty())
                RunConfigurationManager.scanProject("new") { listOf(config("new")) }
                assertEquals(listOf("new"), RunConfigurationManager.detectedConfigurations.value.map { it.id })
            }
        }

    @Test
    fun `clear invalidates pending errors`() =
        runBlocking {
            withTimeout(10_000) {
                val old = PendingScan("old")
                val oldJob = old.start(this)
                old.entered.await()
                RunConfigurationManager.clearDetected()
                old.result.completeExceptionally(IllegalStateException("old failure"))
                oldJob.await()
                assertNull(RunConfigurationManager.lastError.value)
                assertFalse(RunConfigurationManager.isScanning.value)
            }
        }

    @Test
    fun `cancellation is propagated without a scan error`() =
        runBlocking {
            var cancellationObserved = false
            try {
                RunConfigurationManager.scanProject("cancelled") { throw CancellationException("cancelled") }
            } catch (_: CancellationException) {
                cancellationObserved = true
            }
            assertTrue(cancellationObserved)
            assertNull(RunConfigurationManager.lastError.value)
            assertFalse(RunConfigurationManager.isScanning.value)
        }

    @Test
    fun `cancelling old caller cannot end the new caller's scan`() =
        runBlocking {
            withTimeout(10_000) {
                val old = PendingScan("old")
                val oldJob = old.start(this)
                old.entered.await()
                val new = PendingScan("new")
                val newJob = new.start(this)
                new.entered.await()
                oldJob.cancelAndJoin()
                assertTrue(RunConfigurationManager.isScanning.value)
                assertNull(RunConfigurationManager.lastError.value)
                new.result.complete(emptyList())
                newJob.await()
                assertFalse(RunConfigurationManager.isScanning.value)
            }
        }
}
