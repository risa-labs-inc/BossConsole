package ai.rever.boss.plugin.updater

import ai.rever.boss.plugin.repository.PluginInfo
import ai.rever.boss.plugin.repository.PluginRepositoryManager
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression for the "rollback" comment that never ran.
 *
 * `PluginUpdateManager.swapPlugin` logs `Update failed, attempting rollback` and
 * then reports the failure without re-installing the previous version. The
 * function literally documents that fact (`// Note: Actual rollback would
 * require keeping track of the old JAR path / For now, we just report the
 * failure`), so the rollback that `onInstalling` is asking the caller to
 * trust never happens. The caller withdraws its Cancel button for exactly
 * this reason, then a binary-incompat new version or a corrupted jar in
 * transit unloads the user's plugin and asks them to manually re-install.
 *
 * The fix takes a `rollback` callback the caller supplies (typically
 * `PluginUpdateBridge`, which already keeps an old-jar snapshot in
 * `PluginRollbackStore` before `unloadPlugin` runs) and invokes it on the
 * load-failure branch of `swapPlugin`.
 *
 * The callback receives the plugin id, NOT a jar path: the rollback store
 * keys by id because the host's update path downloads to a NEW filename
 * and reconcile then deletes the old jar, so by the time the rollback runs
 * the old path no longer addresses anything. The id is the one identifier
 * that survives a version change, and the bridge's rollback callback closes
 * over the running jar path it captured before unload so the rollback store
 * can delete the failed bytes after restoring the snapshot.
 */
class PluginUpdateManagerRollbackTest {
    private val pluginId = "com.example.demo"

    private fun candidate(version: String) =
        PluginInfo(
            pluginId = pluginId,
            displayName = "Demo",
            version = version,
        )

    private fun manager(latest: PluginInfo): PluginUpdateManager {
        val repos = PluginRepositoryManager().apply { addRepository(FakeSingleVersionRepository(latest)) }
        return PluginUpdateManager(repositoryManager = repos)
    }

    private suspend fun PluginUpdateManager.withAvailable(
        pluginId: String,
        currentVersion: String,
    ): PluginUpdateManager {
        // checkForUpdates seeds _availableUpdates from (pluginId -> installedVersion) vs the
        // store's advertised latest. The test calls this once per scenario before invoking
        // updatePlugin so the failure path can find its target. Returning `this` keeps the
        // chain `manager(...).withAvailable(...)` typed as `PluginUpdateManager` - earlier
        // versions declared this without a return type and bound `val mgr` to Unit.
        val result = checkForUpdates(mapOf(pluginId to currentVersion))
        assertTrue(
            result.availableUpdates.any { it.pluginId == pluginId },
            "expected an available update for $pluginId",
        )
        return this
    }

    @Test
    fun `a failed load invokes the rollback callback with the plugin id`() =
        runTest {
            val mgr = manager(candidate("1.1.0")).withAvailable(pluginId, "1.0.0")

            val rollbackCalls = AtomicInteger(0)
            val rollbackArg = AtomicReference<String?>(null)

            // Simulate a successful unload followed by a failed load: exactly the case the
            // manager's swap branch calls "rollback". A no-op rollback would still satisfy
            // the count but not the id argument, which is the second half of the contract.
            val unload: suspend (String) -> Result<Unit> = { Result.success(Unit) }
            val load: suspend (String) -> Result<Unit> = {
                Result.failure(IllegalStateException("binary-incompat"))
            }
            val rollback: suspend (String) -> Result<Unit> = { id: String ->
                rollbackCalls.incrementAndGet()
                rollbackArg.set(id)
                Result.success(Unit)
            }

            val result =
                mgr.updatePlugin(
                    pluginId = pluginId,
                    downloadPath = "/tmp/dl.jar",
                    unloadPlugin = unload,
                    loadPlugin = load,
                    rollback = rollback,
                )

            assertTrue(result.isFailure, "the failed load must surface as a failure")
            assertEquals(1, rollbackCalls.get(), "rollback must be called exactly once on load failure")
            // Contract pin: the argument MUST be the plugin id the update was for - the
            // rollback store keys by id because by the time rollback runs the original jar
            // path no longer addresses anything. Passing a path here would compile (the type
            // is `String`) and silently break the rollback at the call site.
            assertEquals(pluginId, rollbackArg.get(), "rollback must receive the plugin id, not a jar path")
        }

    @Test
    fun `a successful load does not invoke the rollback callback`() =
        runTest {
            val mgr = manager(candidate("1.1.0")).withAvailable(pluginId, "1.0.0")

            val rollbackCalls = AtomicInteger(0)
            val unload: suspend (String) -> Result<Unit> = { Result.success(Unit) }
            val load: suspend (String) -> Result<Unit> = { Result.success(Unit) }
            val rollback: suspend (String) -> Result<Unit> = {
                rollbackCalls.incrementAndGet()
                Result.success(Unit)
            }

            val result =
                mgr.updatePlugin(
                    pluginId = pluginId,
                    downloadPath = "/tmp/dl.jar",
                    unloadPlugin = unload,
                    loadPlugin = load,
                    rollback = rollback,
                )

            assertTrue(result.isSuccess, "a successful load must surface as success")
            assertEquals(0, rollbackCalls.get(), "rollback must NOT be called on success")
        }
}
