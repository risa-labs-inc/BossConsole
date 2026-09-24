package ai.rever.boss.plugin.updater

import ai.rever.boss.plugin.repository.PluginInfo
import ai.rever.boss.plugin.repository.PluginRepositoryManager
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The downloaded jar's identity is vetted BETWEEN the download and the swap (BossConsole#927).
 *
 * The host's Update button used to hand the downloaded jar straight to the swap: unload the
 * running plugin, then load whatever the jar's manifest declares. The boundary placement is
 * the fix - the vet runs before the Installing state and before any unload, so a rejected
 * jar leaves the running plugin installed, the swap never begins, and the failure is
 * reported like any other update failure.
 */
class PluginUpdateManagerIdentityVetTest {
    private val pluginId = "ai.rever.boss.plugin.dynamic.probe"

    private fun candidate() =
        PluginInfo(
            pluginId = pluginId,
            displayName = "Probe",
            version = "2.0.0",
        )

    private fun manager(vet: (String, String) -> Result<Unit>): PluginUpdateManager {
        val repos = PluginRepositoryManager().apply { addRepository(FakeSingleVersionRepository(candidate())) }
        return PluginUpdateManager(
            repositoryManager = repos,
            verifyDownloadedJar = vet,
        )
    }

    @Test
    fun `a jar the vet rejects is refused before the unload`() =
        runTest {
            var unloaded = 0
            var loaded = 0
            var installing = 0
            val mgr = manager(vet = { _, _ -> Result.failure(IllegalStateException("declares a different id")) })
            mgr.checkForUpdates(mapOf(pluginId to "1.0.0"))

            val result =
                mgr.updatePlugin(
                    pluginId = pluginId,
                    downloadPath = "/tmp/does-not-matter.jar",
                    unloadPlugin = {
                        unloaded++
                        Result.success(Unit)
                    },
                    loadPlugin = {
                        loaded++
                        Result.success(Unit)
                    },
                    onInstalling = { installing++ },
                )

            assertTrue(result.isFailure, "a mismatched jar must be rejected")
            assertEquals(0, unloaded, "the running plugin must not be unloaded for a rejected jar")
            assertEquals(0, loaded, "a rejected jar must not be installed")
            assertEquals(0, installing, "the swap must not begin for a rejected jar")
        }

    @Test
    fun `a rejected jar is reported as a failure and leaves no partial state`() =
        runTest {
            val mgr = manager(vet = { _, _ -> Result.failure(IllegalStateException("declares a different id")) })
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

            val result =
                mgr.updatePlugin(
                    pluginId = pluginId,
                    downloadPath = "/tmp/does-not-matter.jar",
                    unloadPlugin = { Result.success(Unit) },
                    loadPlugin = { Result.success(Unit) },
                )

            assertEquals(listOf("declares a different id"), failures)
            assertTrue(mgr.state.value is UpdateState.Failed)
            // The update row survives a rejection: the plugin is still on its old version,
            // so "an update exists" remains true and the badge stays honest.
            assertEquals(1, mgr.availableUpdates.value.size)
            assertTrue(result.isFailure)
        }

    @Test
    fun `the vet sees the plugin being updated and the downloaded jar`() =
        runTest {
            var seen: Pair<String, String>? = null
            val mgr =
                manager(
                    vet = { id, path ->
                        seen = id to path
                        Result.success(Unit)
                    },
                )
            mgr.checkForUpdates(mapOf(pluginId to "1.0.0"))

            mgr.updatePlugin(
                pluginId = pluginId,
                downloadPath = "/tmp/does-not-matter.jar",
                unloadPlugin = { Result.success(Unit) },
                loadPlugin = { Result.success(Unit) },
            )

            assertEquals(pluginId to "/tmp/does-not-matter.jar", seen)
        }

    @Test
    fun `a jar the vet accepts proceeds to unload and load`() =
        runTest {
            var unloaded = 0
            var loaded = 0
            var installing = 0
            var loadedPath: String? = null
            val mgr = manager(vet = { _, _ -> Result.success(Unit) })
            mgr.checkForUpdates(mapOf(pluginId to "1.0.0"))

            val result =
                mgr.updatePlugin(
                    pluginId = pluginId,
                    downloadPath = "/tmp/does-not-matter.jar",
                    unloadPlugin = {
                        assertEquals(pluginId, it)
                        unloaded++
                        Result.success(Unit)
                    },
                    loadPlugin = {
                        loadedPath = it
                        loaded++
                        Result.success(Unit)
                    },
                    onInstalling = { installing++ },
                )

            assertTrue(result.isSuccess, "a matching-id update must not be refused")
            assertEquals(1, unloaded)
            assertEquals(1, loaded)
            assertEquals("/tmp/does-not-matter.jar", loadedPath)
            assertEquals(1, installing)
            assertTrue(mgr.state.value is UpdateState.Completed)
            assertEquals(0, mgr.availableUpdates.value.size)
        }
}
