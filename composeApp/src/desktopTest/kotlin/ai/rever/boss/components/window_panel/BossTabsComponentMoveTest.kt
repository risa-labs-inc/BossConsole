package ai.rever.boss.components.window_panel

import ai.rever.boss.components.plugin.TabUpdateRegistry
import ai.rever.boss.components.window_panel.components.main_window_panels.BossTabsComponent
import ai.rever.boss.plugin.api.ApplicationEvent
import ai.rever.boss.plugin.api.ApplicationEventBusRegistry
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabEvent
import ai.rever.boss.plugin.api.TabEventType
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.Lifecycle
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins down the tab lifecycle/move contract of [BossTabsComponent]:
 *
 * - Closing a tab destroys the component's own lifecycle, so dynamic plugin tab
 *   components that clean up in lifecycle.onDestroy (e.g. fluck-browser disposing its
 *   JxBrowser handle) actually release their resources. Previously all tab components
 *   shared a panel-level lifecycle that was never destroyed, so moving or closing a
 *   browser tab leaked a live Chromium process (audio kept playing in the background).
 *
 * - Moving a tab between split panels via [BossTabsComponent.detachTab] /
 *   [BossTabsComponent.adoptTab] transfers the LIVE component instance: no destroy, no
 *   recreate-from-config (which reloaded browser tabs), and closing the tab in its new
 *   panel still destroys it exactly once.
 */
class BossTabsComponentMoveTest {
    private object TestTabType : TabTypeInfo {
        override val typeId = TabTypeId("move-test", "test.plugin")
        override val displayName = "Move Test"
        override val icon = Icons.Outlined.Language
    }

    private data class TestTabInfo(
        override val id: String,
        override val typeId: TabTypeId = TestTabType.typeId,
        override val title: String = "Test Tab",
    ) : TabInfo {
        override val icon get() = Icons.Outlined.Language
    }

    /** Mimics a dynamic plugin tab component: cleans up in lifecycle.onDestroy. */
    private class TestTabComponent(
        ctx: ComponentContext,
        override val config: TabInfo,
    ) : TabComponentWithUI,
        ComponentContext by ctx {
        override val tabTypeInfo: TabTypeInfo = TestTabType
        var destroyCount = 0
        var resumeCount = 0

        init {
            lifecycle.subscribe(
                callbacks =
                    object : Lifecycle.Callbacks {
                        override fun onResume() {
                            resumeCount++
                        }

                        override fun onDestroy() {
                            destroyCount++
                        }
                    },
            )
        }

        @Composable
        override fun Content() {
        }
    }

    private val tabRegistry =
        TabRegistry().apply {
            registerTabType(TestTabType) { config, ctx -> TestTabComponent(ctx, config) }
        }

