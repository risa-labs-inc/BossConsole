package ai.rever.boss.plugin

import ai.rever.boss.components.plugin.DynamicPluginInfo
import ai.rever.boss.components.plugin.MissingDependencyInstaller
import ai.rever.boss.components.plugin.PluginAccessSnapshot
import ai.rever.boss.components.plugin.PluginAccessTransitions
import ai.rever.boss.components.plugin.PluginDependencyBus
import ai.rever.boss.components.plugin.enqueuePluginActivation
import ai.rever.boss.components.plugin.reportPluginActivation
import ai.rever.boss.components.plugin.shouldReportPluginReenable
import ai.rever.boss.plugin.api.PluginDependency
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.api.PluginState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Behavioral policy/reporting tests; the final two checks cover only the concrete manager wiring. */
class DependencyPromptOnReenableTest {
    private val manifest =
        PluginManifest(
            pluginId = "example.dependent",
            displayName = "Dependent",
            version = "1.0.0",
            apiVersion = "1.0.0",
            mainClass = "example.Main",
            dependencies = listOf(PluginDependency(pluginId = "example.gateway", version = "1.0.0", optional = false)),
        )

    private object NoopInstaller : MissingDependencyInstaller {
        override fun isInstalled(pluginId: String) = false

        override suspend fun displayNameFor(pluginId: String): String? = null

        override suspend fun install(pluginId: String) = Result.success(Unit)
    }

    @Test
    fun `only successful accessible user re-enables report`() {
        // Exhaust all 16 outcomes; only success + new enable + user intent + access may report.
        for (mask in 0..15) {
            val actual =
                shouldReportPluginReenable(
                    succeeded = mask and 1 != 0,
                    wasAlreadyEnabled = mask and 2 != 0,
                    reportMissingDependencies = mask and 4 != 0,
                    canAccess = mask and 8 != 0,
                )
            assertEquals(mask == 13, actual, "activation flags: $mask")
        }
    }

    @Test
    fun `dependency file checks run off the caller thread and report the same manifest`() =
        runBlocking {
            val caller = Thread.currentThread()
            val bus = PluginDependencyBus()
            var checkedFile = false
            val reporter =
                MissingDependencyReporter(
                    states = {
                        mapOf(
                            "example.gateway" to
                                DynamicPluginInfo(
                                    manifest = manifest.copy(pluginId = "example.gateway", dependencies = emptyList()),
                                    jarPath = "/missing/gateway.jar",
                                    state = PluginState.DISABLED,
                                    loadedAt = 0L,
                                    enabled = false,
                                ),
                        )
                    },
                    installer = NoopInstaller,
                    bus = bus,
                    jarExists = {
                        assertNotSame(caller, Thread.currentThread())
                        checkedFile = true
                        false
                    },
                )
            reportPluginActivation(manifest) {
                assertSame(manifest, it)
                reporter.report(it)
            }.getOrThrow()
            assertTrue(checkedFile)
            assertEquals(
                "example.gateway",
                bus.missingDependencies
                    .first()
                    .missing.missingPluginId,
            )
        }

    @Test
    fun `initial access is silent but a later grant reaches the real prompt bus`() =
        runTest {
            val transitions = PluginAccessTransitions()
            val bus = PluginDependencyBus()
            val reporter = MissingDependencyReporter(states = { emptyMap() }, installer = NoopInstaller, bus = bus)
            val initial = PluginAccessSnapshot("user", false, emptySet())
            val granted = initial.copy(permissions = setOf("tools.use"))
            val startup = transitions.accept(initial)
            if (startup.reportMissingDependencies) reportPluginActivation(manifest, reporter::report).getOrThrow()
            assertTrue(startup.reconcile)
            assertNull(withTimeoutOrNull(1) { bus.missingDependencies.first() })

            val grant = transitions.accept(granted)
            assertTrue(grant.reportMissingDependencies)
            if (grant.reportMissingDependencies) reportPluginActivation(manifest, reporter::report).getOrThrow()
            assertEquals(
                "example.gateway",
                bus.missingDependencies
                    .first()
                    .missing.missingPluginId,
            )
        }

    @Test
    fun `reporting failures are advisory and do not prevent the next report`() =
        runBlocking {
            val failure = IllegalStateException("reporter failed")
            val reported = reportPluginActivation(manifest) { throw failure }.exceptionOrNull()
            // Coroutine stack-trace recovery may copy the exception across dispatchers.
            assertIs<IllegalStateException>(reported)
            assertEquals(failure.message, reported.message)
            var delivered = false
            reportPluginActivation(manifest) { delivered = true }.getOrThrow()
            assertTrue(delivered)
        }

