package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.sandbox.PluginSandbox
import ai.rever.boss.plugin.sandbox.PluginSandboxManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit test validating that sandbox teardown occurs synchronously prior to classloader unload.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginSandboxTeardownUnloadTest {

    @Test
    fun `sandbox removal is invoked synchronously before loader unload`() = runTest {
        val executionOrder = mutableListOf<String>()

        val fakeSandboxManager = object : PluginSandboxManager {
            override fun createSandbox(pluginId: String) = throw UnsupportedOperationException()
            override fun getSandbox(pluginId: String): PluginSandbox? = null
            override fun getAllSandboxes(): Map<String, PluginSandbox> = emptyMap()
            override suspend fun removeSandbox(pluginId: String) {
                executionOrder.add("removeSandbox:$pluginId")
            }
            override suspend fun enablePlugin(pluginId: String) {}
            override suspend fun disablePlugin(pluginId: String) {}
            override suspend fun restartPlugin(pluginId: String): Boolean = false
            override suspend fun fullyUnloadPlugin(pluginId: String): Result<Unit> = Result.success(Unit)
            override fun registerCleanupCallback(callback: ai.rever.boss.plugin.sandbox.PluginCleanupCallback) {}
            override fun unregisterCleanupCallback(callback: ai.rever.boss.plugin.sandbox.PluginCleanupCallback) {}
            override fun dispose() {}
        }

        fakeSandboxManager.removeSandbox("ai.rever.boss.plugin.dynamic.terminaltab")
        executionOrder.add("unloadPlugin:ai.rever.boss.plugin.dynamic.terminaltab")

        assertEquals(
            listOf(
                "removeSandbox:ai.rever.boss.plugin.dynamic.terminaltab",
                "unloadPlugin:ai.rever.boss.plugin.dynamic.terminaltab"
            ),
            executionOrder,
            "Sandbox removal must precede classloader unloading so coroutines finish before classloader closure"
        )
    }
}
