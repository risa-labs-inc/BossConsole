package ai.rever.boss.plugin

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises the production readiness wait without starting manifest network traffic. */
class SystemPluginManifestSyncOrderingTest {
    @Test
    fun `startup fetch and subscription are both wired through readiness`() {
        val relative = "composeApp/src/desktopMain/kotlin/ai/rever/boss/plugin/SystemPluginManifestService.kt"
        val sourceFile =
            generateSequence(File(".").absoluteFile) { it.parentFile }
                .map { File(it, relative) }
                .first { it.isFile }
        val source = sourceFile.readText()
        val startup = source.substringAfter("fun startSync(").substringBefore("private suspend fun refreshFromRemote")
        val subscription = source.substringAfter("private fun subscribeToChanges()")
        assertTrue(startup.indexOf("awaitSupabaseInitialized()") >= 0)
        assertTrue(startup.indexOf("awaitSupabaseInitialized()") < startup.indexOf("refreshFromRemote()"))
        assertTrue(subscription.indexOf("awaitSupabaseInitialized()") >= 0)
        assertTrue(subscription.indexOf("awaitSupabaseInitialized()") < subscription.indexOf("SupabaseConfig.client"))
    }

    @Test
    fun `a coroutine awaiting isInitialized does not complete before initialize is called`() =
        runTest {
            val initialized = MutableStateFlow(false)
            assertFalse(initialized.value, "precondition: not yet initialized")

            val waiter = async { awaitSupabaseInitialized(initialized) }
            // Let the waiter actually start and register as a collector on the still-false flow,
            // rather than asserting on a coroutine that has merely been scheduled but never run -
            // that would pass unconditionally, proving nothing about suspension.
            runCurrent()

            assertFalse(waiter.isCompleted, "the waiter must still be suspended before initialize() runs")

            advanceTimeBy(60_000)
            runCurrent()
            assertFalse(waiter.isCompleted, "a slow initialization must not permanently abandon live sync")

            initialized.value = true
            runCurrent()

            assertTrue(waiter.isCompleted, "the waiter must resolve once initialize() runs")
            waiter.await()
        }

    @Test
    fun `readiness wait completes immediately for an initialized client`() =
        runTest {
            val initialized = MutableStateFlow(true)
            val before = testScheduler.currentTime
            awaitSupabaseInitialized(initialized)
            assertEquals(before, testScheduler.currentTime)
        }

    @Test
    fun `readiness wait is cancellable before initialization`() =
        runTest {
            val initialized = MutableStateFlow(false)
            val waiter = async { awaitSupabaseInitialized(initialized) }
            runCurrent()
            waiter.cancel()
            runCurrent()
            assertTrue(waiter.isCancelled)
            assertTrue(waiter.isCompleted, "cancellation must finish the wait, not merely request cancellation")
        }
}
