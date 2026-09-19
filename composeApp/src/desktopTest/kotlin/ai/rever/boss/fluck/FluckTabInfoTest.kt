package ai.rever.boss.fluck

import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabTypeId
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Warning
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for FluckTabInfo navigation state management.
 *
 * Tests cover:
 * - URL navigation tracking (Issue #379)
 * - Navigation history (back/forward)
 * - Thread safety of URL state
 * - Empty URL handling
 */
class FluckTabInfoTest {
    private fun createTabInfo(
        url: String = "https://example.com",
        currentUrl: String = url,
    ): FluckTabInfo =
        FluckTabInfo(
            id = "test-tab",
            typeId = TabTypeId("fluck"),
            _title = "Test Tab",
            url = url,
            _currentUrl = currentUrl,
        )

    // ==================== URL NAVIGATION TESTS (Issue #379) ====================

    @Test
    fun `currentUrl returns initial URL when no navigation has occurred`() {
        val tabInfo = createTabInfo(url = "https://initial.com")
        assertEquals("https://initial.com", tabInfo.currentUrl)
    }

    @Test
    fun `currentUrl returns navigated URL after navigation`() {
        var tabInfo = createTabInfo(url = "https://initial.com")
        tabInfo = tabInfo.updateNavigation("Page B", "https://navigated.com")

        assertEquals("https://navigated.com", tabInfo.currentUrl)
    }

    @Test
    fun `currentUrl tracks multiple navigations correctly`() {
        var tabInfo = createTabInfo(url = "https://a.com")

        tabInfo = tabInfo.updateNavigation("Page B", "https://b.com")
        assertEquals("https://b.com", tabInfo.currentUrl)

        tabInfo = tabInfo.updateNavigation("Page C", "https://c.com")
        assertEquals("https://c.com", tabInfo.currentUrl)

        tabInfo = tabInfo.updateNavigation("Page D", "https://d.com")
        assertEquals("https://d.com", tabInfo.currentUrl)
    }

    // ==================== NAVIGATION HISTORY TESTS ====================

    @Test
    fun `navigateBack returns to previous URL`() {
        var tabInfo = createTabInfo(url = "https://a.com")
        tabInfo = tabInfo.updateNavigation("Page A", "https://a.com") // Add to history
        tabInfo = tabInfo.updateNavigation("Page B", "https://b.com")

        tabInfo = tabInfo.updateBack()

        assertEquals("https://a.com", tabInfo.currentUrl)
    }

    @Test
    fun `navigateForward returns to next URL after navigateBack`() {
        var tabInfo = createTabInfo(url = "https://a.com")
        tabInfo = tabInfo.updateNavigation("Page A", "https://a.com")
        tabInfo = tabInfo.updateNavigation("Page B", "https://b.com")
        tabInfo = tabInfo.updateBack()

        tabInfo = tabInfo.updateForward()

        assertEquals("https://b.com", tabInfo.currentUrl)
    }

    @Test
    fun `navigateBack at start of history does nothing`() {
        var tabInfo = createTabInfo(url = "https://only.com")
        tabInfo = tabInfo.updateNavigation("Only Page", "https://only.com")

        tabInfo = tabInfo.updateBack() // Should not throw or change URL

        assertEquals("https://only.com", tabInfo.currentUrl)
    }

    @Test
    fun `navigateForward at end of history does nothing`() {
        var tabInfo = createTabInfo(url = "https://last.com")
        tabInfo = tabInfo.updateNavigation("Last Page", "https://last.com")

        tabInfo = tabInfo.updateForward() // Should not throw or change URL

        assertEquals("https://last.com", tabInfo.currentUrl)
    }

    @Test
    fun `navigation after navigateBack truncates forward history`() {
        var tabInfo = createTabInfo(url = "https://a.com")
        tabInfo = tabInfo.updateNavigation("Page A", "https://a.com")
        tabInfo = tabInfo.updateNavigation("Page B", "https://b.com")
        tabInfo = tabInfo.updateNavigation("Page C", "https://c.com")

        tabInfo = tabInfo.updateBack() // Now at B
        tabInfo = tabInfo.updateNavigation("Page D", "https://d.com") // Should truncate C

        assertEquals("https://d.com", tabInfo.currentUrl)

        // Forward should not go to C (it was truncated)
        tabInfo = tabInfo.updateForward()
        assertEquals("https://d.com", tabInfo.currentUrl) // Still at D
    }

    // ==================== EMPTY URL HANDLING TESTS ====================

    @Test
    fun `empty URL is handled gracefully`() {
        val tabInfo = createTabInfo(url = "")
        assertEquals("", tabInfo.currentUrl)
    }

    @Test
    fun `navigation to empty URL updates currentUrl`() {
        var tabInfo = createTabInfo(url = "https://initial.com")
        tabInfo = tabInfo.updateNavigation("Empty", "")

        assertEquals("", tabInfo.currentUrl)
    }

    // ==================== THREAD SAFETY TESTS ====================

    @Test
    fun `concurrent navigation updates are thread-safe`() {
        val tabInfo = createTabInfo(url = "https://initial.com")
        val executor = Executors.newFixedThreadPool(10)
        val latch = CountDownLatch(100)
        val errors = mutableListOf<Throwable>()
        val results = java.util.Collections.synchronizedList(mutableListOf<FluckTabInfo>())

        repeat(100) { i ->
            executor.submit {
                try {
                    val updated = tabInfo.updateNavigation("Page $i", "https://page$i.com")
                    results.add(updated)
                } catch (e: Throwable) {
                    synchronized(errors) { errors.add(e) }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for threads")
        executor.shutdown()

        assertTrue(errors.isEmpty(), "Concurrent navigation caused errors: $errors")
        assertEquals(100, results.size)
        results.forEach { result ->
            assertTrue(result.currentUrl.startsWith("https://page"))
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun `deprecated mutable navigation is thread-safe for backwards compatibility`() {
        val tabInfo = createTabInfo(url = "https://initial.com")
        val executor = Executors.newFixedThreadPool(10)
        val latch = CountDownLatch(100)
        val errors = mutableListOf<Throwable>()

        repeat(100) { i ->
            executor.submit {
                try {
                    tabInfo.navigateToPage("Page $i", "https://page$i.com")
                } catch (e: Throwable) {
                    synchronized(errors) { errors.add(e) }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for threads")
        executor.shutdown()

        assertTrue(errors.isEmpty(), "Concurrent mutable navigation caused errors: $errors")
        assertTrue(tabInfo.currentUrl.startsWith("https://page"))
    }

    @Test
    fun `concurrent reads and writes are thread-safe`() {
        val tabInfo = createTabInfo(url = "https://initial.com")
        val executor = Executors.newFixedThreadPool(10)
        val latch = CountDownLatch(200)
        val errors = mutableListOf<Throwable>()
        val readResults = mutableListOf<String>()

        // 100 writers
        repeat(100) { i ->
            executor.submit {
                try {
                    tabInfo.updateNavigation("Page $i", "https://page$i.com")
                } catch (e: Throwable) {
                    synchronized(errors) { errors.add(e) }
                } finally {
                    latch.countDown()
                }
            }
        }

        // 100 readers
        repeat(100) {
            executor.submit {
                try {
                    val url = tabInfo.currentUrl
                    synchronized(readResults) { readResults.add(url) }
                } catch (e: Throwable) {
                    synchronized(errors) { errors.add(e) }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for threads")
        executor.shutdown()

        assertTrue(errors.isEmpty(), "Concurrent read/write caused errors: $errors")
        // All reads should be valid URLs (not corrupted)
        readResults.forEach { url ->
            assertTrue(
                url == "https://initial.com" || url.startsWith("https://page"),
                "Invalid URL read: $url",
            )
        }
    }

    // ==================== DUPLICATE NAVIGATION TESTS ====================

    @Test
    fun `duplicate consecutive navigation does not add to history`() {
        var tabInfo = createTabInfo(url = "https://a.com")
        tabInfo = tabInfo.updateNavigation("Page A", "https://a.com")
        val initialHistorySize = tabInfo.navigationHistory.size

        tabInfo = tabInfo.updateNavigation("Page A", "https://a.com") // Same URL

        assertEquals(initialHistorySize, tabInfo.navigationHistory.size)
    }

    // ==================== SHALLOW COPY FIX TESTS (Issue #406) ====================

    @Test
    fun `copy creates independent navigation history`() {
        // Create original tab with navigation history
        var original = createTabInfo(url = "https://a.com")
        original = original.updateNavigation("Page A", "https://a.com")
        original = original.updateNavigation("Page B", "https://b.com")

        // Create a copy using updateTitle (which calls copy internally)
        val copied = original.updateTitle("New Title")

        // Assert distinct list identity to verify copy is not sharing list reference (Issue #406)
        assertTrue(copied.navigationHistory !== original.navigationHistory, "Copy must not share navigationHistory reference")

        // Verify initial state is the same
        assertEquals(2, original.navigationHistory.size)
        assertEquals(2, copied.navigationHistory.size)
        assertEquals("https://b.com", original.currentUrl)
        assertEquals("https://b.com", copied.currentUrl)

        // Modify navigation on the copy
        val copiedNavigated = copied.updateNavigation("Page C", "https://c.com")
        assertTrue(copiedNavigated.navigationHistory !== original.navigationHistory, "Navigated copy must not share reference with original")

        // Original should not be affected (this would fail with shallow copy)
        assertEquals(2, original.navigationHistory.size)
        assertEquals("https://b.com", original.currentUrl)
        assertEquals(1, original.historyIndex)

        // Copy should have its own history
        assertEquals(3, copiedNavigated.navigationHistory.size)
        assertEquals("https://c.com", copiedNavigated.currentUrl)
        assertEquals(2, copiedNavigated.historyIndex)
    }

    @Test
    fun `copy with back navigation creates independent history`() {
        // Create original tab with navigation history
        var original = createTabInfo(url = "https://a.com")
        original = original.updateNavigation("Page A", "https://a.com")
        original = original.updateNavigation("Page B", "https://b.com")
        original = original.updateNavigation("Page C", "https://c.com")

        // Create a copy using updateTitle
        val copied = original.updateTitle("Copied Tab")
        assertTrue(copied.navigationHistory !== original.navigationHistory, "Copied tab must have distinct list identity")

        // Navigate back on the copy
        val copiedBack = copied.updateBack()
        assertTrue(copiedBack.navigationHistory !== original.navigationHistory, "Back-navigated copy must have distinct list identity")

        // Original should not be affected
        assertEquals("https://c.com", original.currentUrl)
        assertEquals(2, original.historyIndex)

        // Copy should have navigated back
        assertEquals("https://b.com", copiedBack.currentUrl)
        assertEquals(1, copiedBack.historyIndex)

        // Navigate forward on original
        val originalForward = original.updateNavigation("Page D", "https://d.com")
        assertEquals("https://d.com", originalForward.currentUrl)

        // Copy should not be affected
        assertEquals("https://b.com", copiedBack.currentUrl)
        assertEquals(3, copiedBack.navigationHistory.size)
    }

    @Test
    fun `equals is based on id and display content`() {
        val tab1 = createTabInfo(url = "https://a.com").updateNavigation("Page A", "https://a.com")
        val tab2 = createTabInfo(url = "https://b.com").updateNavigation("Page B", "https://b.com")

        // Same id but different content (title, URL) should NOT be equal
        assertTrue(tab1 != tab2)

        // hashCode is still based on ID only, so it may be the same
        assertEquals(tab1.hashCode(), tab2.hashCode())

        // Tabs with same id AND same content should be equal
        val tab3 = createTabInfo(url = "https://a.com").updateNavigation("Page A", "https://a.com")
        assertEquals(tab1, tab3)
    }

    @Test
    fun `equals returns false for different ids`() {
        val tab1 =
            FluckTabInfo(
                id = "tab-1",
                typeId = TabTypeId("fluck"),
                _title = "Tab 1",
                url = "https://a.com",
            )

        val tab2 =
            FluckTabInfo(
                id = "tab-2",
                typeId = TabTypeId("fluck"),
                _title = "Tab 2",
                url = "https://a.com",
            )

        // Different ids should not be equal
        assertTrue(tab1 != tab2)
        assertTrue(tab1.hashCode() != tab2.hashCode())
    }

    // ==================== HOME (DASHBOARD) TAB IDENTITY TESTS ====================

    @Test
    fun `home page shows Home icon instead of the generic globe`() {
        assertEquals(Icons.Outlined.Home, createTabInfo(url = "").icon)
        assertEquals(Icons.Outlined.Home, createTabInfo(url = "about:blank").icon)
        assertEquals(
            TabIcon.Vector(Icons.Outlined.Home),
            createTabInfo(url = "about:blank").tabIcon,
        )
    }

    @Test
    fun `real page keeps the default browser icon`() {
        val tabInfo = createTabInfo(url = "https://example.com")
        assertEquals(Icons.Outlined.Language, tabInfo.icon)
        assertEquals(TabIcon.Vector(Icons.Outlined.Language), tabInfo.tabIcon)
    }

    @Test
    fun `icon follows navigation between home and real pages`() {
        val tabInfo = createTabInfo(url = "about:blank")
        assertEquals(Icons.Outlined.Home, tabInfo.icon)

        val onPage = tabInfo.updateNavigation("Example", "https://example.com")
        assertEquals(Icons.Outlined.Language, onPage.icon)

        val backHome = onPage.updateNavigation("", "about:blank")
        assertEquals(Icons.Outlined.Home, backHome.icon)
    }

    @Test
    fun `explicit tabIcon override wins over the home icon`() {
        val override = TabIcon.Vector(Icons.Outlined.Warning)
        val tabInfo = createTabInfo(url = "about:blank").updateTabIcon(override)
        assertEquals(override, tabInfo.tabIcon)
    }

    @Test
    fun `isHomeUrl matches blank and about-blank urls only`() {
        assertTrue(FluckTabInfo.isHomeUrl(""))
        assertTrue(FluckTabInfo.isHomeUrl("   "))
        assertTrue(FluckTabInfo.isHomeUrl("about:blank"))
        assertTrue(!FluckTabInfo.isHomeUrl("https://example.com"))
        assertTrue(!FluckTabInfo.isHomeUrl("about:blank#anchor"))
    }

    @Test
    fun `home landing transform sets Home title and clears the stale favicon`() {
        // The exact transform BossTabUpdateProvider.updateUrl applies when a
        // navigation lands on home: the previous page's title and favicon must
        // both be replaced by the home identity.
        val onPage =
            FluckTabInfo(
                id = "test-tab",
                typeId = TabTypeId("fluck"),
                _title = "Google",
                url = "https://google.com",
                faviconCacheKey = "stale-favicon-key",
            )

        val home =
            onPage
                .updateNavigation(FluckTabInfo.HOME_TITLE, "about:blank")
                .updateTitle(FluckTabInfo.HOME_TITLE)
                .updateFaviconCacheKey(null)

        assertEquals(FluckTabInfo.HOME_TITLE, home.title)
        assertEquals(null, home.faviconCacheKey)
        assertEquals("about:blank", home.currentUrl)
        assertEquals(Icons.Outlined.Home, home.icon)
        // The history entry records the home visit under its own title.
        assertEquals(Pair(FluckTabInfo.HOME_TITLE, "about:blank"), home.navigationHistory.last())
    }

    @Test
    fun `copy for split view preserves current URL as initial URL`() {
        // Create tab and navigate away from initial URL
        val original =
            FluckTabInfo(
                id = "original-tab",
                typeId = TabTypeId("fluck"),
                _title = "Original",
                url = "https://initial.com",
            ).updateNavigation("Current Page", "https://current.com")

        // Simulate split view copy with new ID and current URL
        val splitCopy =
            original.copy(
                id = "split-123",
                url = original.currentUrl, // Should be current URL
                _currentUrl = original.currentUrl,
                navigationHistory = original.navigationHistory.toMutableList(),
            )

        // Split copy should have current URL as both url and _currentUrl
        assertEquals("https://current.com", splitCopy.url)
        assertEquals("https://current.com", splitCopy.currentUrl)

        // Original should be unchanged
        assertEquals("https://initial.com", original.url)
        assertEquals("https://current.com", original.currentUrl)
    }

    // ==================== ZOOM LEVEL AND FAVICON PERSISTENCE TESTS ====================

    @Test
    fun `default zoom level is 1_0`() {
        val tabInfo = createTabInfo(url = "https://example.com")
        assertEquals(1.0, tabInfo.currentZoomLevel)
    }

    @Test
    fun `updateZoomLevel updates currentZoomLevel and preserves state`() {
        val tabInfo = createTabInfo(url = "https://example.com").updateFaviconCacheKey("fav-123")
        val zoomed = tabInfo.updateZoomLevel(1.5)

        assertEquals(1.5, zoomed.currentZoomLevel)
        assertEquals("fav-123", zoomed.faviconCacheKey)
        assertEquals("https://example.com", zoomed.currentUrl)
    }

    @Test
    fun `copy preserves custom zoom level and favicon cache key`() {
        val original =
            createTabInfo(url = "https://example.com")
                .updateZoomLevel(1.25)
                .updateFaviconCacheKey("fav-456")

        val copied = original.updateTitle("New Title")

        assertEquals(1.25, copied.currentZoomLevel)
        assertEquals("fav-456", copied.faviconCacheKey)
        assertEquals("New Title", copied.title)
    }

    @Test
    fun `updateNavigation preserves custom zoom level and favicon cache key`() {
        val tabInfo =
            createTabInfo(url = "https://example.com")
                .updateZoomLevel(1.75)
                .updateFaviconCacheKey("fav-789")

        val navigated = tabInfo.updateNavigation("New Page", "https://example.com/next")

        assertEquals(1.75, navigated.currentZoomLevel)
        assertEquals("fav-789", navigated.faviconCacheKey)
        assertEquals("https://example.com/next", navigated.currentUrl)
    }

    @Test
    fun `split view copy retains original zoom level and favicon cache key`() {
        val original =
            createTabInfo(url = "https://example.com")
                .updateZoomLevel(2.0)
                .updateFaviconCacheKey("fav-split")

        val splitCopy =
            original.copy(
                id = "split-456",
                url = original.currentUrl,
                _currentUrl = original.currentUrl,
                navigationHistory = original.navigationHistory.toMutableList(),
            )

        assertEquals(2.0, splitCopy.currentZoomLevel)
        assertEquals("fav-split", splitCopy.faviconCacheKey)
        assertEquals("split-456", splitCopy.id)
    }

    @Test
    fun `equals differentiates tabs with different zoom levels`() {
        val tab1 = createTabInfo(url = "https://example.com").updateZoomLevel(1.0)
        val tab2 = createTabInfo(url = "https://example.com").updateZoomLevel(1.5)

        assertTrue(tab1 != tab2)
    }
}
