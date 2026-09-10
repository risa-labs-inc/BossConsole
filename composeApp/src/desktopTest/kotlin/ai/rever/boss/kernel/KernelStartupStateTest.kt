package ai.rever.boss.kernel

import ai.rever.boss.process.ManagedProcess
import ai.rever.boss.process.ProcessConfig
import ai.rever.boss.process.ProcessMode
import ai.rever.boss.process.ProcessRegistry
import ai.rever.boss.process.ProcessSpawner
import ai.rever.boss.process.ProcessType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KernelStartupStateTest {
    @Test
    fun `KernelStartupState holds correct service information`() {
        val initializing = KernelStartupState.Initializing
        assertEquals(KernelStartupState.Initializing, initializing)

        val spawning = KernelStartupState.SpawningServices(spawnedCount = 3, totalExpected = 9)
        assertEquals(3, spawning.spawnedCount)
        assertEquals(9, spawning.totalExpected)

        val ready =
            KernelStartupState.Ready(
                runningServices = listOf("boss-orchestrator", "boss-service-auth"),
                missingJars = listOf("boss-app-browser"),
                failedServices = emptyList(),
            )
        assertEquals(2, ready.runningServices.size)
        assertEquals(1, ready.missingJars.size)
        assertTrue(ready.failedServices.isEmpty())
    }

    @Test
    fun `respawnCandidate stands down and notifies when restart count exceeds maxRestarts`() {
        val registry = ProcessRegistry()
        val config =
            ProcessConfig(
                processId = "test-service",
                processType = ProcessType.SERVICE,
                displayName = "Test Service",
                mainClass = "unused.Main",
                maxRestarts = 2,
            )
        val managedProcess =
            ManagedProcess(
                config = config,
                process = ProcessBuilder(ProcessSpawner.findJavaExecutable(), "-version").start(),
                ipcAddress = "tcp://localhost:57001",
            )
        registry.register("test-service", managedProcess)

        // Increment restart count to max
        registry.incrementRestartCount("test-service")
        registry.incrementRestartCount("test-service")
        assertEquals(2, registry.getRestartCount("test-service"))

        // Should return null (and notify operator)
        val candidate = respawnCandidate(registry, "test-service")
        assertNull(candidate, "Candidate should be null when restart count reaches maxRestarts")

        managedProcess.destroyForcibly()
    }
}
