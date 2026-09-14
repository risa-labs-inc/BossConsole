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

        /**
         * The window id on every emission, in order, so nothing can quietly stop attributing
         * events to a window and so a move can be checked as a change rather than a set.
         */
        val windowIds = mutableListOf<String?>()

        /** URLs handed to the page-view emitter, so route plumbing is covered here too. */
        val urls = mutableListOf<String?>()
    }

    private var clock = 0L

    /** Mutable, because a tab moves between windows and the id is resolved per emission. */
    private var currentWindowId: String? = "w1"
    private val recorder = Recorder()

    private fun tracker() =
        BrowserVisitTracker(
            windowId = { currentWindowId },
            nowMs = { clock },
            emitPageViewed = { authority, type, index, windowId, url ->
                recorder.pageViews += Triple(authority, type, index)
                recorder.windowIds += windowId
                recorder.urls += url
            },
            emitPageLeft = { authority, dwell, active, windowId ->
                recorder.pageLefts += Triple(authority, dwell, active)
                recorder.windowIds += windowId
            },
            emitTabEvent = { type, authority, windowId ->
                recorder.tabEvents += type to authority
                recorder.windowIds += windowId
            },
        )

    @Test
    fun `dwell is wall clock and active time excludes the unfocused stretch`() {
        val t = tracker()
        t.setVisible(true)
        t.pageViewed("acmecorp.com")

        clock = 5_000
        t.setVisible(false) // user switches to another tab for a minute
        clock = 65_000
        t.setVisible(true)
        clock = 70_000
        t.closed()

        val (authority, dwell, active) = recorder.pageLefts.single()
        assertEquals("acmecorp.com", authority)
        assertEquals(70_000, dwell, "dwell is wall-clock from page load to close")
        assertEquals(10_000, active, "active excludes the 60s the tab was in the background")
    }

    @Test
    fun `a page loaded in a background tab accrues no active time`() {
        // The case that makes averaging dwell alone misleading: cmd-click opens a tab that
        // is never looked at. It must not report a minute of engagement.
        val t = tracker()
        t.pageViewed("acmecorp.com")
        clock = 60_000
        t.closed()

        val (_, dwell, active) = recorder.pageLefts.single()
        assertEquals(60_000, dwell)
        assertEquals(0, active)
    }

    @Test
    fun `navigation depth counts a run on one site and resets on leaving it`() {
        val t = tracker()
        t.pageViewed("acmecorp.com")
        t.pageViewed("portal.acmecorp.com") // same registrable domain — deeper
        t.pageViewed("apps.acmecorp.com")
        t.pageViewed("bbc.co.uk") // different site — new run
        t.pageViewed("news.bbc.co.uk")

        assertEquals(listOf(1, 2, 3, 1, 2), recorder.pageViews.map { it.third })
    }

    @Test
    fun `an unreportable host breaks the run instead of extending it`() {
        // A detour through a dev server is not "one page deeper into acmecorp".
        val t = tracker()
        t.pageViewed("acmecorp.com")
        t.pageViewed("localhost:3000")
        t.pageViewed("acmecorp.com")

        assertEquals(listOf(1, 1), recorder.pageViews.map { it.third })
        assertTrue(recorder.pageViews.none { it.first == "localhost:3000" })
    }

    @Test
    fun `an error page closes the visit it interrupted instead of extending it`() {
        // A mistyped host commits an error page. While that never reached the tracker, the
        // previous visit stayed open: its dwell and active time kept accruing for as long as
        // the user sat on the error page and were then billed to the previous domain.
        val t = tracker()
        t.setVisible(true)
        t.pageViewed("acmecorp.com")
        clock = 5_000
        t.leftTrackablePage("youtube.como") // navigation landed on an error page
        clock = 605_000 // ten minutes staring at it

        val (authority, dwell, active) = recorder.pageLefts.single()
        assertEquals("acmecorp.com", authority)
        assertEquals(5_000, dwell, "the ten minutes on the error page are not acmecorp's")
        assertEquals(5_000, active)
    }

    @Test
    fun `an error page breaks the run and consumes the hint that produced it`() {
        val t = tracker()
        t.pageViewed("acmecorp.com")
        t.expect(BrowserNavigationType.TYPED)
        t.leftTrackablePage("youtube.como")
        t.pageViewed("acmecorp.com") // clicked back through from somewhere

        // Depth restarts rather than counting the error page as one hop deeper...
        assertEquals(listOf(1, 1), recorder.pageViews.map { it.third })
        // ...and the TYPED hint died with the navigation that failed, rather than relabelling
        // this one.
        assertEquals(
            listOf(BrowserNavigationType.LINK, BrowserNavigationType.LINK),
            recorder.pageViews.map { it.second },
        )
    }

    @Test
    fun `a tab closed on an error page still says where it was`() {
        val t = tracker()
        t.pageViewed("acmecorp.com")
        t.leftTrackablePage("portal.acmecorp.com")
        t.closed()

        assertEquals(
            "portal.acmecorp.com",
            recorder.tabEvents.single { it.first == BrowserEventType.TAB_CLOSED }.second,
        )
    }

    @Test
    fun `each navigation closes out the previous page exactly once`() {
        val t = tracker()
        t.setVisible(true)
        t.pageViewed("acmecorp.com")
        clock = 3_000
        t.pageViewed("bbc.co.uk")
        clock = 8_000
        t.closed()

        assertEquals(2, recorder.pageLefts.size)
        assertEquals(Triple("acmecorp.com", 3_000L, 3_000L), recorder.pageLefts[0])
        assertEquals(Triple("bbc.co.uk", 5_000L, 5_000L), recorder.pageLefts[1])
    }

    @Test
    fun `an explicit host navigation is attributed to how it was triggered`() {
        val t = tracker()
        t.expect(BrowserNavigationType.TYPED)
        t.pageViewed("acmecorp.com")
        t.expect(BrowserNavigationType.RELOAD)
        t.pageViewed("acmecorp.com")
        t.expect(BrowserNavigationType.BACK_FORWARD)
        t.pageViewed("acmecorp.com")

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
        t.pageViewed("acmecorp.com")
        t.pageViewed("acmecorp.com") // clicked through from the page

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
        t.pageViewed("acmecorp.com")
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
        t.opened("acmecorp.com")
        t.pageViewed("acmecorp.com")
        t.setVisible(true)
        t.setVisible(false) // fully hidden
        clock = 30_000 // away long enough to be a real switch, not a tab move
        t.setVisible(true)
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
    fun `a tab move in either order leaves the tab active exactly once`() {
        // The visibility signal is one DisposableEffect per composition, and a cross-window
        // move builds one and tears down the other in an order this code does not control.
        // As a plain boolean the compose-then-dispose order was destructive: the enter
        // no-opped because the tab was already active, the leave then cleared it, and a tab
        // that was visible and focused accrued NO active time while dwell kept climbing -
        // reading as "left open, never read", the exact thing this class exists to tell apart.
        for (composeFirst in listOf(true, false)) {
            clock = 0
            recorder.tabEvents.clear()
            recorder.pageLefts.clear()
            val t = tracker()
            t.setVisible(true) // window A shows the tab
            t.pageViewed("acmecorp.com")

            clock = 10_000
            if (composeFirst) {
                t.setVisible(true) // window B composes
                t.setVisible(false) // window A disposes
            } else {
                t.setVisible(false) // window A disposes
                t.setVisible(true) // window B composes
            }

            clock = 20_000
            t.closed()

            val (_, dwell, active) = recorder.pageLefts.single()
            assertEquals(20_000, dwell, "composeFirst=$composeFirst")
            assertEquals(
                20_000,
                active,
                "a moved tab is still being looked at; composeFirst=$composeFirst",
            )
            assertEquals(
                1,
                recorder.tabEvents.count { it.first == BrowserEventType.TAB_ACTIVATED },
                "a move is not a tab switch; composeFirst=$composeFirst",
            )
        }
    }

    @Test
    fun `going away and coming back later is still a real tab switch`() {
        // The grace window that absorbs a move must not swallow the user actually leaving
        // the tab and returning, which is the signal TAB_ACTIVATED exists for.
        val t = tracker()
        t.pageViewed("acmecorp.com")
        t.setVisible(true)
        t.setVisible(false)
        clock = 30_000
        t.setVisible(true)

        assertEquals(2, recorder.tabEvents.count { it.first == BrowserEventType.TAB_ACTIVATED })
    }

    @Test
    fun `the first activation says where the tab is, not that it is blank`() {
        // opened() and the initial load both run in init; the visibility effect fires at
        // first composition - all before any NavigationFinished. Reading only the current
        // page's authority therefore reported nearly every tab's first TAB_ACTIVATED as a
        // blank tab, which is not joinable with anything.
        val t = tracker()
        t.opened("portal.acmecorp.com")
        t.setVisible(true)

        assertEquals(
            listOf<String?>("portal.acmecorp.com", "portal.acmecorp.com"),
            recorder.tabEvents.map { it.second },
        )
    }

    @Test
    fun `every emission carries the owning window`() {
        // Dwell, depth and tab counts are all per-window in a multi-window setup; an event
        // that arrives without one cannot be attributed at all, and nothing else notices.
        val t = tracker()
        t.opened("acmecorp.com")
        t.setVisible(true)
        t.pageViewed("acmecorp.com")
        clock = 1_000
        t.closed()

        assertTrue(recorder.windowIds.isNotEmpty())
        assertEquals(setOf<String?>("w1"), recorder.windowIds.toSet())
    }

    @Test
    fun `a tab moved to another window is attributed to the window it is in now`() {
        // The id used to be captured at construction, so a moved tab reported its dwell,
        // depth and close against the window it left - and nothing downstream could tell.
        val t = tracker()
        t.opened("acmecorp.com")
        t.pageViewed("acmecorp.com")
        val beforeMove = recorder.windowIds.toList()

        currentWindowId = "w2"
        clock = 1_000
        t.pageViewed("bbc.co.uk") // closes the first visit and opens a second
        t.closed()
        val afterMove = recorder.windowIds.drop(beforeMove.size)

        assertEquals(setOf<String?>("w1"), beforeMove.toSet(), "before the move")
        assertTrue(afterMove.isNotEmpty())
        assertEquals(setOf<String?>("w2"), afterMove.toSet(), "after the move")
    }

    @Test
    fun `a tab closed on an unreportable host is not reported as an empty tab`() {
        // lastDomain is deliberately null for a dev server so the depth run breaks, but
        // reporting the CLOSE with it collapsed "was on localhost" into "never loaded
        // anything" - the same conflation the open side goes out of its way to avoid.
        val t = tracker()
        t.opened("localhost:3000")
        t.pageViewed("localhost:3000")
        t.closed()

        assertEquals(
            listOf<String?>("localhost:3000", "localhost:3000"),
            recorder.tabEvents.filter { it.first != BrowserEventType.TAB_ACTIVATED }.map { it.second },
        )
    }

    @Test
    fun `a hint nobody consumed expires instead of relabelling a later click`() {
        // A navigation can be announced and never land - a blocked scheme, a stop(), a
        // download. The hint would otherwise sit there until something navigated, and the
        // user's next link click would be reported as TYPED.
        val t = tracker()
        t.expect(BrowserNavigationType.TYPED)
        clock = 61_000
        t.pageViewed("acmecorp.com")

        assertEquals(BrowserNavigationType.LINK, recorder.pageViews.single().second)
    }

    @Test
    fun `a hint is still honoured across a slow but real navigation`() {
        val t = tracker()
        t.expect(BrowserNavigationType.TYPED)
        clock = 30_000
        t.pageViewed("acmecorp.com")

        assertEquals(BrowserNavigationType.TYPED, recorder.pageViews.single().second)
    }

    @Test
    fun `a clock that jumps backwards cannot produce negative time`() {
        // Resume-from-sleep and NTP corrections both do this. A negative dwell would be
        // dropped downstream, but a negative *active* accumulation would silently corrupt
        // the running total for the rest of the visit.
        val t = tracker()
        t.setVisible(true)
        t.pageViewed("acmecorp.com")
        clock = -60_000
        t.closed()

        val (_, dwell, active) = recorder.pageLefts.single()
        assertTrue(dwell >= 0, "dwell was $dwell")
        assertTrue(active >= 0, "active was $active")
    }
}
