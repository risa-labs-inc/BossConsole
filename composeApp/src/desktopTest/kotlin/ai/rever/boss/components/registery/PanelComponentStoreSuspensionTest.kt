package ai.rever.boss.components.registery

import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.sandbox.PanelSandboxRegistry
import ai.rever.boss.plugin.sandbox.PluginSandbox
import ai.rever.boss.plugin.sandbox.SandboxState
import ai.rever.boss.plugin.sandbox.health.PluginHealthMetrics
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the panel half of the unload race.
 *
 * Closing a plugin's tabs was never enough: nothing removed its sidebar panel before the unload,
 * so `teardownPluginTabs` could only burn its timeout and the panel disposed against a closed
 * classloader anyway - the exact fault the wait exists to prevent.
 * [PanelComponentStoreRegistry.detachPanels] is the missing step, and suspension is what makes it
 * stick: both panel hosts call [PanelComponentStore.getOrCreateComponent] during composition, so a
 * bare `removeComponent` is undone on the next frame.
 *
 * The failure these guard against is asymmetric. A panel that composes too long crashes an unload;
 * a panel that stays suspended is blank for the rest of the session. So resume is tested on the
 * paths that have no plugin left to come back, not just the happy one.
 *
 * Threading: production mutates this state on the UI thread (see the KDocs). These tests run
 * off-EDT, which is safe because each owns its stores and no composition is reading them.
 */
