package ai.rever.boss.plugin.browser

/**
 * Tracks tabs the popup handler just opened, covering the two burst-shaped failure modes a
 * `window.open` storm produces.
 *
 * First, the storm itself: every adopted popup becomes a real tab, so an unbounded burst is an
 * unbounded tab flood. [tryRecordOpened] refuses once [maxOpenTabs] tabs were opened inside
 * [trackWindowMs] - a refused popup's browser is already closed by the caller, so refusal costs
 * the page nothing it was promised.
 *
 * Second, the redirect: a popup whose navigation turns out to be a download opens a tab that has
 * to come straight back down when `StartDownloadCallback` fires. [drainRedirectTabs] reports how
 * many tabs were opened inside [closeWindowMs] and forgets them. A *count*, not a single close:
 * a burst opens several tabs before the first download lands, and closing only the newest one
 * used to leave the rest standing.
 *
 * One timestamp list serves both purposes, so a tab drained for a download stops counting
 * against the burst cap immediately - it is already on its way out.
 */
internal class PopupTabTracker(
    private val trackWindowMs: Long = DEFAULT_TRACK_WINDOW_MS,
    private val closeWindowMs: Long = DEFAULT_CLOSE_WINDOW_MS,
    private val maxOpenTabs: Int = DEFAULT_MAX_OPEN_TABS,
) {
    private val openedAtMs = ArrayDeque<Long>()

    /**
     * Records a tab the popup handler is about to open. Returns false - meaning do NOT open it -
     * once [maxOpenTabs] tabs were already opened inside the tracking window. A refused tab is
     * not recorded: nothing was opened, so nothing is owed a close.
     */
    @Synchronized
    fun tryRecordOpened(nowMs: Long = System.currentTimeMillis()): Boolean {
        prune(nowMs)
        if (openedAtMs.size >= maxOpenTabs) return false
        openedAtMs.addLast(nowMs)
        return true
    }

    /**
     * How many just-opened tabs a starting download should take back down. The drained entries
     * are forgotten, so the next download only counts tabs newer than itself and a quiet period
     * can never resurrect a stale close.
     */
    @Synchronized
    fun drainRedirectTabs(nowMs: Long = System.currentTimeMillis()): Int {
        prune(nowMs)
        val cutoff = nowMs - closeWindowMs
        val count = openedAtMs.count { it >= cutoff }
        openedAtMs.removeIf { it >= cutoff }
        return count
    }

    /** Entries currently inside the tracking window. Exposed for tests. */
    @Synchronized
    fun pendingCount(nowMs: Long = System.currentTimeMillis()): Int {
        prune(nowMs)
        return openedAtMs.size
    }

    private fun prune(nowMs: Long) {
        val cutoff = nowMs - trackWindowMs
        while (openedAtMs.isNotEmpty() && openedAtMs.first() < cutoff) {
            openedAtMs.removeFirst()
        }
    }

    companion object {
        /**
         * How long an opened tab counts toward the burst cap and stays eligible for
         * download-redirect cleanup. Matches the lifetime the old `recentlyOpenedTabIds` list
         * gave an entry.
         */
        const val DEFAULT_TRACK_WINDOW_MS = 5_000L

        /**
         * How new a tab must be for a starting download to take it back down. A download
         * redirect resolves within moments of the tab opening; three seconds keeps a tab the
         * user opened deliberately out of the sweep.
         */
        const val DEFAULT_CLOSE_WINDOW_MS = 3_000L

        /**
         * Most tabs a page may open through the popup handler inside one tracking window before
         * further ones are refused. Well above anything a real flow needs - OAuth plus a link or
         * two - and far below what a `window.open` storm produces.
         */
        const val DEFAULT_MAX_OPEN_TABS = 8
    }
}
