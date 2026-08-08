package ai.rever.boss.plugin.browser

import ai.rever.boss.plugin.api.BrowserEventType
import ai.rever.boss.plugin.api.BrowserNavigationType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Engagement accounting in [BrowserVisitTracker].
 *
 * The numbers here are the ones product decisions get made on, and every one of them is a
 * difference of two clock readings — so these tests drive a fake clock rather than sleeping,
 * and assert the arithmetic exactly.
 */
class BrowserVisitTrackerTest {
    private class Recorder {
        val pageViews = mutableListOf<Triple<String, BrowserNavigationType?, Int>>()
        val pageLefts = mutableListOf<Triple<String, Long, Long>>()
        val tabEvents = mutableListOf<Pair<BrowserEventType, String?>>()
    }

    private var clock = 0L
    private val recorder = Recorder()

    private fun tracker() =
        BrowserVisitTracker(
            windowId = "w1",
            nowMs = { clock },
            emitPageViewed = { authority, type, index, _ ->
                recorder.pageViews += Triple(authority, type, index)
            },
            emitPageLeft = { authority, dwell, active, _ ->
                recorder.pageLefts += Triple(authority, dwell, active)
            },
            emitTabEvent = { type, authority, _ -> recorder.tabEvents += type to authority },
        )

    @Test
    fun `dwell is wall clock and active time excludes the unfocused stretch`() {
        val t = tracker()
        t.setFocused(true)
        t.pageViewed("availity.com")

        clock = 5_000
        t.setFocused(false) // user switches to another tab for a minute
        clock = 65_000
        t.setFocused(true)
        clock = 70_000
        t.closed()

        val (authority, dwell, active) = recorder.pageLefts.single()
        assertEquals("availity.com", authority)
        assertEquals(70_000, dwell, "dwell is wall-clock from page load to close")
        assertEquals(10_000, active, "active excludes the 60s the tab was in the background")
    }

    @Test
    fun `a page loaded in a background tab accrues no active time`() {
        // The case that makes averaging dwell alone misleading: cmd-click opens a tab that
        // is never looked at. It must not report a minute of engagement.
        val t = tracker()
        t.pageViewed("availity.com")
        clock = 60_000
        t.closed()

        val (_, dwell, active) = recorder.pageLefts.single()
        assertEquals(60_000, dwell)
        assertEquals(0, active)
    }

    @Test
    fun `navigation depth counts a run on one site and resets on leaving it`() {
        val t = tracker()
        t.pageViewed("availity.com")
        t.pageViewed("portal.availity.com") // same registrable domain — deeper
        t.pageViewed("apps.availity.com")
        t.pageViewed("bbc.co.uk") // different site — new run
        t.pageViewed("news.bbc.co.uk")

        assertEquals(listOf(1, 2, 3, 1, 2), recorder.pageViews.map { it.third })
    }

    @Test
    fun `an unreportable host breaks the run instead of extending it`() {
        // A detour through a dev server is not "one page deeper into availity".
        val t = tracker()
        t.pageViewed("availity.com")
        t.pageViewed("localhost:3000")
        t.pageViewed("availity.com")

        assertEquals(listOf(1, 1), recorder.pageViews.map { it.third })
        assertTrue(recorder.pageViews.none { it.first == "localhost:3000" })
    }

    @Test
    fun `each navigation closes out the previous page exactly once`() {
        val t = tracker()
        t.setFocused(true)
        t.pageViewed("availity.com")
        clock = 3_000
        t.pageViewed("bbc.co.uk")
        clock = 8_000
        t.closed()

        assertEquals(2, recorder.pageLefts.size)
        assertEquals(Triple("availity.com", 3_000L, 3_000L), recorder.pageLefts[0])
        assertEquals(Triple("bbc.co.uk", 5_000L, 5_000L), recorder.pageLefts[1])
    }

    @Test
    fun `an explicit host navigation is attributed to how it was triggered`() {
        val t = tracker()
        t.expect(BrowserNavigationType.TYPED)
        t.pageViewed("availity.com")
        t.expect(BrowserNavigationType.RELOAD)
        t.pageViewed("availity.com")
        t.expect(BrowserNavigationType.BACK_FORWARD)
        t.pageViewed("availity.com")

        assertEquals(
            listOf(
                BrowserNavigationType.TYPED,
                BrowserNavigationType.RELOAD,
                BrowserNavigationType.BACK_FORWARD,
            ),
            recorder.pageViews.map { it.second },
        )
    }

    @Test
    fun `a navigation nobody asked for is a link, and the hint is single use`() {
        val t = tracker()
        t.expect(BrowserNavigationType.TYPED)
        t.pageViewed("availity.com")
        t.pageViewed("availity.com") // clicked through from the page

        assertEquals(
            listOf(BrowserNavigationType.TYPED, BrowserNavigationType.LINK),
            recorder.pageViews.map { it.second },
        )
    }

    @Test
    fun `closing twice reports one close and one page left`() {
        // dispose() can be reached more than once; double-counting a visit would inflate
        // both the page-view count and the total time on site.
        val t = tracker()
        t.pageViewed("availity.com")
        clock = 1_000
        t.closed()
        t.closed()
        t.pageViewed("bbc.co.uk")

        assertEquals(1, recorder.pageLefts.size)
        assertEquals(1, recorder.tabEvents.count { it.first == BrowserEventType.TAB_CLOSED })
        assertTrue(recorder.pageViews.none { it.first == "bbc.co.uk" }, "no tracking after close")
    }

    @Test
    fun `tab lifecycle reports open, each activation, and close`() {
        val t = tracker()
        t.opened("availity.com")
        t.pageViewed("availity.com")
        t.setFocused(true)
        t.setFocused(true) // already focused — not a switch
        t.setFocused(false)
        t.setFocused(true)
        t.closed()

        assertEquals(
            listOf(
                BrowserEventType.TAB_OPENED,
                BrowserEventType.TAB_ACTIVATED,
                BrowserEventType.TAB_ACTIVATED,
                BrowserEventType.TAB_CLOSED,
            ),
            recorder.tabEvents.map { it.first },
        )
    }

    @Test
    fun `a clock that jumps backwards cannot produce negative time`() {
        // Resume-from-sleep and NTP corrections both do this. A negative dwell would be
        // dropped downstream, but a negative *active* accumulation would silently corrupt
        // the running total for the rest of the visit.
        val t = tracker()
        t.setFocused(true)
        t.pageViewed("availity.com")
        clock = -60_000
        t.closed()

        val (_, dwell, active) = recorder.pageLefts.single()
        assertTrue(dwell >= 0, "dwell was $dwell")
        assertTrue(active >= 0, "active was $active")
    }
}
