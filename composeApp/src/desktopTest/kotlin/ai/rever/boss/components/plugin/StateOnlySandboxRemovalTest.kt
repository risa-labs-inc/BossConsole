package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.sandbox.PluginSandboxManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StateOnlySandboxRemovalTest {
    @Test
    fun `state-only uninstall waits for sandbox removal before dropping state`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val sandboxes =
                object : PluginSandboxManager by unusedProxy() {
                    override suspend fun removeSandbox(pluginId: String) {
                        entered.complete(Unit)
                        release.await()
                    }
                }
            val manager =
                DynamicPluginManager(
                    panelRegistry = PanelRegistry(),
                    tabRegistry = TabRegistry(),
                    sandboxManager = sandboxes,
                    createSandboxedContext = { _, _ -> error("No plugin should be registered") },
                )
            try {
                seedStateOnlyPlugin(manager)
                val removal = async { manager.uninstallPlugin("test.state-only", force = true) }
                runCurrent()
                assertTrue(entered.isCompleted, "sandbox teardown was never started")
                assertFalse(removal.isCompleted, "uninstall detached the sandbox teardown")
                assertTrue(manager.isInstalled("test.state-only"))
                release.complete(Unit)
                assertTrue(removal.await().isSuccess)
                assertFalse(manager.isInstalled("test.state-only"))
            } finally {
                release.complete(Unit)
                manager.disposeWindow()
                Dispatchers.resetMain()
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun seedStateOnlyPlugin(manager: DynamicPluginManager) {
        val field = DynamicPluginManager::class.java.getDeclaredField("_pluginStates")
        field.isAccessible = true
        val state = field.get(manager) as MutableStateFlow<Map<String, DynamicPluginInfo>>
        val manifest =
            PluginManifest(
                pluginId = "test.state-only",
                displayName = "State only",
                version = "1.0.0",
                apiVersion = "1.0.0",
                mainClass = "unused.Main",
            )
        val info = DynamicPluginInfo(manifest, "unused.jar", PluginState.DISABLED, 0, false)
        state.value = mapOf(manifest.pluginId to info)
    }

    private inline fun <reified T> unusedProxy(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            error("Unexpected call: ${method.name}")
        } as T
}