    @Test
    fun `reporter cancellation is propagated`() =
        runBlocking {
            val cancellation = CancellationException("window closed")
            try {
                reportPluginActivation(manifest) { throw cancellation }
                error("cancellation must not become an advisory failure")
            } catch (actual: CancellationException) {
                assertEquals(cancellation.message, actual.message)
            }
        }

    @Test
    fun `cancelling the owner before dispatch prevents reporting`() =
        runTest {
            val owner = Job()
            var delivered = false
            val report =
                launch(owner + StandardTestDispatcher(testScheduler)) {
                    reportPluginActivation(manifest) { delivered = true }
                }
            owner.cancelAndJoin()
            report.join()
            assertFalse(delivered)
        }

    @Test
    fun `caller cancellation cannot interrupt persistence or cancel a manager-owned report`() =
        runTest {
            val managerJob = Job()
            val managerScope = CoroutineScope(managerJob + StandardTestDispatcher(testScheduler))
            var persisted = false
            var delivered = false
            lateinit var reporting: Job
            val caller =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    reporting = managerScope.enqueuePluginActivation(manifest, { delivered = true }, { throw it })
                    // The delegate can persist immediately after enable returns, before any IO runs.
                    persisted = true
                    awaitCancellation()
                }
            assertTrue(persisted)
            assertFalse(delivered)
            caller.cancelAndJoin()
            reporting.join()
            assertTrue(delivered)
            managerJob.cancelAndJoin()
        }

    @Test
    fun `disposing the manager cancels its queued activation report`() =
        runTest {
            val managerJob = Job()
            val managerScope = CoroutineScope(managerJob + StandardTestDispatcher(testScheduler))
            var delivered = false
            val reporting = managerScope.enqueuePluginActivation(manifest, { delivered = true }, { throw it })
            managerJob.cancelAndJoin()
            reporting.join()
            assertFalse(delivered)
        }

    private fun source(path: String): String {
        val root =
            assertNotNull(
                generateSequence(File("").absoluteFile) { it.parentFile }
                    .firstOrNull { File(it, "composeApp/build.gradle.kts").isFile },
            )
        return File(root, path).readText()
    }

    @Test
    fun `manager uses the tested policy and recovery opts out`() {
        val manager = source("composeApp/src/commonMain/kotlin/ai/rever/boss/components/plugin/DynamicPluginManager.kt")
        // Declaration boundaries, not a brace parser: includes expression bodies and catch blocks.
        assertTrue(manager.contains("suspend fun enablePlugin("), "enable declaration missing")
        assertTrue(manager.contains("suspend fun reregisterAfterRestart("), "enable end boundary missing")
        val enable =
            manager
                .substringAfter("suspend fun enablePlugin(")
                .substringBefore("suspend fun reregisterAfterRestart(")
        assertTrue(Regex("""shouldReportPluginReenable\s*\(""").containsMatchIn(enable))
        assertTrue(Regex("""notifyPluginActivated\s*\(\s*activatedManifest\s*\)""").containsMatchIn(enable))
        assertTrue(
            Regex("""manager\.enablePlugin\s*\(\s*pluginId\s*,\s*reportMissingDependencies\s*=\s*false""")
                .containsMatchIn(manager),
        )
        assertTrue(
            Regex("""handleAccessChange\s*\(\s*reportMissingDependencies\s*=\s*change\.reportMissingDependencies""")
                .containsMatchIn(manager),
        )
        assertTrue(Regex("""enqueuePluginActivation\s*\(\s*manifest\s*,\s*callback\s*\)""").containsMatchIn(manager))
    }

    @Test
    fun `setup connects the reporter without a second delegate notification`() {
        val setup =
            source(
                "composeApp/src/desktopMain/kotlin/ai/rever/boss/components/plugin/PluginLoaderDelegateSetup.kt",
            )
        assertTrue(setup.contains("MissingDependencyReporter.forManager(dynamicPluginManager)"))
        assertTrue(
            Regex(
                """onPluginActivated\s*=\s*(?:missingDependencyReporter::report|""" +
                    """\{\s*manifest\s*->\s*missingDependencyReporter\.report\(manifest\)\s*\})""",
            ).containsMatchIn(setup),
        )
        val delegate = source("composeApp/src/desktopMain/kotlin/ai/rever/boss/plugin/PluginLoaderDelegateImpl.kt")
        assertTrue(delegate.contains("override suspend fun enablePlugin("), "delegate enable declaration missing")
        assertTrue(delegate.contains("override suspend fun disablePlugin("), "delegate enable end boundary missing")
        val enable =
            delegate
                .substringAfter("override suspend fun enablePlugin(")
                .substringBefore("override suspend fun disablePlugin(")
        assertFalse(Regex("""\.report\s*\(""").containsMatchIn(enable))
    }
}