    private fun newPanel() =
        BossTabsComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            tabRegistry = tabRegistry,
            windowId = "test-window",
        )

    @AfterTest
    fun tearDown() {
        TabUpdateRegistry.clear()
    }

    @Test
    fun `removeTab destroys the tab component's lifecycle`() {
        val panel = newPanel()
        val index = panel.addTab(TestTabInfo(id = "tab-1"))
        assertTrue(index >= 0)

        val component = panel.getComponentById("tab-1") as TestTabComponent
        assertEquals(0, component.destroyCount)

        panel.removeTab(index)

        assertEquals(1, component.destroyCount)
        assertNull(panel.getComponentById("tab-1"))
    }

    @Test
    fun `removeTab advances through failing pause and stop then removes navigation`() {
        val panel = newPanel()
        panel.addTab(TestTabInfo(id = "failing-tab"))
        val component = panel.getComponentById("failing-tab") as TestTabComponent
        component.lifecycle.subscribe(
            object : Lifecycle.Callbacks {
                override fun onPause(): Unit = throw NoClassDefFoundError("pause failed")

                override fun onStop(): Unit = throw NoClassDefFoundError("stop failed")
            },
        )

        assertTrue(panel.removeTabById("failing-tab", recordForReopen = false))
        assertFalse(panel.removeTabById("failing-tab", recordForReopen = false))
        assertEquals(Lifecycle.State.DESTROYED, component.lifecycle.state)
        assertEquals(1, component.destroyCount)
        assertTrue(
            panel.tabsState.value.tabs
                .isEmpty(),
        )
    }

    @Test
    fun `clearAllTabs continues to siblings after a destroy callback throws`() {
        val panel = newPanel()
        panel.addTab(TestTabInfo(id = "sibling-tab"))
        panel.addTab(TestTabInfo(id = "failing-tab"))
        val failing = panel.getComponentById("failing-tab") as TestTabComponent
        val sibling = panel.getComponentById("sibling-tab") as TestTabComponent
        var failingDestroyCalls = 0
        failing.lifecycle.subscribe(
            object : Lifecycle.Callbacks {
                override fun onDestroy() {
                    failingDestroyCalls++
                    throw NoClassDefFoundError("destroy failed")
                }
            },
        )

        panel.clearAllTabs()
        panel.clearAllTabs()
        assertEquals(1, failingDestroyCalls)
        assertEquals(Lifecycle.State.DESTROYED, failing.lifecycle.state)
        assertEquals(1, sibling.destroyCount)
        assertTrue(
            panel.tabsState.value.tabs
                .isEmpty(),
        )
        assertNull(panel.getComponentById("failing-tab"))
        assertNull(panel.getComponentById("sibling-tab"))
    }

    @Test
    fun `a failed close event subscriber does not strand a live tab`() {
        val panel = newPanel()
        panel.addTab(TestTabInfo(id = "event-failure"))
        val component = panel.getComponentById("event-failure") as TestTabComponent
        val previousPublisher = ApplicationEventBusRegistry.systemPublisher
        ApplicationEventBusRegistry.systemPublisher = { throw NoClassDefFoundError("event subscriber failed") }
        try {
            assertTrue(panel.removeTabById("event-failure", recordForReopen = false))
        } finally {
            ApplicationEventBusRegistry.systemPublisher = previousPublisher
        }
        assertEquals(1, component.destroyCount)
        assertTrue(
            panel.tabsState.value.tabs
                .isEmpty(),
        )
    }

    @Test
    fun `destroying an unadopted detached tab isolates lifecycle failures and runs once`() {
        val panel = newPanel()
        panel.addTab(TestTabInfo(id = "detached-failure"))
        val component = panel.getComponentById("detached-failure") as TestTabComponent
        var failingDestroyCalls = 0
        component.lifecycle.subscribe(
            object : Lifecycle.Callbacks {
                override fun onPause(): Unit = throw NoClassDefFoundError("pause failed")

                override fun onDestroy() {
                    failingDestroyCalls++
                    throw NoClassDefFoundError("destroy failed")
                }
            },
        )
        val detached = assertNotNull(panel.detachTab("detached-failure"))
        detached.destroy()
        detached.destroy()
        assertEquals(Lifecycle.State.DESTROYED, component.lifecycle.state)
        assertEquals(1, failingDestroyCalls)
        assertTrue(
            panel.tabsState.value.tabs
                .isEmpty(),
        )
    }

    @Test
    fun `close event reentry cannot close the same tab or publish twice`() {
        val panel = newPanel()
        panel.addTab(TestTabInfo(id = "reentrant-close"))
        val component = panel.getComponentById("reentrant-close") as TestTabComponent
        val previousPublisher = ApplicationEventBusRegistry.systemPublisher
        var closedEvents = 0
        ApplicationEventBusRegistry.systemPublisher = { event ->
            if (event is TabEvent && event.tabType == TabEventType.CLOSED) {
                closedEvents++
                assertFalse(panel.removeTabById(event.tabId, recordForReopen = false))
            }
        }
        try {
            assertTrue(panel.removeTabById("reentrant-close", recordForReopen = false))
        } finally {
            ApplicationEventBusRegistry.systemPublisher = previousPublisher
        }
        assertEquals(1, closedEvents)
        assertEquals(1, component.destroyCount)
        assertTrue(
            panel.tabsState.value.tabs
                .isEmpty(),
        )
    }

    @Test
    fun `destroy callback closing a preceding sibling preserves remaining tab and pins`() {
        val panel = newPanel()
        panel.addTab(TestTabInfo(id = "preceding"))
        panel.addTab(TestTabInfo(id = "closing"))
        panel.addTab(TestTabInfo(id = "survivor"))
        panel.pinTab(0)
        panel.pinTab(1)
        val preceding = panel.getComponentById("preceding") as TestTabComponent
        val closing = panel.getComponentById("closing") as TestTabComponent
        val survivor = panel.getComponentById("survivor") as TestTabComponent
        closing.lifecycle.subscribe(
            object : Lifecycle.Callbacks {
                override fun onDestroy() {
                    panel.removeTabById("preceding", recordForReopen = false)
                }
            },
        )

        assertTrue(panel.removeTabById("closing", recordForReopen = false))
        assertEquals(
            listOf("survivor"),
            panel.tabsState.value.tabs
                .map { it.id },
        )
        assertEquals(0, panel.pinnedCount)
        assertEquals(
            "survivor",
            panel.tabsState.value.activeTab
                ?.id,
        )
        assertEquals(1, preceding.destroyCount)
        assertEquals(1, closing.destroyCount)
        assertEquals(0, survivor.destroyCount)
        assertSame(survivor, panel.getComponentById("survivor"))
        panel.clearAllTabs()
        assertEquals(1, survivor.destroyCount)
    }

    @Test
    fun `addTab drives the tab lifecycle to RESUMED`() {
        // Load-bearing: Essenty's destroy() below CREATED is a silent no-op, so if addTab
        // ever stops resume()-ing the registry, onDestroy cleanup silently stops firing.
        val panel = newPanel()
        panel.addTab(TestTabInfo(id = "tab-1"))

        val component = panel.getComponentById("tab-1") as TestTabComponent
        assertEquals(1, component.resumeCount)
        assertEquals(Lifecycle.State.RESUMED, component.lifecycle.state)
    }

    @Test
    fun `addTab returns -1 and stores nothing for an unregistered tab type`() {
        val panel = newPanel()
        val unknownTab =
            TestTabInfo(
                id = "tab-1",
                typeId = TabTypeId("unknown-type", "test.plugin"),
            )

        assertEquals(-1, panel.addTab(unknownTab))
        assertNull(panel.getComponentById("tab-1"))
        assertTrue(
            panel.tabsState.value.tabs
                .isEmpty(),
        )
    }

    @Test
    fun `detachTab and adoptTab move the live component without destroying it`() {
        val source = newPanel()
        val target = newPanel()
        source.addTab(TestTabInfo(id = "tab-1"))
        val component = source.getComponentById("tab-1") as TestTabComponent

        val detached = source.detachTab("tab-1")

        assertNotNull(detached)
        assertEquals(0, component.destroyCount)
        assertNull(source.getComponentById("tab-1"))
        assertTrue(
            source.tabsState.value.tabs
                .isEmpty(),
        )

        val newIndex = target.adoptTab(detached)

        assertTrue(newIndex >= 0)
        assertSame(component, target.getComponentById("tab-1"))
        assertEquals(
            "tab-1",
            target.tabsState.value.tabs
                .single()
                .id,
        )
        assertEquals(0, component.destroyCount)

        // Closing the tab in its new panel still destroys it exactly once.
        target.removeTabById("tab-1")
        assertEquals(1, component.destroyCount)
    }

    @Test
    fun `detachTab returns null for unknown tab`() {
        assertNull(newPanel().detachTab("nope"))
    }

    @Test
    fun `a move publishes MOVED instead of a CLOSED-OPENED pair`() {
        val source = newPanel()
        val target = newPanel()
        source.addTab(TestTabInfo(id = "tab-1"))

        val events = mutableListOf<ApplicationEvent>()
        val previousPublisher = ApplicationEventBusRegistry.systemPublisher
        ApplicationEventBusRegistry.systemPublisher = { events += it }
        try {
            target.adoptTab(source.detachTab("tab-1")!!)
        } finally {
            ApplicationEventBusRegistry.systemPublisher = previousPublisher
        }

        val tabEvents = events.filterIsInstance<TabEvent>().filter { it.tabId == "tab-1" }
        assertEquals(listOf(TabEventType.MOVED), tabEvents.map { it.tabType })
    }

    @Test
    fun `removeTabById reports whether a tab was removed`() {
        val panel = newPanel()
        panel.addTab(TestTabInfo(id = "tab-1"))

        assertTrue(panel.removeTabById("tab-1"))
        assertFalse(panel.removeTabById("tab-1"))
    }

    @Test
    fun `adoptTab closes a stale holder of the same tab id instead of orphaning it`() {
        val source = newPanel()
        val target = newPanel()
        source.addTab(TestTabInfo(id = "tab-1"))
        target.addTab(TestTabInfo(id = "tab-1"))
        val moved = source.getComponentById("tab-1") as TestTabComponent
        val stale = target.getComponentById("tab-1") as TestTabComponent

        val detached = source.detachTab("tab-1")
        target.adoptTab(detached!!)

        // The stale holder was destroyed (not silently dropped from the maps)...
        assertEquals(1, stale.destroyCount)
        // ...and the moved component took its place, alive.
        assertSame(moved, target.getComponentById("tab-1"))
        assertEquals(0, moved.destroyCount)
        assertEquals(
            listOf("tab-1"),
            target.tabsState.value.tabs
                .map { it.id },
        )
    }

    @Test
    fun `splitPanel with a missing target panel rescues the detached tab instead of leaking it`() {
        val splitViewState = SplitViewState(tabRegistry, windowId = "test-window")
        val mainPanel = splitViewState.getPanel("main")!!.tabsComponent
        mainPanel.addTab(TestTabInfo(id = "tab-1"))
        val component = mainPanel.getComponentById("tab-1") as TestTabComponent
        val detached = mainPanel.detachTab("tab-1")!!

        val returnedId =
            splitViewState.splitPanel(
                panelId = "no-such-panel",
                orientation = SplitOrientation.VERTICAL,
                detachedTab = detached,
            )

        // No split was created, and the live component landed back in an existing panel
        // rather than disappearing from the UI while its component keeps running.
        assertEquals("no-such-panel", returnedId)
        assertEquals(listOf("main"), splitViewState.getAllPanels().map { it.id })
        assertSame(component, mainPanel.getComponentById("tab-1"))
        assertEquals(0, component.destroyCount)
    }

    @Test
    fun `splitPanel adopts a detached tab into the new panel without recreating it`() {
        val splitViewState = SplitViewState(tabRegistry, windowId = "test-window")
        val mainPanel = splitViewState.getPanel("main")!!.tabsComponent
        mainPanel.addTab(TestTabInfo(id = "tab-1"))
        val component = mainPanel.getComponentById("tab-1") as TestTabComponent

        // Cross-panel edge drop: detach from the source, adopt into the new split.
        val detached = mainPanel.detachTab("tab-1")
        assertNotNull(detached)
        val newPanelId =
            splitViewState.splitPanel(
                panelId = "main",
                orientation = SplitOrientation.VERTICAL,
                detachedTab = detached,
            )

        val newPanel = splitViewState.getPanel(newPanelId)!!.tabsComponent
        assertSame(component, newPanel.getComponentById("tab-1"))
        assertEquals(
            "tab-1",
            newPanel.tabsState.value.tabs
                .single()
                .id,
        )
        assertTrue(
            mainPanel.tabsState.value.tabs
                .isEmpty(),
        )
        assertEquals(0, component.destroyCount)
    }
}
