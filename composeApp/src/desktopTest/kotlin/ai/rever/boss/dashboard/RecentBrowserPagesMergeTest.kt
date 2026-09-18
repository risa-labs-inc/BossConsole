package ai.rever.boss.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The startup load **merges** with what is already recorded, it does not replace it.
 *
 * `init` launches `loadAsync`, and `recordPageVisit` can land while that read is in flight - which
 * is exactly why that method uses `update {}`. The load used to assign the decoded list straight
 * into the flow, so the page the user had just visited was dropped from the list *and* from the
 * save scheduled for it.
 *
 * `visitCount` is what makes this merge different from [mergeRecorded]: it accumulates, so taking
 * the newer entry wholesale would reset a long-lived page's count to the single racing visit.
 */
class RecentBrowserPagesMergeTest {
    private fun page(
        url: String,
        lastVisited: Long,
        visits: Int = 1,
        favicon: String? = null,
        title: String = url,
    ) = RecentBrowserPage(
        url = url,
        title = title,
        lastVisited = lastVisited,
        faviconCacheKey = favicon,
        visitCount = visits,
    )

    private val onDisk = page("https://a.dev", lastVisited = 100, visits = 7, favicon = "favA", title = "A")

    @Test
    fun `a visit recorded while the load was in flight survives the load`() {
        val inFlight = page("https://b.dev", lastVisited = 900)

        val merged = mergeRecordedPages(loaded = listOf(onDisk), recorded = listOf(inFlight), max = 30)

        assertTrue(inFlight in merged, "the in-flight visit must not be discarded by the load")
        assertTrue(onDisk in merged, "the persisted history must not be discarded either")
    }

    @Test
    fun `the decoded list is kept when nothing was recorded yet`() {
        // The ordinary launch: the load wins the race, so there is nothing to merge with and the
        // caller must be able to tell the merge changed nothing.
        val loaded = listOf(page("https://c.dev", 300), onDisk)

        assertEquals(loaded, mergeRecordedPages(loaded = loaded, recorded = emptyList(), max = 30))
    }

    @Test
    fun `a url on both sides keeps its accumulated visit count`() {
        // The racing entry is created fresh with visitCount 1 because the list was empty, so the
        // seven visits already on disk only survive if the counts are summed.
        val racingVisit = page("https://a.dev", lastVisited = 900, visits = 1, title = "A, retitled")

        val merged = mergeRecordedPages(loaded = listOf(onDisk), recorded = listOf(racingVisit), max = 30)

        assertEquals(1, merged.size, "the url must collapse to a single entry")
        assertEquals(8, merged.single().visitCount)
        assertEquals("A, retitled", merged.single().title, "the newer visit supplies the title")
        assertEquals(900, merged.single().lastVisited)
    }

    @Test
    fun `an in-flight visit wins the title tie against the stored entry`() {
        val stored = page("https://tie.dev", lastVisited = 900, title = "Stored title")
        val inFlight = page("https://tie.dev", lastVisited = 900, title = "Fresh title")

        val merged = mergeRecordedPages(loaded = listOf(stored), recorded = listOf(inFlight), max = 30)

        assertEquals("Fresh title", merged.single().title)
        assertEquals(2, merged.single().visitCount)
    }

    @Test
    fun `a favicon the racing visit lacked is taken from the stored entry`() {
        // recordPageVisit passes faviconCacheKey = null until the icon arrives, so without the
        // fallback a page would visibly lose its icon on every launch that races a visit.
        val racingVisit = page("https://a.dev", lastVisited = 900, favicon = null)

        assertEquals("favA", mergeRecordedPages(listOf(onDisk), listOf(racingVisit), 30).single().faviconCacheKey)
    }

    @Test
    fun `the result is ordered newest first`() {
        val oldest = page("https://oldest.dev", 1)
        val middle = page("https://middle.dev", 2)
        val newest = page("https://newest.dev", 3)

        val merged = mergeRecordedPages(loaded = listOf(oldest, newest), recorded = listOf(middle), max = 30)

        assertEquals(listOf(newest, middle, oldest), merged)
    }

    @Test
    fun `the cap is applied after merging, dropping the oldest`() {
        // Applying it per-side would let a full stored list crowd out a fresher in-flight visit.
        val loaded = (1..30).map { page("https://d$it.dev", lastVisited = it.toLong()) }
        val justVisited = page("https://new.dev", lastVisited = 9999)

        val merged = mergeRecordedPages(loaded = loaded, recorded = listOf(justVisited), max = 30)

        assertEquals(30, merged.size)
        assertEquals(justVisited, merged.first())
        assertTrue(merged.none { it.url == "https://d1.dev" }, "the oldest entry is the one dropped")
    }

    @Test
    fun `merging two empty lists is empty, not a crash`() {
        assertTrue(mergeRecordedPages(loaded = emptyList(), recorded = emptyList(), max = 30).isEmpty())
    }

    @Test
    fun `a dismissal recorded while the load was in flight survives the load`() {
        val merged =
            mergeDismissedSuggestions(
                loaded = setOf("stored", "obsolete"),
                recorded = setOf("in-flight"),
                allowed = setOf("stored", "in-flight"),
            )

        assertEquals(setOf("stored", "in-flight"), merged)
    }
}
