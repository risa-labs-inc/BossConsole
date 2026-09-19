package ai.rever.boss.plugin.updater

import ai.rever.boss.plugin.repository.PluginInfo
import ai.rever.boss.plugin.repository.PluginRepository
import ai.rever.boss.plugin.repository.PluginRepositoryManager
import ai.rever.boss.plugin.repository.PluginSearchFilter
import ai.rever.boss.plugin.repository.PluginSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A cancelled download must arrive at the caller AS a cancellation.
 *
 * This is the shape of a bug that shipped past a review: `downloadUpdate` caught
 * `Exception`, which includes `CancellationException`, and returned
 * `Result.failure`. `updatePlugin` then RETURNED rather than throwing - so the
 * caller's `catch (CancellationException)`, the thing that deletes the truncated jar
 * from the plugin directory, could never run, and the user's own Cancel was reported
 * to the Toolbox as "update failed: StandaloneCoroutine was cancelled".
 *
 * Both halves are asserted here, because either one alone re-breaks it.
 */
class PluginUpdateCancellationTest {
    private val pluginId = "ai.rever.boss.plugin.dynamic.probe"

    private fun manager(repository: PluginRepository): PluginUpdateManager {
        val repos = PluginRepositoryManager().apply { addRepository(repository) }
        return PluginUpdateManager(repositoryManager = repos, hostBossVersion = "9.9.9")
    }

    private fun candidate() =
        PluginInfo(
            pluginId = pluginId,
            displayName = "Probe",
            version = "2.0.0",
        )

    @Test
    fun `a cancelled download propagates rather than becoming a failed result`() =
        runTest {
            val mgr = manager(CancellingRepository(candidate()))
            mgr.checkForUpdates(mapOf(pluginId to "1.0.0"))

            var cancelled = false
            try {
                mgr.downloadUpdate(pluginId, "/tmp/does-not-matter.jar")
            } catch (_: CancellationException) {
                cancelled = true
            }

            assertTrue(cancelled, "swallowed into Result.failure, the caller's cleanup never runs")
        }

    @Test
    fun `a cancelled update is not reported to listeners as a failure`() =
        runTest {
            val mgr = manager(CancellingRepository(candidate()))
            mgr.checkForUpdates(mapOf(pluginId to "1.0.0"))
            val failures = mutableListOf<String>()
            mgr.addListener(
                object : UpdateListener {
                    override fun onUpdateFailed(
                        pluginId: String,
                        error: String,
                    ) {
                        failures += error
                    }
                },
            )

            runCatching {
                mgr.updatePlugin(
                    pluginId = pluginId,
                    downloadPath = "/tmp/does-not-matter.jar",
                    unloadPlugin = { Result.success(Unit) },
                    loadPlugin = { Result.success(Unit) },
                )
            }

            // The user pressed Cancel. "update failed: StandaloneCoroutine was
            // cancelled" is the wrong thing to put in front of them.
            assertTrue(failures.isEmpty(), "reported as a failure: $failures")
        }

    @Test
    fun `a cancel after the swap has started still completes the unload and load`() =
        runTest {
            val mgr = manager(SucceedingRepository(candidate()))
            mgr.checkForUpdates(mapOf(pluginId to "1.0.0"))
            var loaded = false

            // The headline hazard: withdrawing the Cancel button is UI state, so the
            // work has to stop being cancellable too. Here the unload cancels the very
            // job the swap runs on - if the swap were cancellable, the plugin would be
            // left unloaded with nothing in its place.
            runCatching {
                coroutineScope {
                    mgr.updatePlugin(
                        pluginId = pluginId,
                        downloadPath = "/tmp/does-not-matter.jar",
                        unloadPlugin = {
                            this@coroutineScope.cancel()
                            Result.success(Unit)
                        },
                        loadPlugin = {
                            // A REAL suspension point, or the test proves nothing:
                            // cancellation is cooperative, and a lambda that never
                            // suspends never checks. Under NonCancellable this does not
                            // throw, which is the whole point.
                            yield()
                            loaded = true
                            Result.success(Unit)
                        },
                    )
                }
            }

            assertTrue(loaded, "the load must still run, or the plugin is gone with nothing in its place")
        }

