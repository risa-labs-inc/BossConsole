package ai.rever.boss.plugin

import ai.rever.boss.components.plugin.DependencyInstallPlan
import ai.rever.boss.components.plugin.MissingDependencyInstaller
import ai.rever.boss.plugin.repository.PluginInfo
import ai.rever.boss.plugin.repository.PluginRepository
import ai.rever.boss.plugin.repository.PluginSearchFilter
import ai.rever.boss.plugin.repository.PluginSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * How the dialog learns what Install will do, and how Install then does it.
 *
 * Two halves. `planFor` on the store-backed installer turns the store's dependency column into
 * a plan, degrading to "just this plugin" whenever the store cannot answer. `installAll` on the
 * interface runs a plan front to back and stops where it fails. Neither touches the existing
 * `install(pluginId)`, which three other callers rely on for single installs; the plan is what
 * the dialog asks for, not a change to what a bare install means.
 */
class MissingDependencyInstallerPlanTest {
    private val temp = File(System.getProperty("java.io.tmpdir"), "boss-dep-plan-${hashCode()}")

    @AfterTest
    fun cleanup() {
        temp.deleteRecursively()
    }

    /**
     * A store that knows a dependency graph and nothing else.
     *
     * [failing] ids answer with a failed Result, the way `RemotePluginRepository` reports a
     * decode or transport problem; ids absent from [graph] answer `success(null)`, the way the
     * real store reports a plugin it has never heard of. The plan has to survive both.
     */
    private class GraphStore(
        private val graph: Map<String, List<String>>,
        private val failing: Set<String> = emptySet(),
    ) : PluginRepository {
        override val id = "store"
        override val name = "Store"
        override val isLocal = false
        override val isAvailable = true

        val asked = mutableListOf<String>()

        override suspend fun listPlugins(): Result<List<PluginInfo>> = Result.success(emptyList())

        override suspend fun searchPlugins(filter: PluginSearchFilter): Result<PluginSearchResult> =
            Result.failure(UnsupportedOperationException("unused"))

        override suspend fun getPlugin(pluginId: String): Result<PluginInfo?> {
            asked += pluginId
            val deps = graph[pluginId]
            return when {
                pluginId in failing -> {
                    Result.failure(IllegalStateException("decode failed"))
                }

                deps == null -> {
                    Result.success(null)
                }

                else -> {
                    Result.success(
                        PluginInfo(pluginId = pluginId, displayName = pluginId, version = "1.0.0", dependencies = deps),
                    )
                }
            }
        }

        override suspend fun getPluginVersions(pluginId: String) = listPlugins()

        override suspend fun downloadPlugin(
            pluginId: String,
            version: String?,
            targetPath: String,
            onProgress: ((Float) -> Unit)?,
        ): Result<String> = Result.failure(UnsupportedOperationException("unused"))

        override fun getDownloadProgress(pluginId: String): Flow<Float>? = null

        override suspend fun refresh(): Result<Unit> = Result.success(Unit)
    }

    private fun installer(
        store: PluginRepository?,
        installed: Set<String> = emptySet(),
    ): StoreMissingDependencyInstaller {
        temp.mkdirs()
        return StoreMissingDependencyInstaller(
            repository = { store },
            pluginDir = { temp },
            hooks =
                InstallerHooks(
                    installedNow = { id -> id in installed },
                    load = { Result.success(Unit) },
                    readManifest = { null },
                    persist = { _, _, _, _ -> },
                ),
        )
    }

    @Test
    fun `the plan follows the store's dependency column, dependencies first`() =
        runTest {
            val installer = installer(GraphStore(mapOf("b" to listOf("c"), "c" to listOf("d"), "d" to emptyList())))

            val plan = installer.planFor("b")

            assertEquals(listOf("d", "c", "b"), plan.order)
            assertTrue(plan.unresolved.isEmpty())
        }

    @Test
    fun `a dependency already present is left out of the plan`() =
        runTest {
            // isPresent is the installer's own installedNow, so the plan and the Install guard
            // cannot disagree about what installed means.
            val installer =
                installer(
                    GraphStore(mapOf("b" to listOf("c"), "c" to listOf("d"), "d" to emptyList())),
                    installed = setOf("c"),
                )

            val plan = installer.planFor("b")

            assertEquals(listOf("b"), plan.order)
        }

    @Test
    fun `a plugin the store has never heard of is still planned, but not expanded`() =
        runTest {
            val plan = installer(GraphStore(mapOf("b" to listOf("c")))).planFor("b")

            assertEquals(listOf("c", "b"), plan.order)
            assertEquals(setOf("c"), plan.unresolved)
        }

    @Test
    fun `a store failure on one plugin does not fail the plan`() =
        runTest {
            // Metadata failure leaves the dependency in the plan for a fresh lookup at install
            // time. It is not evidence of optionality: an install failure still stops the plan.
            val installer = installer(GraphStore(mapOf("b" to listOf("c")), failing = setOf("c")))

            val plan = installer.planFor("b")

            assertEquals(listOf("c", "b"), plan.order)
            assertEquals(setOf("c"), plan.unresolved)
        }

    @Test
    fun `no store yields a plan of the plugin alone`() =
        runTest {
            val plan = installer(store = null).planFor("b")

            assertEquals(listOf("b"), plan.order)
        }

    @Test
    fun `the store is asked about each plugin once`() =
        runTest {
            val store =
                GraphStore(
                    mapOf("b" to listOf("c", "e"), "c" to listOf("d"), "e" to listOf("d"), "d" to emptyList()),
                )

            installer(store).planFor("b")

            assertEquals(store.asked.size, store.asked.toSet().size, "asked twice: ${store.asked}")
        }

