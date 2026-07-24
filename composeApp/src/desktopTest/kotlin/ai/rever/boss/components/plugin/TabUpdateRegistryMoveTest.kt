package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabUpdateProvider
import ai.rever.boss.plugin.api.TabUpdateProviderFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins down that a [TabUpdateProvider] handed out by [TabUpdateRegistry] follows its tab
 * across panel moves. Plugins cache the provider for the tab's lifetime (e.g. the browser
 * plugin wires JxBrowser navigation/title listeners against it once at browser init), so
 * a provider bound to the component that owned the tab at creation time would silently
 * stop updating the tab bar after the tab moves to another split panel.
 */
class TabUpdateRegistryMoveTest {
    private val typeId = TabTypeId("fluck")

    /** Factory owning a mutable set of tab ids; records every update it receives. */
    private class RecordingFactory : TabUpdateProviderFactory {
        val ownedTabs = mutableSetOf<String>()
        val titles = mutableListOf<String>()
        val favicons = mutableListOf<String?>()
        val urls = mutableListOf<String>()
        val openedUrls = mutableListOf<String>()
        var closeCount = 0

        override fun createProvider(
            tabId: String,
            typeId: TabTypeId,
        ): TabUpdateProvider? {
            if (tabId !in ownedTabs) return null
            return object : TabUpdateProvider {
                override val tabId: String = tabId

                override fun updateTitle(title: String) {
                    titles += title
                }

                override fun updateFavicon(faviconUrl: String?) {
                    favicons += faviconUrl
                }

                override fun updateUrl(url: String) {
                    urls += url
                }

                override fun closeTab() {
                    closeCount++
                }

                override fun openNewTab(url: String): String? {
                    openedUrls += url
                    return "new-tab-id"
                }
            }
        }
    }

    @AfterTest
    fun tearDown() {
        TabUpdateRegistry.clear()
    }

    @Test
    fun `cached provider routes updates to the tab's current owner after a move`() {
        val sourcePanel = RecordingFactory().apply { ownedTabs += "tab-1" }
        val targetPanel = RecordingFactory()
        TabUpdateRegistry.register("source", sourcePanel)
        TabUpdateRegistry.register("target", targetPanel)
        TabUpdateRegistry.registerTab("tab-1", "source")

        val provider = TabUpdateRegistry.createProvider("tab-1", typeId)
        assertNotNull(provider)
        provider.updateTitle("before move")

        // Simulate a cross-panel move: ownership transfers source -> target.
        sourcePanel.ownedTabs -= "tab-1"
        targetPanel.ownedTabs += "tab-1"
        TabUpdateRegistry.unregisterTab("tab-1", "source")
        TabUpdateRegistry.registerTab("tab-1", "target")

        provider.updateTitle("after move")

        assertEquals(listOf("before move"), sourcePanel.titles)
        assertEquals(listOf("after move"), targetPanel.titles)
    }

    @Test
    fun `createProvider returns null when no component owns the tab`() {
        TabUpdateRegistry.register("source", RecordingFactory())
        assertNull(TabUpdateRegistry.createProvider("unknown", typeId))
    }

    @Test
    fun `all provider methods delegate to the current owner`() {
        val panel = RecordingFactory().apply { ownedTabs += "tab-1" }
        TabUpdateRegistry.register("panel", panel)
        TabUpdateRegistry.registerTab("tab-1", "panel")

        val provider = TabUpdateRegistry.createProvider("tab-1", typeId)!!
        provider.updateTitle("t")
        provider.updateFavicon("f.ico")
        provider.updateFavicon(null)
        provider.updateUrl("https://example.com")
        provider.closeTab()
        val newTabId = provider.openNewTab("https://example.org")

        assertEquals("tab-1", provider.tabId)
        assertEquals(listOf("t"), panel.titles)
        assertEquals(listOf("f.ico", null), panel.favicons)
        assertEquals(listOf("https://example.com"), panel.urls)
        assertEquals(1, panel.closeCount)
        assertEquals(listOf("https://example.org"), panel.openedUrls)
        assertEquals("new-tab-id", newTabId)
    }

    @Test
    fun `cached provider no-ops safely when the owner is gone mid-flight`() {
        val panel = RecordingFactory().apply { ownedTabs += "tab-1" }
        TabUpdateRegistry.register("panel", panel)
        TabUpdateRegistry.registerTab("tab-1", "panel")
        val provider = TabUpdateRegistry.createProvider("tab-1", typeId)!!

        // Tab closed: mapping and ownership are gone, but the plugin still holds the provider.
        TabUpdateRegistry.unregisterTab("tab-1", "panel")
        panel.ownedTabs -= "tab-1"

        provider.updateTitle("late")
        provider.closeTab()
        assertNull(provider.openNewTab("https://example.com"))

        assertEquals(emptyList(), panel.titles)
        assertEquals(0, panel.closeCount)
    }
}
