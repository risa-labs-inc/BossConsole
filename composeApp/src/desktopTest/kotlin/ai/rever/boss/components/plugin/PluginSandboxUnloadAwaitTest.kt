package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.plugin.api.PluginType
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.sandbox.PluginSandboxManager
import ai.rever.boss.plugin.sandbox.PluginSandboxManagerImpl
import ai.rever.boss.plugin.sandbox.ui.PluginCrashRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginSandboxUnloadAwaitTest {
    @Test
    fun `uninstall awaits sandbox teardown before returning`() = verifyRemoval(cancelCaller = false)

    @Test
    fun `caller cancellation cannot abandon destructive cleanup`() = verifyRemoval(cancelCaller = true)

    @Test
    fun `cancellation waiting for cleanup lock refreshes surviving panels`() =
        runBlocking {
            val sandboxManager = PluginSandboxManagerImpl()
            val manager =
                DynamicPluginManager(
                    PanelRegistry(),
                    TabRegistry(),
                    sandboxManager,
                    createSandboxedContext = { _, _ -> error("No plugin is loaded in this fixture") },
                )
            val lock =
                manager.javaClass
                    .getDeclaredField("mutex")
                    .apply { isAccessible = true }
                    .get(manager) as Mutex
            val previousRefresh = DynamicPluginManager.pluginPanelsRefresh
            var refreshed = false
            DynamicPluginManager.pluginPanelsRefresh = { id, _ -> if (id == "waiting-plugin") refreshed = true }
            lock.lock()
            val uninstall = async(start = CoroutineStart.UNDISPATCHED) { manager.uninstallPlugin("waiting-plugin") }
            try {
                assertFalse(uninstall.isCompleted)
                uninstall.cancel()
                withTimeout(5_000) { uninstall.join() }
                assertTrue(refreshed, "Cancellation must compensate panel detachment before lock acquisition")
            } finally {
                lock.unlock()
                DynamicPluginManager.pluginPanelsRefresh = previousRefresh
                manager.disposeWindow()
                sandboxManager.dispose()
            }
        }

    @Test
    fun `cancellation during UI disposal cannot proceed into destructive cleanup`() =
        runBlocking {
            val sandboxManager = PluginSandboxManagerImpl()
            val manager =
                DynamicPluginManager(
                    PanelRegistry(),
                    TabRegistry(),
                    sandboxManager,
                    createSandboxedContext = { _, _ -> error("No plugin is loaded in this fixture") },
                )
            val previousTeardown = DynamicPluginManager.pluginTabsTeardown
            val started = CompletableDeferred<Unit>()
            val disposal = CompletableDeferred<Unit>()
            var proceeded = false
            DynamicPluginManager.pluginTabsTeardown = {
                started.complete(Unit)
                disposal.await()
            }
            val teardown =
                async(start = CoroutineStart.UNDISPATCHED) {
                    manager.teardownPluginTabsForUnload("disposal-plugin")
                    proceeded = true
                }
            try {
                withTimeout(5_000) { started.await() }
                teardown.cancel()
                withTimeout(5_000) { teardown.join() }
                assertFalse(proceeded, "Cancelled disposal must propagate before acquiring even an uncontended lock")
            } finally {
                DynamicPluginManager.pluginTabsTeardown = previousTeardown
                manager.disposeWindow()
                sandboxManager.dispose()
            }
        }

    private fun verifyRemoval(cancelCaller: Boolean) =
        runBlocking {
            val real = PluginSandboxManagerImpl()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val sandboxManager =
                object : PluginSandboxManager by real {
                    override suspend fun removeSandbox(pluginId: String) {
                        started.complete(Unit)
                        release.await()
                        real.removeSandbox(pluginId)
                    }
                }
            val manager =
                DynamicPluginManager(
                    PanelRegistry(),
                    TabRegistry(),
                    sandboxManager,
                    createSandboxedContext = { _, _ -> error("No plugin is loaded in this fixture") },
                )
            val id = "com.example.await-removal"
            val info = unloadedInfo(id)
            // Reproduce the existing unload-with-state-only path without loading a real plugin JAR.
            manager.javaClass
                .getDeclaredMethod("updatePluginState", String::class.java, DynamicPluginInfo::class.java)
                .apply {
                    isAccessible = true
                    invoke(manager, id, info)
                }
            PluginCrashRegistry.markIncompatible(id)
            val uninstall = async(start = CoroutineStart.UNDISPATCHED) { manager.uninstallPlugin(id, force = true) }
            try {
                withTimeout(5_000) { started.await() }
                assertFalse(uninstall.isCompleted, "uninstall must await its sandbox removal")
                if (cancelCaller) uninstall.cancel()
                release.complete(Unit)
                if (cancelCaller) {
                    withTimeout(5_000) { uninstall.join() }
                    assertTrue(uninstall.isCancelled)
                } else {
                    assertTrue(withTimeout(5_000) { uninstall.await() }.isSuccess)
                }
                assertFalse(manager.isInstalled(id))
                assertFalse(PluginCrashRegistry.isIncompatible(id))
            } finally {
                release.complete(Unit)
                uninstall.join()
                manager.disposeWindow()
                real.dispose()
                PluginCrashRegistry.clearIncompatible(id)
            }
        }

    private fun unloadedInfo(id: String): DynamicPluginInfo =
        DynamicPluginInfo(
            manifest =
                PluginManifest(
                    pluginId = id,
                    displayName = "Await removal",
                    version = "1.0.0",
                    apiVersion = "1.0",
                    mainClass = "example.Plugin",
                    type = PluginType.PANEL,
                ),
            jarPath = "/unused.jar",
            state = PluginState.ERROR,
            loadedAt = 0L,
            enabled = false,
        )
}