    @Test
    fun `a rejected downloaded candidate does not unload or load the existing plugin`() =
        runTest {
            val mgr = manager(SucceedingRepository(candidate()))
            mgr.checkForUpdates(mapOf(pluginId to "1.0.0"))
            val installingEvents = mutableListOf<String>()
            val completedEvents = mutableListOf<String>()
            val failures = mutableListOf<String>()
            mgr.addListener(
                object : UpdateListener {
                    override fun onUpdateInstalling(pluginId: String) {
                        installingEvents += pluginId
                    }

                    override fun onUpdateCompleted(
                        pluginId: String,
                        newVersion: String,
                    ) {
                        completedEvents += "$pluginId@$newVersion"
                    }

                    override fun onUpdateFailed(
                        pluginId: String,
                        error: String,
                    ) {
                        failures += "$pluginId:$error"
                    }
                },
            )

            var validationCalled = false
            var unloadCalled = false
            var loadCalled = false
            var installingCalled = false

            val result =
                mgr.updatePlugin(
                    pluginId = pluginId,
                    downloadPath = "/tmp/does-not-matter.jar",
                    unloadPlugin = {
                        unloadCalled = true
                        Result.success(Unit)
                    },
                    loadPlugin = {
                        loadCalled = true
                        Result.success(Unit)
                    },
                    onInstalling = {
                        installingCalled = true
                    },
                    validateDownloadedPlugin = { candidatePluginId, downloadedPath ->
                        validationCalled = true
                        assertEquals(pluginId, candidatePluginId)
                        assertEquals("/tmp/does-not-matter.jar", downloadedPath)
                        Result.failure(IllegalStateException("candidate rejected"))
                    },
                )

            assertTrue(result.isFailure, "a rejected candidate must fail the update")
            assertTrue(validationCalled, "the downloaded candidate must be vetted before swap")
            assertFalse(unloadCalled, "the running plugin must remain loaded when validation fails")
            assertFalse(loadCalled, "a rejected candidate must not be loaded")
            assertFalse(installingCalled, "the caller must not be told that the non-cancellable swap began")
            assertTrue(installingEvents.isEmpty(), "listener install events must not fire before validation passes")
            assertTrue(completedEvents.isEmpty(), "a rejected candidate must not be reported as installed")
            assertTrue(failures.isNotEmpty(), "validation failure must be reported through the update lifecycle")
        }

    @Test
    fun `a real download failure is still reported`() =
        runTest {
            val mgr = manager(FailingRepository(candidate()))
            mgr.checkForUpdates(mapOf(pluginId to "1.0.0"))
            val failures = mutableListOf<String>()
            mgr.addListener(
                object : UpdateListener {
                    override fun onUpdateFailed(
                        pluginId: String,
                        error: String,
                    ) {
                        failures += error
                    }
                },
            )

            val result = mgr.downloadUpdate(pluginId, "/tmp/does-not-matter.jar")

            assertTrue(result.isFailure)
            assertFalse(failures.isEmpty(), "a 404 is a failure and must stay one")
        }
}

/** Cancels mid-download, the way a user pressing Cancel does. */
private class CancellingRepository(
    private val latest: PluginInfo,
) : StubRepository(latest) {
    override suspend fun downloadPlugin(
        pluginId: String,
        version: String?,
        targetPath: String,
        onProgress: ((Float) -> Unit)?,
    ): Result<String> = throw CancellationException("cancelled by the user")
}

/** Downloads without incident, for the paths that are about the swap. */
private class SucceedingRepository(
    private val latest: PluginInfo,
) : StubRepository(latest) {
    override suspend fun downloadPlugin(
        pluginId: String,
        version: String?,
        targetPath: String,
        onProgress: ((Float) -> Unit)?,
    ): Result<String> = Result.success(targetPath)
}

/** Fails the way an unreachable asset does. */
private class FailingRepository(
    private val latest: PluginInfo,
) : StubRepository(latest) {
    override suspend fun downloadPlugin(
        pluginId: String,
        version: String?,
        targetPath: String,
        onProgress: ((Float) -> Unit)?,
    ): Result<String> = Result.failure(IllegalStateException("HTTP 404"))
}

/** Everything a [PluginUpdateManager] asks of a repository except the download. */
private abstract class StubRepository(
    private val latest: PluginInfo,
) : PluginRepository {
    override val id = "fake-remote"
    override val name = "Fake Remote"
    override val isLocal = false
    override val isAvailable = true

    override suspend fun listPlugins(): Result<List<PluginInfo>> = Result.success(listOf(latest))

    override suspend fun searchPlugins(filter: PluginSearchFilter): Result<PluginSearchResult> =
        Result.success(PluginSearchResult(listOf(latest), totalCount = 1))

    override suspend fun getPlugin(pluginId: String): Result<PluginInfo?> =
        Result.success(if (pluginId == latest.pluginId) latest else null)

    override suspend fun getPluginVersions(pluginId: String): Result<List<PluginInfo>> =
        Result.success(if (pluginId == latest.pluginId) listOf(latest) else emptyList())

    override fun getDownloadProgress(pluginId: String): Flow<Float>? = null

    override suspend fun refresh(): Result<Unit> = Result.success(Unit)
}
