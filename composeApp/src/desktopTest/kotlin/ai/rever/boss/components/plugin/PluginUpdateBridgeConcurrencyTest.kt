package ai.rever.boss.components.plugin

import ai.rever.boss.downloads.DownloadCenter
import ai.rever.boss.plugin.PluginStoreSetup
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TransferKind
import ai.rever.boss.plugin.repository.PluginRepositoryManager
import ai.rever.boss.plugin.sandbox.PluginSandboxManagerImpl
import ai.rever.boss.plugin.updater.PluginUpdateManager
import ai.rever.boss.plugin.updater.UpdateInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PluginUpdateBridgeConcurrencyTest {
    private lateinit var updateManager: PluginUpdateManager
    private lateinit var dynamicManager: DynamicPluginManager

    @BeforeEach
    fun setup() {
        val repoManager = PluginRepositoryManager()
        updateManager = PluginUpdateManager(repoManager)

        PluginStoreSetup::class.java.getDeclaredField("_updateManager").apply {
            isAccessible = true
            set(PluginStoreSetup, updateManager)
        }

        val availableUpdatesField = PluginUpdateManager::class.java.getDeclaredField("_availableUpdates")
        availableUpdatesField.isAccessible = true
        val stateFlow = availableUpdatesField.get(updateManager) as MutableStateFlow<List<UpdateInfo>>
        stateFlow.value =
            listOf(
                UpdateInfo(
                    pluginId = "plugin-a",
                    displayName = "Plugin A",
                    currentVersion = "1.0.0",
                    newVersion = "2.0.0",
                    downloadUrl = "http://example.com/a.jar",
                ),
                UpdateInfo(
                    pluginId = "plugin-b",
                    displayName = "Plugin B",
                    currentVersion = "1.0.0",
                    newVersion = "2.0.0",
                    downloadUrl = "http://example.com/b.jar",
                ),
            )

        dynamicManager =
            DynamicPluginManager(
                PanelRegistry(),
                TabRegistry(),
                PluginSandboxManagerImpl(),
                createSandboxedContext = { _, _ -> error("No plugin loaded") },
            )

        DownloadCenter.transfers.value.forEach { DownloadCenter.end(it.info.id) }
    }

    @AfterEach
    fun teardown() {
        DownloadCenter.transfers.value.forEach { DownloadCenter.end(it.info.id) }
    }

    @Test
    fun `concurrent updates for same plugin fail fast for the second operation`() =
        runBlocking {
            val pluginId = "plugin-a"

            val ownsA = DownloadCenter.begin(pluginId, "Plugin A", TransferKind.PLUGIN_INSTALL)
            assertTrue(ownsA)

            val resultB = PluginUpdateBridge.performUpdate(pluginId, dynamicManager)

            assertTrue(resultB.isFailure)
            val exception = resultB.exceptionOrNull()
            assertNotNull(exception)
            assertTrue(exception.message!!.contains("Update already in progress"))
        }

    @Test
    fun `concurrent updates for different plugins proceed independently`() =
        runBlocking {
            val ownsA = DownloadCenter.begin("plugin-a", "Plugin A", TransferKind.PLUGIN_INSTALL)
            assertTrue(ownsA)

            val resultB = PluginUpdateBridge.performUpdate("plugin-b", dynamicManager)

            assertTrue(resultB.isFailure)
            val exception = resultB.exceptionOrNull()
            assertNotNull(exception)
            assertFalse(exception.message!!.contains("Update already in progress"))
        }

    @Test
    fun `failure isolation B failure cannot call cleanup on A artifact`() =
        runBlocking {
            // A owns the transfer
            val ownsA = DownloadCenter.begin("plugin-a", "Plugin A", TransferKind.PLUGIN_INSTALL)
            assertTrue(ownsA)

            // B fails because A owns it
            val resultB = PluginUpdateBridge.performUpdate("plugin-a", dynamicManager)
            assertTrue(resultB.isFailure)

            // A should still be active in DownloadCenter, meaning B didn't clean it up in a finally block
            val transferA = DownloadCenter.transfers.value.find { it.info.id == "plugin-a" }
            assertNotNull(transferA, "A's transfer should still exist")
        }

    @Test
    fun `retry after failure`() =
        runBlocking {
            val pluginId = "plugin-b"

            // First try - this will fail because there is no actual repository initialized (so it throws internally)
            val result1 = PluginUpdateBridge.performUpdate(pluginId, dynamicManager)
            assertTrue(result1.isFailure)
            assertFalse(result1.exceptionOrNull()!!.message!!.contains("Update already in progress"))

            // Second try - should fail with the exact same reason, NOT "Update already in progress"
            // This proves the first failure cleaned up its DownloadCenter state.
            val result2 = PluginUpdateBridge.performUpdate(pluginId, dynamicManager)
            assertTrue(result2.isFailure)
            assertFalse(result2.exceptionOrNull()!!.message!!.contains("Update already in progress"))
        }

    @Test
    fun `retry after success`() =
        runBlocking {
            // We can't easily mock a FULL success without mocking the whole PluginUpdateManager and File paths.
            // But we can simulate a successful completion of the DownloadCenter transfer:
            DownloadCenter.begin("plugin-c", "Plugin C", TransferKind.PLUGIN_INSTALL)
            DownloadCenter.end("plugin-c")

            // Now try to update plugin-c. It shouldn't be blocked by a stale DownloadCenter state.
            // We'll add plugin-c to available updates first.
            val availableUpdatesField = PluginUpdateManager::class.java.getDeclaredField("_availableUpdates")
            availableUpdatesField.isAccessible = true
            val stateFlow = availableUpdatesField.get(updateManager) as MutableStateFlow<List<UpdateInfo>>
            stateFlow.value = stateFlow.value +
                UpdateInfo(
                    pluginId = "plugin-c",
                    displayName = "Plugin C",
                    currentVersion = "1.0.0",
                    newVersion = "2.0.0",
                    downloadUrl = "http://example.com/c.jar",
                )

            val result = PluginUpdateBridge.performUpdate("plugin-c", dynamicManager)
            assertTrue(result.isFailure) // Fails due to uninitialized store/repo, NOT due to lock
            assertFalse(result.exceptionOrNull()!!.message!!.contains("Update already in progress"))
        }
}