    /** An installer that records the order it was asked to install in and fails on demand. */
    private class RecordingInstaller(
        private val failOn: String? = null,
        val failure: Throwable = IllegalStateException("$failOn did not install"),
    ) : MissingDependencyInstaller {
        val installed = mutableListOf<String>()

        override fun isInstalled(pluginId: String) = false

        override suspend fun displayNameFor(pluginId: String): String? = null

        override suspend fun install(pluginId: String): Result<Unit> {
            if (pluginId == failOn) return Result.failure(failure)
            installed += pluginId
            return Result.success(Unit)
        }
    }

    @Test
    fun `installAll installs in the order given`() =
        runTest {
            val installer = RecordingInstaller()

            val result = installer.installAll(listOf("d", "c", "b"))

            assertTrue(result.isSuccess)
            assertEquals(listOf("d", "c", "b"), installer.installed)
        }

    @Test
    fun `installAll stops at the first failure and leaves what came before`() =
        runTest {
            // No rollback, deliberately. A dependency installed on its own is harmless and may be
            // wanted by something else; deleting it to keep the failure tidy would be the one
            // outcome worse than the failure.
            val installer = RecordingInstaller(failOn = "c")

            val result = installer.installAll(listOf("d", "c", "b"))

            assertTrue(result.isFailure)
            assertEquals(listOf("d"), installer.installed)
            // The dialog's title is about the root, so the message says which plugin did not
            // arrive and that a dependency is why; the dependency's own message is kept intact.
            assertEquals("Could not install b: c did not install", result.exceptionOrNull()?.message)
            assertEquals("c did not install", result.exceptionOrNull()?.cause?.message)
        }

    @Test
    fun `the root's own failure is reported as it is`() =
        runTest {
            // Nothing to add: the failing plugin is the one the dialog is already about, and
            // "Could not install b: b did not install" would say it twice.
            val installer = RecordingInstaller(failOn = "b")

            val result = installer.installAll(listOf("d", "c", "b"))

            assertEquals(listOf("d", "c"), installer.installed)
            assertEquals("b did not install", result.exceptionOrNull()?.message)
            assertSame(installer.failure, result.exceptionOrNull())
        }

    @Test
    fun `a dependency failure without a message still identifies the root`() =
        runTest {
            val failure = IllegalStateException(null, null)
            val installer = RecordingInstaller(failOn = "c", failure = failure)

            val result = installer.installAll(listOf("d", "c", "b"))

            assertEquals("Could not install b.", result.exceptionOrNull()?.message)
            assertSame(failure, result.exceptionOrNull()?.cause)
            assertEquals(listOf("d"), installer.installed)
        }

    @Test
    fun `the default plan is the plugin alone`() =
        runTest {
            // Every installer other than the store-backed one keeps today's behaviour without
            // writing a line: PluginLoadGateRecovery and PluginStoreVersionBridge install one
            // named plugin and were never shown a closure to consent to.
            val plan = RecordingInstaller().planFor("b")

            assertEquals(DependencyInstallPlan(listOf("b"), emptySet(), cyclic = false, truncated = false), plan)
        }

    @Test
    fun `planning propagates thrown and returned cancellation instead of expanding siblings`() =
        runTest {
            for (throwFailure in listOf(true, false)) {
                val delegate = GraphStore(mapOf("b" to listOf("c", "d")))
                val asked = mutableListOf<String>()
                val store =
                    object : PluginRepository by delegate {
                        override suspend fun getPlugin(pluginId: String): Result<PluginInfo?> {
                            asked += pluginId
                            if (pluginId == "c") {
                                val error = CancellationException("window closed")
                                if (throwFailure) throw error
                                return Result.failure(error)
                            }
                            return delegate.getPlugin(pluginId)
                        }
                    }
                assertFailsWith<CancellationException> { installer(store).planFor("b") }
                assertEquals(listOf("b", "c"), asked)
            }
        }

    @Test
    fun `accepted plan reaches its root after the observing window closes`() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val rootReached = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val installer =
                StoreMissingDependencyInstaller(
                    repository = { null },
                    pluginDir = { temp },
                    hooks =
                        InstallerHooks(
                            installedNow = { id ->
                                if (id == "detached-child") {
                                    entered.complete(Unit)
                                    check(release.await(5, TimeUnit.SECONDS))
                                }
                                if (id == "detached-root") rootReached.complete(Unit)
                                true
                            },
                            load = { Result.success(Unit) },
                        ),
                )
            val caller = launch { installer.installAll(listOf("detached-child", "detached-root")) }
            try {
                withTimeout(5_000) { entered.await() }
                caller.cancelAndJoin()
                release.countDown()
                withTimeout(5_000) { rootReached.await() }
            } finally {
                release.countDown()
                caller.cancelAndJoin()
            }
        }

    @Test
    fun `presence checks run off the caller thread and repeated edges are checked once`() =
        runBlocking {
            val callerThread = Thread.currentThread()
            val checked = mutableListOf<String>()
            val store = GraphStore(mapOf("b" to listOf("c", "c"), "c" to emptyList()))
            val installer =
                StoreMissingDependencyInstaller(
                    repository = { store },
                    pluginDir = { temp },
                    hooks =
                        InstallerHooks(
                            installedNow = { id ->
                                assertTrue(Thread.currentThread() !== callerThread)
                                checked += id
                                false
                            },
                            load = { Result.success(Unit) },
                        ),
                )
            assertEquals(listOf("c", "b"), installer.planFor("b").order)
            assertEquals(listOf("c"), checked)
        }
}
