package ai.rever.boss.tabs

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecentlyClosedTabsBufferTest {
    @BeforeTest
    fun setUp() {
        RecentlyClosedTabsBuffer.clear()
    }

    @Test
    fun `recordClosedTab adds tab to top of history stack`() {
        RecentlyClosedTabsBuffer.recordClosedTab(
            id = "tab-1",
            title = "GitHub",
            typeId = "fluck-tab",
            url = "https://github.com",
        )

        val history = RecentlyClosedTabsBuffer.getHistory()
        assertEquals(1, history.size)
        assertEquals("GitHub", history[0].title)
        assertEquals("https://github.com", history[0].url)
    }

    @Test
    fun `popMostRecent retrieves tabs in LIFO order`() {
        RecentlyClosedTabsBuffer.recordClosedTab("t1", "Tab 1", "fluck-tab")
        RecentlyClosedTabsBuffer.recordClosedTab("t2", "Tab 2", "fluck-tab")

        val popped = RecentlyClosedTabsBuffer.popMostRecent()
        assertEquals("Tab 2", popped?.title)

        val remaining = RecentlyClosedTabsBuffer.getHistory()
        assertEquals(1, remaining.size)
        assertEquals("Tab 1", remaining[0].title)
    }

    @Test
    fun `buffer enforces max capacity limit of 10 items`() {
        for (i in 1..15) {
            RecentlyClosedTabsBuffer.recordClosedTab("t$i", "Tab $i", "fluck-tab")
        }

        val history = RecentlyClosedTabsBuffer.getHistory()
        assertEquals(10, history.size)
        assertEquals("Tab 15", history[0].title)
        assertEquals("Tab 6", history[9].title)
    }

    @Test
    fun `popMostRecent on empty buffer returns null`() {
        assertNull(RecentlyClosedTabsBuffer.popMostRecent())
    }

    @Test
    fun `clear empties the buffer`() {
        RecentlyClosedTabsBuffer.recordClosedTab("t1", "Tab 1", "fluck-tab")
        RecentlyClosedTabsBuffer.clear()

        assertEquals(0, RecentlyClosedTabsBuffer.getHistory().size)
    }
}
