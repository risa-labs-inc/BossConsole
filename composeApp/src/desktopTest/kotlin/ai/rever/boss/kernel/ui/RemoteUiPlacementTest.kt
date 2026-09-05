package ai.rever.boss.kernel.ui

import ai.rever.boss.components.registery.PanelComponentStore
import ai.rever.boss.components.registery.PanelComponentStoreRegistry
import ai.rever.boss.components.sidebar.SidebarVisibilitySettings
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeId
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [RemoteUiPlacement] exercised against real (not faked) [PanelRegistry] / [TabRegistry] /
 * [SplitViewState] instances, reached exactly the way production does: through
 * [PanelComponentStoreRegistry] / [SplitViewStateRegistry], keyed by a window id
 * [resolveWindowIdTo] hands back in place of [ai.rever.boss.utils.WindowFocusManager] (BossConsole#54).
 *
 * Window ids are unique per test (a global counter) — these registries are process-wide
 * singletons, so a fixed id would let tests interfere with each other if the suite ever runs
 * concurrently, the same reason [ai.rever.boss.components.registery.PanelComponentStoreResetTest]
 * does it.
 */
class RemoteUiPlacementTest {
    private val registry = RemoteUiSurfaceRegistry()
    private val windowId = "remote-ui-placement-test-${nextWindowId.getAndIncrement()}"
    private lateinit var panelRegistry: PanelRegistry
    private lateinit var tabRegistry: TabRegistry
    private lateinit var splitViewState: SplitViewState
    private var resolvedWindowId: String? = windowId

    private val placement =
        RemoteUiPlacement(
            registry = registry,
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
            resolveWindowId = { resolvedWindowId },
            maxAttempts = RETRY_ATTEMPTS,
            retryDelayMs = RETRY_DELAY_MS,
        )

    @BeforeTest
    fun setUp() {
        panelRegistry = PanelRegistry()
        tabRegistry = TabRegistry()
        splitViewState = SplitViewState(tabRegistry, windowId)
        val panelComponentStore = PanelComponentStore(DefaultComponentContext(LifecycleRegistry()), panelRegistry)
        SplitViewStateRegistry.register(windowId, splitViewState)
        PanelComponentStoreRegistry.register(windowId, panelComponentStore)
    }

    @AfterTest
    fun tearDown() {
        SplitViewStateRegistry.unregister(windowId)
        PanelComponentStoreRegistry.unregister(windowId)
        splitViewState.dispose()
    }

    // ---- Placement reaches the window (1, 2, 3) ----

    @Test
    fun `an authenticated panel registration is placed into the resolvable window`() =
        runBlocking {
            val surfaceId = "panel-1"
            registry.register(surfaceId, "plugin-a", panelDescriptor(surfaceId, slot = "right.top.top"))

            placement.place(surfaceId)

            val placed = awaitPanel(surfaceId)
            assertEquals("Panel $surfaceId", placed.displayName)
        }

    @Test
    fun `an authenticated tab registration is placed into the resolvable window and opened`() =
        runBlocking {
            val surfaceId = "tab-1"
            registry.register(surfaceId, "plugin-a", tabDescriptor(surfaceId))

            placement.place(surfaceId)

            awaitTrue { tabRegistry.isRegistered(TabTypeId(surfaceId, "boss.remote")) }
            val openTab = awaitOpenTab(surfaceId)
            assertEquals("Tab $surfaceId", openTab.title)
            assertEquals(
                0,
                splitViewState
                    .getActiveTabsComponent()
                    ?.tabsState
                    ?.value
                    ?.activeIndex,
                "the new tab should be selected",
            )
        }

    @Test
    fun `default_slot is respected`() =
        runBlocking {
            val surfaceId = "panel-slot"
            registry.register(surfaceId, "plugin-a", panelDescriptor(surfaceId, slot = "left.bottom"))

            placement.place(surfaceId)

            val placed = awaitPanel(surfaceId)
            assertEquals(SidebarVisibilitySettings.panelFor("left.bottom"), placed.defaultSlotPosition)
        }

    // ---- Deterministic fallback (4) ----

    @Test
    fun `an unknown default_slot falls back exactly the way a corrupted sidebar setting would`() =
        runBlocking {
            val surfaceId = "panel-bad-slot"
            registry.register(surfaceId, "plugin-a", panelDescriptor(surfaceId, slot = "not-a-real-slot"))

            placement.place(surfaceId)

            val placed = awaitPanel(surfaceId)
            assertEquals(
                SidebarVisibilitySettings.panelFor("not-a-real-slot"),
                placed.defaultSlotPosition,
                "must be the same deterministic fallback SidebarVisibilitySettings itself uses",
            )
        }

    // ---- Unregister and disconnect (7, 8) ----

    @Test
    fun `unregister removes the placed panel`() =
        runBlocking {
            val surfaceId = "panel-remove"
            registry.register(surfaceId, "plugin-a", panelDescriptor(surfaceId))
            placement.place(surfaceId)
            val panelId = awaitPanel(surfaceId).id

            registry.unregister(surfaceId)
            placement.remove(surfaceId)

            assertNull(panelRegistry.getPanelContent(panelId))
            assertTrue(panelRegistry.getAllPanels().none { it.id.panelId == surfaceId })
        }

    @Test
    fun `unregister removes the placed tab's type and closes the open tab`() =
        runBlocking {
            val surfaceId = "tab-remove"
            registry.register(surfaceId, "plugin-a", tabDescriptor(surfaceId))
            placement.place(surfaceId)
            awaitOpenTab(surfaceId)

            registry.unregister(surfaceId)
            placement.remove(surfaceId)

            assertFalse(tabRegistry.isRegistered(TabTypeId(surfaceId, "boss.remote")))
            awaitTrue {
                splitViewState
                    .getActiveTabsComponent()
                    ?.tabsState
                    ?.value
                    ?.tabs
                    ?.none { it.id == "remote-tab:$surfaceId" } == true
            }
        }

    @Test
    fun `a disconnect alone - no UnregisterUI - leaves the placed panel in place`() =
        runBlocking {
            // RemoteUiSurfaceRegistry's own contract for a dead stream is "stays attached, reads
            // connected == false" (closeStream's KDoc) - the placement must not fight that by
            // un-placing on a mere disconnect. Simulated here by never calling registry.unregister
            // or placement.remove, which is exactly what a stream death (as opposed to a graceful
            // UnregisterUI) does today - see PluginUIServiceBridge.streamUI's teardown.
            val surfaceId = "panel-disconnect"
            registry.register(surfaceId, "plugin-a", panelDescriptor(surfaceId))
            placement.place(surfaceId)
            awaitPanel(surfaceId)

            // The surface is still registered (no UnregisterUI happened), so the panel must still
            // be there - disconnect is a RemoteUiSurfaceHost/connected-state concern, not a
            // placement one.
            assertTrue(panelRegistry.getPanelContent(placedPanelId(surfaceId)) != null)
        }

    // ---- Duplicates and races (9, 10) ----

    @Test
    fun `a duplicate place call for an already-placed surface is a no-op`() =
        runBlocking {
            val surfaceId = "panel-duplicate"
            registry.register(surfaceId, "plugin-a", panelDescriptor(surfaceId))
            placement.place(surfaceId)
            awaitPanel(surfaceId)

            // A second acceptance of the same surface (e.g. a same-process reclaim) must not
            // register a second PanelInfo or otherwise duplicate the surface.
            placement.place(surfaceId)
            delay(SETTLE_MS)

            assertEquals(1, panelRegistry.getAllPanels().count { it.id.panelId == surfaceId })
        }

    @Test
    fun `concurrent place calls for the same surface never produce two panels`() =
        runBlocking {
            val surfaceId = "panel-race"
            registry.register(surfaceId, "plugin-a", panelDescriptor(surfaceId))

            // Many concurrent callers, as a burst of duplicate RegisterUI acceptances (a reclaim
            // racing the original registration) could produce.
            repeat(20) { placement.place(surfaceId) }
            awaitPanel(surfaceId)
            delay(SETTLE_MS)

            assertEquals(1, panelRegistry.getAllPanels().count { it.id.panelId == surfaceId })
        }

    @Test
    fun `unregistering while placement is still retrying leaves nothing behind`() =
        runBlocking {
            // No window resolvable yet - place() will be retrying when remove() arrives, exactly
            // the register/unregister race #54 asks for: the retry must not go on to place a
            // surface that was already torn down.
            resolvedWindowId = null
            val surfaceId = "panel-race-unregister"
            registry.register(surfaceId, "plugin-a", panelDescriptor(surfaceId))
            placement.place(surfaceId)
            delay(SETTLE_MS) // let at least one failed attempt happen

            registry.unregister(surfaceId)
            placement.remove(surfaceId)
            resolvedWindowId = windowId // now let a retry succeed, if one were still going to run

            delay(RETRY_DELAY_MS * (RETRY_ATTEMPTS + 1))

            assertTrue(panelRegistry.getAllPanels().none { it.id.panelId == surfaceId }, "a torn-down surface must never appear")
        }

    // ---- Helpers ----

    private fun placedPanelId(surfaceId: String) = panelRegistry.getAllPanels().first { it.id.panelId == surfaceId }.id

    private suspend fun awaitPanel(surfaceId: String): PanelInfo =
        withTimeout(AWAIT_TIMEOUT_MS) {
            while (true) {
                panelRegistry.getAllPanels().firstOrNull { it.id.panelId == surfaceId }?.let { return@withTimeout it }
                delay(POLL_MS)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    private suspend fun awaitOpenTab(surfaceId: String): TabInfo =
        withTimeout(AWAIT_TIMEOUT_MS) {
            while (true) {
                splitViewState
                    .getActiveTabsComponent()
                    ?.tabsState
                    ?.value
                    ?.tabs
                    ?.firstOrNull { it.id == "remote-tab:$surfaceId" }
                    ?.let { return@withTimeout it }
                delay(POLL_MS)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    private suspend fun awaitTrue(condition: () -> Boolean) {
        withTimeout(AWAIT_TIMEOUT_MS) {
            while (!condition()) delay(POLL_MS)
        }
    }

    private fun panelDescriptor(
        surfaceId: String,
        slot: String = "left.top.top",
    ) = RemoteUiSurfaceDescriptor(
        surfaceType = "panel",
        displayName = "Panel $surfaceId",
        iconName = "",
        defaultSlot = slot,
    )

    private fun tabDescriptor(surfaceId: String) =
        RemoteUiSurfaceDescriptor(
            surfaceType = "tab",
            displayName = "Tab $surfaceId",
            iconName = "",
            defaultSlot = "",
        )

    private companion object {
        val nextWindowId = AtomicInteger(0)
        const val AWAIT_TIMEOUT_MS = 10_000L
        const val POLL_MS = 10L
        const val SETTLE_MS = 100L
        const val RETRY_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 40L
    }
}
