package ai.rever.boss.plugin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
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
    fun `auth before enqueue still backfills the later JAR`() =
        runTest {
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
    fun `completion wake retries a JAR temporarily blocked by an update`() =
        runTest {
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
    fun `stale JAR is discarded before sidecar persistence`() =
        runTest {
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
    fun `a blocked plugin does not starve a ready plugin`() =
        runTest {
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
    fun `duplicate wakeups do not repeat an attempt for unchanged JAR bytes`() =
        runTest {
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

    @Test
    fun `enqueue before authentication waits for the token`() =
        runTest {
            val attempts = mutableListOf<String>()
            val coordinator = coordinator(this, attempts = attempts)
            val jar = temporaryJar("token-later")
            coordinator.enqueue("plugin.token-later", jar)
            advanceUntilIdle()
            assertEquals(emptyList(), attempts)
            coordinator.setAuthenticated(true)
            advanceUntilIdle()
            assertEquals(listOf(jar.absolutePath), attempts)
        }

    @Test
    fun `requeued replacement is eligible after the old bytes were attempted`() =
        runTest {
            val attempts = mutableListOf<String>()
            val coordinator = coordinator(this, attempts = attempts)
            val jar = temporaryJar("new-build")
            coordinator.setAuthenticated(true)
            coordinator.enqueue("plugin.new-build", jar)
            advanceUntilIdle()
            jar.writeText("a replacement with a different length")
            coordinator.enqueue("plugin.new-build", jar)
            advanceUntilIdle()
            assertEquals(listOf(jar.absolutePath, jar.absolutePath), attempts)
        }

    @Test
    fun `logout pauses pending work until authentication returns`() =
        runTest {
            val attempts = mutableListOf<String>()
            val updating = mutableSetOf("plugin.logout")
            val coordinator = coordinator(this, attempts = attempts, updating = updating)
            val jar = temporaryJar("logout")
            coordinator.setAuthenticated(true)
            coordinator.enqueue("plugin.logout", jar)
            advanceUntilIdle()
            coordinator.setAuthenticated(false)
            updating.clear()
            coordinator.onUpdateCheckCompleted("plugin.logout")
            advanceUntilIdle()
            assertEquals(emptyList(), attempts)
            coordinator.setAuthenticated(true)
            advanceUntilIdle()
            assertEquals(listOf(jar.absolutePath), attempts)
        }

    @Test
    fun `authentication lost and restored during persistence permits another attempt`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            var attempts = 0
            val coordinator =
                SidecarBackfillCoordinator(this, { false }, { false }) {
                    attempts++
                    if (attempts == 1) release.await()
                }
            coordinator.setAuthenticated(true)
            coordinator.enqueue("plugin.refresh", temporaryJar("refresh"))
            runCurrent()
            assertEquals(1, attempts)
            coordinator.setAuthenticated(false)
            coordinator.setAuthenticated(true)
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(2, attempts)
        }

    @Test
    fun `logout during persistence retains work for a later login`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            var attempts = 0
            val coordinator =
                SidecarBackfillCoordinator(this, { false }, { false }) {
                    attempts++
                    if (attempts == 1) release.await()
                }
            coordinator.setAuthenticated(true)
            coordinator.enqueue("plugin.logout-running", temporaryJar("logout-running"))
            runCurrent()
            coordinator.setAuthenticated(false)
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, attempts)
            coordinator.setAuthenticated(true)
            advanceUntilIdle()
            assertEquals(2, attempts)
        }

    @Test
    fun `a second wakeup cannot persist concurrently with a suspended attempt`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            var attempts = 0
            val coordinator =
                SidecarBackfillCoordinator(this, { false }, { false }) {
                    attempts++
                    release.await()
                }
            coordinator.setAuthenticated(true)
            coordinator.enqueue("plugin.first", temporaryJar("first"))
            runCurrent()
            coordinator.enqueue("plugin.second", temporaryJar("second"))
            runCurrent()
            assertEquals(1, attempts)
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(2, attempts)
        }

    @Test
    fun `a sidecar written while queued prevents persistence`() =
        runTest {
            var signed = false
            var attempts = 0
            val coordinator = SidecarBackfillCoordinator(this, { signed }, { false }) { attempts++ }
            val jar = temporaryJar("already-signed")
            coordinator.enqueue("plugin.signed", jar)
            signed = true
            coordinator.setAuthenticated(true)
            advanceUntilIdle()
            coordinator.enqueue("plugin.signed", jar)
            advanceUntilIdle()
            assertEquals(0, attempts)
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
