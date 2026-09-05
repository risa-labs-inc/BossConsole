package ai.rever.boss.plugin

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SidecarBackfillCoordinatorTest {
    private val temporaryFiles = mutableListOf<File>()

    @AfterTest
    fun removeTemporaryFiles() {
        temporaryFiles.forEach(File::delete)
    }

    @Test
    fun `auth before enqueue still backfills the later JAR`() = runTest {
        val attempts = mutableListOf<String>()
        val coordinator = coordinator(this, attempts = attempts)
        val jar = temporaryJar("auth-first")

        coordinator.setAuthenticated(true)
        advanceUntilIdle()
        coordinator.enqueue("plugin.auth-first", jar)
        advanceUntilIdle()

        assertEquals(listOf(jar.absolutePath), attempts)
    }

    @Test
    fun `completion wake retries a JAR temporarily blocked by an update`() = runTest {
        val attempts = mutableListOf<String>()
        val updating = mutableSetOf("plugin.updating")
        val coordinator = coordinator(this, attempts = attempts, updating = updating)
        val jar = temporaryJar("updating")

        coordinator.setAuthenticated(true)
        coordinator.enqueue("plugin.updating", jar)
        advanceUntilIdle()
        assertEquals(emptyList(), attempts)

        updating.remove("plugin.updating")
        coordinator.onUpdateCheckCompleted("plugin.updating")
        advanceUntilIdle()

        assertEquals(listOf(jar.absolutePath), attempts)
    }

    @Test
    fun `stale JAR is discarded before sidecar persistence`() = runTest {
        val attempts = mutableListOf<String>()
        val updating = mutableSetOf("plugin.replaced")
        val coordinator = coordinator(this, attempts = attempts, updating = updating)
        val jar = temporaryJar("replaced")

        coordinator.setAuthenticated(true)
        coordinator.enqueue("plugin.replaced", jar)
        advanceUntilIdle()

        jar.writeText("replacement bytes with a different length")
        updating.remove("plugin.replaced")
        coordinator.onUpdateCheckCompleted("plugin.replaced")
        advanceUntilIdle()

        assertEquals(emptyList(), attempts)
    }

    @Test
    fun `a blocked plugin does not starve a ready plugin`() = runTest {
        val attempts = mutableListOf<String>()
        val updating = mutableSetOf("plugin.blocked")
        val coordinator = coordinator(this, attempts = attempts, updating = updating)
        val blockedJar = temporaryJar("blocked")
        val readyJar = temporaryJar("ready")

        coordinator.setAuthenticated(true)
        coordinator.enqueue("plugin.blocked", blockedJar)
        coordinator.enqueue("plugin.ready", readyJar)
        advanceUntilIdle()

        assertEquals(listOf(readyJar.absolutePath), attempts)
    }

    @Test
    fun `duplicate wakeups do not repeat an attempt for unchanged JAR bytes`() = runTest {
        val attempts = mutableListOf<String>()
        val coordinator = coordinator(this, attempts = attempts)
        val jar = temporaryJar("deduplicated")

        coordinator.setAuthenticated(true)
        coordinator.enqueue("plugin.deduplicated", jar)
        advanceUntilIdle()
        coordinator.setAuthenticated(true)
        coordinator.onUpdateCheckCompleted("plugin.deduplicated")
        coordinator.enqueue("plugin.deduplicated", jar)
        advanceUntilIdle()

        assertEquals(listOf(jar.absolutePath), attempts)
    }

    private fun coordinator(
        scope: CoroutineScope,
        attempts: MutableList<String>,
        updating: Set<String> = emptySet(),
    ) = SidecarBackfillCoordinator(
        scope = scope,
        sidecarExists = { false },
        updateInFlight = updating::contains,
        persist = { jar -> attempts += jar.absolutePath },
    )

    private fun temporaryJar(name: String): File =
        File.createTempFile("sidecar-backfill-$name-", ".jar").also { file ->
            file.writeText("original bytes")
            temporaryFiles += file
        }
}
