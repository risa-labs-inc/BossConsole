package ai.rever.boss.components.registery

import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.PanelRegistry
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins down the [PanelComponentStore] mechanics behind plugin hot reload of
 * open sidebar panels (issue #856): the store caches the instantiated
 * component, so swapping the registry factory alone is invisible to an open
 * slot — [PanelComponentStore.resetComponent] is what re-creates it from the
 * current registration, and it must survive the old component's cleanup hook
 * throwing an Error (a closed classloader throws NoClassDefFoundError, not an
 * Exception).
 *
 * Threading: production calls resetComponent/resetPanels on the UI thread
 * only (see their KDoc). These tests deliberately invoke them off-EDT — safe
 * here because each test owns its store/registry and kotlin.test runs the
 * class single-threaded, so no composition or concurrent caller races the
 * snapshot-state mutation.
 */
class PanelComponentStoreResetTest {

    private val testIcon = ImageVector.Builder(
        defaultWidth = 1.dp, defaultHeight = 1.dp,
        viewportWidth = 1f, viewportHeight = 1f
    ).build()

    private fun panelInfo(id: PanelId) = object : PanelInfo {
        override val id = id
        override val displayName = "Test Panel"
        override val icon = testIcon
        override val defaultSlotPosition = left
    }

    private class FakePanelComponent(
        override val panelInfo: PanelInfo,
        ctx: ComponentContext,
        val generation: Int,
        private val onBeforeResetAction: () -> Unit = {},
    ) : PanelComponentWithUI, ComponentContext by ctx {
        @Composable
        override fun Content() {
        }

        override fun onBeforeReset() = onBeforeResetAction()
    }

    private fun newContext(): ComponentContext = DefaultComponentContext(LifecycleRegistry())

    private fun registerFactory(
        registry: PanelRegistry,
        id: PanelId,
        generation: Int,
        onBeforeReset: () -> Unit = {},
    ) {
        registry.registerPanel(panelInfo(id)) { ctx, info ->
            FakePanelComponent(info, ctx, generation, onBeforeReset)
        }
    }

    @Test
    fun `open panel keeps its cached component when the factory is re-registered`() {
        val registry = PanelRegistry()
        val id = PanelId("test-panel", 1)
        registerFactory(registry, id, generation = 1)
        val store = PanelComponentStore(newContext(), registry)

        val opened = store.getOrCreateComponent(id) as FakePanelComponent

        // Simulate a hot reload: unregister the old factory, register the new build's.
        registry.unregisterPanel(id)
        registerFactory(registry, id, generation = 2)

        // The cache wins — this is why an open panel kept rendering the old build.
        assertSame(opened, store.getOrCreateComponent(id))
    }

    @Test
    fun `resetComponent recreates the panel from the newly registered factory`() {
        val registry = PanelRegistry()
        val id = PanelId("test-panel", 1)
        registerFactory(registry, id, generation = 1)
        val store = PanelComponentStore(newContext(), registry)

        val opened = store.getOrCreateComponent(id) as FakePanelComponent
        assertEquals(1, opened.generation)

        registry.unregisterPanel(id)
        registerFactory(registry, id, generation = 2)

        assertTrue(store.resetComponent(id))
        val refreshed = store.getOrCreateComponent(id) as FakePanelComponent
        assertNotSame(opened, refreshed)
        assertEquals(2, refreshed.generation)
    }

    @Test
    fun `resetComponent survives the old component's cleanup hook throwing an Error`() {
        val registry = PanelRegistry()
        val id = PanelId("test-panel", 1)
        registerFactory(registry, id, generation = 1, onBeforeReset = {
            throw NoClassDefFoundError("plugin classloader already closed")
        })
        val store = PanelComponentStore(newContext(), registry)
        store.getOrCreateComponent(id)

        registry.unregisterPanel(id)
        registerFactory(registry, id, generation = 2)

        assertTrue(store.resetComponent(id))
        assertEquals(2, (store.getOrCreateComponent(id) as FakePanelComponent).generation)
    }

    @Test
    fun `resetComponent drops the stale component when the panel is no longer registered`() {
        val registry = PanelRegistry()
        val id = PanelId("test-panel", 1)
        registerFactory(registry, id, generation = 1)
        val store = PanelComponentStore(newContext(), registry)
        store.getOrCreateComponent(id)

        registry.unregisterPanel(id)

        // No new factory: the reset fails, but the stale component must not
        // stay cached (it would pin the unloaded plugin's classloader).
        assertFalse(store.resetComponent(id))
        assertFalse(store.activeComponents.containsKey(id))
    }

    @Test
    fun `resetComponent returns false for a panel that is not open`() {
        val registry = PanelRegistry()
        val id = PanelId("test-panel", 1)
        registerFactory(registry, id, generation = 1)
        val store = PanelComponentStore(newContext(), registry)

        assertFalse(store.resetComponent(id))
    }

    @Test
    fun `resetPanels resets only matching open slots across windows and leaves others untouched`() {
        val reloadId = PanelId("reload-me", 1)
        val otherId = PanelId("other-panel", 2)

        val registryA = PanelRegistry()
        registerFactory(registryA, reloadId, generation = 1)
        registerFactory(registryA, otherId, generation = 1)
        val storeA = PanelComponentStore(newContext(), registryA)
        val aReload = storeA.getOrCreateComponent(reloadId)
        val aOther = storeA.getOrCreateComponent(otherId)

        val registryB = PanelRegistry()
        registerFactory(registryB, reloadId, generation = 1)
        val storeB = PanelComponentStore(newContext(), registryB)
        val bReload = storeB.getOrCreateComponent(reloadId)

        // Window ids unique to this test: the registry is a global singleton,
        // so shared ids would couple tests if the suite ever runs in parallel.
        PanelComponentStoreRegistry.register("reset-panels-window-a", storeA)
        PanelComponentStoreRegistry.register("reset-panels-window-b", storeB)
        try {
            // Fresh PanelId instance, structurally equal to the registration id —
            // pins the value-equality assumption between the tracker's ids and
            // the stores' keys that the reload path relies on.
            val reset = PanelComponentStoreRegistry.resetPanels(setOf(PanelId("reload-me", 1)))

            assertEquals(2, reset)
            assertNotSame(aReload, storeA.getOrCreateComponent(reloadId))
            assertNotSame(bReload, storeB.getOrCreateComponent(reloadId))
            assertSame(aOther, storeA.getOrCreateComponent(otherId))
        } finally {
            PanelComponentStoreRegistry.unregister("reset-panels-window-a")
            PanelComponentStoreRegistry.unregister("reset-panels-window-b")
        }
    }

    @Test
    fun `store registry tracks stores per window`() {
        val registry = PanelRegistry()
        val storeA = PanelComponentStore(newContext(), registry)
        val storeB = PanelComponentStore(newContext(), registry)

        PanelComponentStoreRegistry.register("tracks-window-a", storeA)
        PanelComponentStoreRegistry.register("tracks-window-b", storeB)
        try {
            assertTrue(PanelComponentStoreRegistry.getAllStores().containsAll(listOf(storeA, storeB)))
        } finally {
            PanelComponentStoreRegistry.unregister("tracks-window-a")
            PanelComponentStoreRegistry.unregister("tracks-window-b")
        }
        assertFalse(PanelComponentStoreRegistry.getAllStores().contains(storeA))
    }
}
