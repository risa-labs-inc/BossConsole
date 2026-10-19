package ai.rever.boss.plugin.browser

/**
 * Bounds automatic popup admission. This is deliberately not a tab ownership tracker:
 * timestamps cannot tell which panel/window/tab initiated a download, and must never
 * authorize closing tabs. Only elapsed time releases the admission budget.
 */
internal class PopupTabTracker(
    private val trackWindowMs: Long = 5_000L,
    private val maxOpenTabs: Int = 8,
) {
    private val openedAtMs = ArrayDeque<Long>()

    @Synchronized
    fun tryRecordOpened(nowMs: Long = System.currentTimeMillis()): Boolean {
        val cutoff = nowMs - trackWindowMs
        while (openedAtMs.isNotEmpty() && openedAtMs.first() < cutoff) {
            openedAtMs.removeFirst()
        }
        if (openedAtMs.size >= maxOpenTabs) return false
        openedAtMs.addLast(nowMs)
        return true
    }
}
