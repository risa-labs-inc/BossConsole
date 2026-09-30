package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.repository.PluginInfo
import ai.rever.boss.plugin.repository.PluginRepository
import ai.rever.boss.plugin.repository.PluginRepositoryManager
import ai.rever.boss.plugin.repository.PluginSearchFilter
import ai.rever.boss.plugin.repository.PluginSearchResult
import ai.rever.boss.plugin.updater.PluginUpdateManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginUpdateBridgeValidationTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `a downloaded update declaring another plugin is rejected before unload`() =
        runTest {
            val pluginId = "com.example.target"
            val candidate = PluginInfo(pluginId = pluginId, displayName = "Target", version = "2.0.0")
            val repository =
                DownloadingRepository(candidate) { targetPath ->
                    PluginJarTestFixtures.writeJar(
                        dir = File(targetPath).parentFile,
                        fileName = File(targetPath).name,
                        pluginId = "com.example.other",
                        version = "2.0.0",
                    )
                }
            val manager =
                PluginUpdateManager(
                    repositoryManager = PluginRepositoryManager().apply { addRepository(repository) },
                    hostBossVersion = "9.9.9",
                )
            manager.checkForUpdates(mapOf(pluginId to "1.0.0"))

            var unloadCalled = false
            var loadCalled = false
            var installingCalled = false

            val result =
                manager.updatePlugin(
                    pluginId = pluginId,
                    downloadPath = File(dir, "candidate.jar").absolutePath,
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
                    validateDownloadedPlugin = PluginUpdateBridge::validateDownloadedUpdateCandidate,
                )

            assertTrue(result.isFailure, "the mismatched candidate must be rejected")
            assertFalse(unloadCalled, "the installed plugin must not be unloaded")
            assertFalse(loadCalled, "the mismatched candidate must not be loaded")
            assertFalse(installingCalled, "the swap phase must not begin before validation succeeds")
        }

    @Test
    fun `a downloaded update declaring a host-managed plugin is rejected before unload`() =
        runTest {
            val pluginId = "ai.rever.boss.plugin.api"
            val candidate = PluginInfo(pluginId = pluginId, displayName = "BOSS Plugin API", version = "2.0.0")
            val repository =
                DownloadingRepository(candidate) { targetPath ->
                    PluginJarTestFixtures.writeJar(
                        dir = File(targetPath).parentFile,
                        fileName = File(targetPath).name,
                        pluginId = pluginId,
                        version = "2.0.0",
                    )
                }
            val manager =
                PluginUpdateManager(
                    repositoryManager = PluginRepositoryManager().apply { addRepository(repository) },
                    hostBossVersion = "9.9.9",
                )
            manager.checkForUpdates(mapOf(pluginId to "1.0.0"))

            var unloadCalled = false
            var loadCalled = false
            var installingCalled = false

            val result =
                manager.updatePlugin(
                    pluginId = pluginId,
                    downloadPath = File(dir, "host-managed-candidate.jar").absolutePath,
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
                    validateDownloadedPlugin = PluginUpdateBridge::validateDownloadedUpdateCandidate,
                )

            assertTrue(result.isFailure, "the host-managed candidate must be rejected")
            assertFalse(unloadCalled, "the installed plugin must not be unloaded")
            assertFalse(loadCalled, "the host-managed candidate must not be loaded")
            assertFalse(installingCalled, "the swap phase must not begin before validation succeeds")
        }

    private class DownloadingRepository(
        private val latest: PluginInfo,
        private val writeDownload: (String) -> Unit,
    ) : PluginRepository {
        override val id = "fake-store"
        override val name = "Fake Store"
        override val isLocal = false
        override val isAvailable = true

        override suspend fun listPlugins(): Result<List<PluginInfo>> = Result.success(listOf(latest))

        override suspend fun searchPlugins(filter: PluginSearchFilter): Result<PluginSearchResult> =
            Result.success(PluginSearchResult(listOf(latest), totalCount = 1))

        override suspend fun getPlugin(pluginId: String): Result<PluginInfo?> =
            Result.success(if (pluginId == latest.pluginId) latest else null)

        override suspend fun getPluginVersions(pluginId: String): Result<List<PluginInfo>> =
            Result.success(if (pluginId == latest.pluginId) listOf(latest) else emptyList())

        override suspend fun downloadPlugin(
            pluginId: String,
            version: String?,
            targetPath: String,
            onProgress: ((Float) -> Unit)?,
        ): Result<String> {
            writeDownload(targetPath)
            return Result.success(targetPath)
        }

        override fun getDownloadProgress(pluginId: String): Flow<Float>? = null

        override suspend fun refresh(): Result<Unit> = Result.success(Unit)
    }
}