class PanelComponentStoreSuspensionTest {
    private val pluginId = "ai.rever.boss.plugin.dynamic.secretmanager"
    private val otherPluginId = "ai.rever.boss.plugin.dynamic.terminaltab"

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
    ) : PanelComponentWithUI,
        ComponentContext by ctx {
        @Composable
        override fun Content() = Unit
    }

    /** Enough of a sandbox to own a panel in [PanelSandboxRegistry]; only `pluginId` is read. */
    private class FakeSandbox(
        override val pluginId: String,
    ) : PluginSandbox {
        override val state: StateFlow<SandboxState> = MutableStateFlow(SandboxState.RUNNING)
        override val healthMetrics: StateFlow<PluginHealthMetrics> = MutableStateFlow(PluginHealthMetrics())
        override val sandboxScope = CoroutineScope(SupervisorJob())

        override fun recordHeartbeat() = Unit

        override fun recordSuccess() = Unit

        override fun recordError(error: Throwable) = Unit

        override suspend fun start() = Result.success(Unit)

        override suspend fun stop() = Result.success(Unit)

        override suspend fun restart() = Result.success(Unit)

        override fun markUnhealthy() = Unit

        override fun resetHealth() = Unit

        override fun resetRestartAttempts() = Unit
    }

    private fun newStore(vararg ids: PanelId): PanelComponentStore {
        val registry = PanelRegistry()
        ids.forEach { id ->
            registry.registerPanel(panelInfo(id)) { ctx, info -> FakePanelComponent(info, ctx) }
        }
        return PanelComponentStore(registry)
    }

    private fun ownedPanel(
        name: String,
        owner: String,
    ): PanelId {
        val id = PanelId(name, 1)
        PanelSandboxRegistry.register(id, FakeSandbox(owner))
        return id
    }

    @BeforeTest
    fun setUp() {
        PanelSandboxRegistry.clear()
        PanelComponentStoreRegistry.clearSuspensions()
    }

    @AfterTest
    fun tearDown() {
        PanelSandboxRegistry.clear()
        PanelComponentStoreRegistry.clearSuspensions()
    }

    @Test
    fun `detaching drops the cached component and keeps it from coming back`() {
        val id = ownedPanel("secrets", pluginId)
        val store = newStore(id)
        PanelComponentStoreRegistry.register("window-1", store)
        assertNotNull(store.getOrCreateComponent(id), "precondition: the panel is open")

        val detached = PanelComponentStoreRegistry.detachPanels(pluginId)

        assertEquals(1, detached, "the open panel should have been detached")
        assertFalse(store.activeComponents.containsKey(id), "and dropped from the store")
        assertNull(
            store.getOrCreateComponent(id),
            "the next frame must NOT re-instantiate it - that is the whole point of suspending, " +
                "since both panel hosts call getOrCreateComponent from composition",
        )

        PanelComponentStoreRegistry.unregister("window-1")
    }

    @Test
    fun `resuming lets the panel come back, rebuilt rather than restored`() {
        val id = ownedPanel("secrets", pluginId)
        val store = newStore(id)
        PanelComponentStoreRegistry.register("window-1", store)
        val before = store.getOrCreateComponent(id)

        PanelComponentStoreRegistry.detachPanels(pluginId)
        PanelComponentStoreRegistry.resumePanels(pluginId)

        val after = store.getOrCreateComponent(id)
        assertNotNull(after, "resume must let the slot fill again")
        assertNotSame(before, after, "and from the current factory - a reload is why we detached")

        PanelComponentStoreRegistry.unregister("window-1")
    }

    @Test
    fun `detaching one plugin leaves another plugin's panel alone`() {
        val mine = ownedPanel("secrets", pluginId)
        val theirs = ownedPanel("terminal", otherPluginId)
        val store = newStore(mine, theirs)
        PanelComponentStoreRegistry.register("window-1", store)
        store.getOrCreateComponent(mine)
        val untouched = store.getOrCreateComponent(theirs)

        PanelComponentStoreRegistry.detachPanels(pluginId)

        assertFalse(PanelComponentStoreRegistry.isSuspended(otherPluginId))
        assertEquals(
            untouched,
            store.getOrCreateComponent(theirs),
            "an unload of one plugin must not disturb another's panel - they share the store",
        )

        PanelComponentStoreRegistry.unregister("window-1")
    }

    @Test
    fun `detaching reaches every window`() {
        val id = ownedPanel("secrets", pluginId)
        val storeA = newStore(id)
        val storeB = newStore(id)
        PanelComponentStoreRegistry.register("window-1", storeA)
        PanelComponentStoreRegistry.register("window-2", storeB)
        storeA.getOrCreateComponent(id)
        storeB.getOrCreateComponent(id)

        val detached = PanelComponentStoreRegistry.detachPanels(pluginId)

        assertEquals(2, detached, "one unload, every window - the loader is process-wide")
        assertFalse(storeA.activeComponents.containsKey(id))
        assertFalse(storeB.activeComponents.containsKey(id))

        PanelComponentStoreRegistry.unregister("window-1")
        PanelComponentStoreRegistry.unregister("window-2")
    }

    @Test
    fun `a panel with no sandbox is never suspended`() {
        val id = PanelId("host-owned", 1)
        val store = newStore(id)
        PanelComponentStoreRegistry.register("window-1", store)
        val hostPanel = store.getOrCreateComponent(id)

        PanelComponentStoreRegistry.detachPanels(pluginId)

        assertEquals(
            hostPanel,
            store.getOrCreateComponent(id),
            "host panels have no owning plugin, so no unload can take them off screen",
        )

        PanelComponentStoreRegistry.unregister("window-1")
    }

    @Test
    fun `resume works for a plugin that never came back`() {
        val id = ownedPanel("secrets", pluginId)
        val store = newStore(id)
        PanelComponentStoreRegistry.register("window-1", store)
        store.getOrCreateComponent(id)
        PanelComponentStoreRegistry.detachPanels(pluginId)

        // A remove, or a reload that failed: the plugin is gone, so nothing re-registers its
        // panels. Resume still has to clear, or the slot is blank until the app restarts.
        PanelSandboxRegistry.clear()
        PanelComponentStoreRegistry.resumePanels(pluginId)

        assertFalse(
            PanelComponentStoreRegistry.isSuspended(pluginId),
            "a suspension that outlives its unload blanks the slot for the rest of the session",
        )
        assertTrue(store.activeComponents.isEmpty(), "and the stale component stays gone")

        PanelComponentStoreRegistry.unregister("window-1")
    }
}
