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
import com.arkivanov.essenty.lifecycle.Lifecycle
import com.arkivanov.essenty.lifecycle.doOnCreate
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    private val testIcon =
        ImageVector
            .Builder(
                defaultWidth = 1.dp,
                defaultHeight = 1.dp,
                viewportWidth = 1f,
                viewportHeight = 1f,
            ).build()

    private fun panelInfo(id: PanelId) =
        object : PanelInfo {
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
        private val onInitializedAction: () -> Unit = {},
        private val onDestroyAction: () -> Unit = {},
    ) : PanelComponentWithUI,
        ComponentContext by ctx {
        var destroyCount = 0
            private set

        init {
            lifecycle.subscribe(
                callbacks =
                    object : Lifecycle.Callbacks {
                        override fun onDestroy() {
                            destroyCount++
                            onDestroyAction()
                        }
                    },
            )
        }

        @Composable
        override fun Content() {
        }

        override fun onInitialized() = onInitializedAction()

        override fun onBeforeReset() = onBeforeResetAction()
    }

    private fun registerFactory(
        registry: PanelRegistry,
        id: PanelId,
        generation: Int,
        onBeforeReset: () -> Unit = {},
        onDestroy: () -> Unit = {},
    ) {
        registry.registerPanel(panelInfo(id)) { ctx, info ->
            FakePanelComponent(
                panelInfo = info,
                ctx = ctx,
                generation = generation,
                onBeforeResetAction = onBeforeReset,
                onDestroyAction = onDestroy,
            )
        }
    }

    @Test
    fun `removeComponent isolates lifecycle destroy failures and destroys exactly once`() {
        val registry = PanelRegistry()
        val id = PanelId("test-panel", 1)
        registerFactory(
            registry,
            id,
            generation = 1,
            onDestroy = {
                throw NoClassDefFoundError("plugin classloader already closed")
            },
        )
        val store = PanelComponentStore(registry)

        val component = store.getOrCreateComponent(id) as FakePanelComponent

        store.removeComponent(id)
        store.removeComponent(id)

        assertEquals(1, component.destroyCount)
        assertFalse(store.activeComponents.containsKey(id))
    }

    @Test
    fun `dispose isolates failures and destroys every panel lifecycle exactly once`() {
        val registry = PanelRegistry()
        val firstId = PanelId("first-panel", 1)
        val secondId = PanelId("second-panel", 2)
        registerFactory(
            registry,
            firstId,
            generation = 1,
            onDestroy = {
                throw NoClassDefFoundError("plugin classloader already closed")
            },
        )
        registerFactory(registry, secondId, generation = 1)
        val store = PanelComponentStore(registry)

        val first = store.getOrCreateComponent(firstId) as FakePanelComponent
        val second = store.getOrCreateComponent(secondId) as FakePanelComponent

        store.dispose()
        store.dispose()

        assertEquals(1, first.destroyCount)
        assertEquals(1, second.destroyCount)
        assertTrue(store.activeComponents.isEmpty())
    }

    @Test
    fun `open panel keeps its cached component when the factory is re-registered`() {
        val registry = PanelRegistry()
        val id = PanelId("test-panel", 1)
        registerFactory(registry, id, generation = 1)
        val store = PanelComponentStore(registry)

        val opened = store.getOrCreateComponent(id) as FakePanelComponent

        // Simulate a hot reload: unregister the old factory, register the new build's.
        registry.unregisterPanel(id)
        registerFactory(registry, id, generation = 2)

        // The cache wins — this is why an open panel kept rendering the old build.
        assertSame(opened, store.getOrCreateComponent(id))
    }

    @Test
    fun `resetComponent recreates after the old lifecycle destroy fails`() {
        val registry = PanelRegistry()
        val id = PanelId("test-panel", 1)
        registerFactory(
            registry,
            id,
            generation = 1,
            onDestroy = {
                throw NoClassDefFoundError("plugin classloader already closed")
            },
        )
        val store = PanelComponentStore(registry)

        val opened = store.getOrCreateComponent(id) as FakePanelComponent
        assertEquals(1, opened.generation)

        registry.unregisterPanel(id)
        registerFactory(registry, id, generation = 2)

        assertTrue(store.resetComponent(id))
        assertEquals(1, opened.destroyCount)

        val refreshed = store.getOrCreateComponent(id) as FakePanelComponent
        assertNotSame(opened, refreshed)
        assertEquals(2, refreshed.generation)

        store.removeComponent(id)
        assertEquals(1, refreshed.destroyCount)
    }

    @Test
    fun `resetComponent isolates replacement destroy failure when initialization fails`() {
        val registry = PanelRegistry()
        val id = PanelId("test-panel", 1)
        registerFactory(registry, id, generation = 1)
        val store = PanelComponentStore(registry)

        val opened = store.getOrCreateComponent(id) as FakePanelComponent

        registry.unregisterPanel(id)

        lateinit var replacement: FakePanelComponent
        registry.registerPanel(panelInfo(id)) { ctx, info ->
            FakePanelComponent(
                panelInfo = info,
                ctx = ctx,
                generation = 2,
                onInitializedAction = {
                    error("replacement initialization failed")
                },
                onDestroyAction = {
                    throw NoClassDefFoundError("plugin classloader already closed")
                },
            ).also { replacement = it }
        }

        assertFalse(store.resetComponent(id))
        assertEquals(1, opened.destroyCount)
        assertEquals(1, replacement.destroyCount)
        assertFalse(store.activeComponents.containsKey(id))
    }

    @Test
    fun `resetComponent survives the old component's cleanup hook throwing an Error`() {
        val registry = PanelRegistry()
        val id = PanelId("test-panel", 1)
        registerFactory(registry, id, generation = 1, onBeforeReset = {
            throw NoClassDefFoundError("plugin classloader already closed")
        })
        val store = PanelComponentStore(registry)
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
        val store = PanelComponentStore(registry)
        val opened = store.getOrCreateComponent(id) as FakePanelComponent

        registry.unregisterPanel(id)

        // No new factory: the reset fails, but the stale component must not
        // stay cached (it would pin the unloaded plugin's classloader).
        assertFalse(store.resetComponent(id))
        assertEquals(1, opened.destroyCount)
        assertFalse(store.activeComponents.containsKey(id))
    }

    @Test
    fun `resetComponent returns false for a panel that is not open`() {
        val registry = PanelRegistry()
        val id = PanelId("test-panel", 1)
        registerFactory(registry, id, generation = 1)
        val store = PanelComponentStore(registry)

        assertFalse(store.resetComponent(id))
    }

    @Test
    fun `resetPanels resets only matching open slots across windows and leaves others untouched`() {
        val reloadId = PanelId("reload-me", 1)
        val otherId = PanelId("other-panel", 2)

        val registryA = PanelRegistry()
        registerFactory(registryA, reloadId, generation = 1)
        registerFactory(registryA, otherId, generation = 1)
        val storeA = PanelComponentStore(registryA)
        val aReload = storeA.getOrCreateComponent(reloadId)
        val aOther = storeA.getOrCreateComponent(otherId)

        val registryB = PanelRegistry()
        registerFactory(registryB, reloadId, generation = 1)
        val storeB = PanelComponentStore(registryB)
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
        val storeA = PanelComponentStore(registry)
        val storeB = PanelComponentStore(registry)

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

    @Test
    fun `closing one panel does not fire doOnDestroy on another`() {
        val registry = PanelRegistry()
        val idA = PanelId("lifecycle-iso-a", 1)
        val idB = PanelId("lifecycle-iso-b", 2)
        var destroyA = false
        var destroyB = false
        registry.registerPanel(panelInfo(idA)) { ctx, info ->
            object : PanelComponentWithUI, ComponentContext by ctx {
                override val panelInfo = info

                init {
                    lifecycle.doOnDestroy { destroyA = true }
                }

                @Composable
                override fun Content() = Unit
            }
        }
        registry.registerPanel(panelInfo(idB)) { ctx, info ->
            object : PanelComponentWithUI, ComponentContext by ctx {
                override val panelInfo = info

                init {
                    lifecycle.doOnDestroy { destroyB = true }
                }

                @Composable
                override fun Content() = Unit
            }
        }
        val store = PanelComponentStore(registry)
        store.getOrCreateComponent(idA)
        store.getOrCreateComponent(idB)

        store.removeComponent(idA)

        assertTrue(destroyA, "the closed panel's doOnDestroy must fire")
        assertFalse(destroyB, "a sibling panel's lifecycle must be unaffected")
    }

    @Test
    fun `factory failure destroys its partial lifecycle and permits retry`() {
        val registry = PanelRegistry()
        val id = PanelId("factory-failure", 1)
        var destroyed = 0
        registry.registerPanel(panelInfo(id)) { ctx, _ ->
            ctx.lifecycle.doOnDestroy { destroyed++ }
            throw NoClassDefFoundError("factory failed after allocating resources")
        }
        val store = PanelComponentStore(registry)

        assertFailsWith<NoClassDefFoundError> { store.getOrCreateComponent(id) }
        assertEquals(1, destroyed)
        assertTrue(store.activeComponents.isEmpty())
        registerFactory(registry, id, generation = 2)
        val replacement = store.getOrCreateComponent(id) as FakePanelComponent
        store.dispose()
        assertEquals(1, replacement.destroyCount)
        assertEquals(1, destroyed)
    }

    @Test
    fun `reset destroys a replacement whose resume callback throws`() {
        val registry = PanelRegistry()
        val id = PanelId("resume-failure", 1)
        registerFactory(registry, id, generation = 1)
        val store = PanelComponentStore(registry)
        val old = store.getOrCreateComponent(id) as FakePanelComponent
        lateinit var replacement: FakePanelComponent
        registry.registerPanel(panelInfo(id)) { ctx, info ->
            FakePanelComponent(info, ctx, generation = 2).also {
                replacement = it
                ctx.lifecycle.subscribe(
                    object : Lifecycle.Callbacks {
                        override fun onResume(): Unit = throw NoClassDefFoundError("resume failed")
                    },
                )
            }
        }

        assertFalse(store.resetComponent(id))
        assertEquals(1, old.destroyCount)
        assertEquals(1, replacement.destroyCount)
        assertTrue(store.activeComponents.isEmpty())
        store.dispose()
        assertEquals(1, replacement.destroyCount)
    }

    @Test
    fun `pause and stop failures do not prevent final destruction`() {
        val registry = PanelRegistry()
        val id = PanelId("downward-failure", 1)
        registry.registerPanel(panelInfo(id)) { ctx, info ->
            FakePanelComponent(info, ctx, generation = 1).also {
                ctx.lifecycle.subscribe(
                    object : Lifecycle.Callbacks {
                        override fun onPause(): Unit = throw NoClassDefFoundError("pause failed")

                        override fun onStop(): Unit = throw NoClassDefFoundError("stop failed")
                    },
                )
            }
        }
        val store = PanelComponentStore(registry)
        val component = store.getOrCreateComponent(id) as FakePanelComponent

        store.removeComponent(id)
        assertEquals(1, component.destroyCount)
        assertEquals(Lifecycle.State.DESTROYED, component.lifecycle.state)
        store.dispose()
        assertEquals(1, component.destroyCount)
    }

    @Test
    fun `reset saves state before destroying the old lifecycle and initializing the replacement`() {
        val registry = PanelRegistry()
        val id = PanelId("ordered-reset", 1)
        val events = mutableListOf<String>()
        registerFactory(
            registry,
            id,
            generation = 1,
            onBeforeReset = { events += "save" },
            onDestroy = { events += "destroy-old" },
        )
        val store = PanelComponentStore(registry)
        store.getOrCreateComponent(id)
        registry.registerPanel(panelInfo(id)) { ctx, info ->
            events += "create-new"
            FakePanelComponent(
                info,
                ctx,
                generation = 2,
                onInitializedAction = { events += "initialize-new" },
            )
        }

        assertTrue(store.resetComponent(id))
        assertEquals(listOf("save", "destroy-old", "create-new", "initialize-new"), events)
        store.dispose()
    }

    @Test
    fun `created lifecycle replays onCreate during construction exactly once`() {
        val registry = PanelRegistry()
        val id = PanelId("create-replay", 1)
        val events = mutableListOf<String>()
        registry.registerPanel(panelInfo(id)) { ctx, info ->
            ctx.lifecycle.doOnCreate { events += "create" }
            events += "factory"
            FakePanelComponent(
                info,
                ctx,
                generation = 1,
                onDestroyAction = { events += "destroy" },
            )
        }
        val store = PanelComponentStore(registry)
        val component = store.getOrCreateComponent(id) as FakePanelComponent

        assertEquals(listOf("create", "factory"), events)
        assertEquals(Lifecycle.State.RESUMED, component.lifecycle.state)
        store.removeComponent(id)
        store.dispose()
        assertEquals(listOf("create", "factory", "destroy"), events)
    }
}
