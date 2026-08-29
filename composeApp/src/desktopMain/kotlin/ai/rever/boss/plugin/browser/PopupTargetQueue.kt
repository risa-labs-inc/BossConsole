package ai.rever.boss.plugin.browser

import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Pairs the target URL Chromium states at `CreatePopupCallback` with the `OpenPopupCallback` for
 * the same popup. It is the fallback destination for a popup whose navigation never names one.
 *
 * Chromium creates and opens a popup in order, so a FIFO pairs them - but only while both sides
 * push and pop exactly once per popup. [record] therefore keeps **every** create, including one
 * with no usable target: skipping those would shift the queue by one and hand the next popup a
 * URL meant for a previous link, which is the same wrong-destination bug this whole change
 * exists to remove. Unusable entries are filtered at [claim] instead, where dropping one costs
 * only a fallback.
 *
 * A create with no matching open still desyncs the queue - a popup whose navigation resolves to
 * a download is destroyed before it is ever shown - so entries expire quickly. The window only
 * has to cover the microseconds between create and open.
 */
internal class PopupTargetQueue(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private data class Entry(
        val recordedAtMs: Long,
        val url: String,
    )

    private val entries = ConcurrentLinkedDeque<Entry>()

    /** Records where a popup was told to go, before any browser exists to ask. */
    fun record(
        url: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        dropStale(nowMs)
        // Bounded as well as aged: a page opening popups faster than they are claimed must not
        // grow this without limit.
        while (entries.size >= maxEntries) {
            entries.pollFirst() ?: break
        }
        entries.addLast(Entry(nowMs, url))
    }

    /** Takes the oldest unclaimed target, or null when there is none worth using. */
    fun claim(nowMs: Long = System.currentTimeMillis()): String? {
        dropStale(nowMs)
        return usablePopupUrl(entries.pollFirst()?.url)
    }

    /** How many creates are still waiting for an open. Non-zero after a claim means a desync. */
    fun pending(): Int = entries.size

    private fun dropStale(nowMs: Long) {
        while (true) {
            val head = entries.peekFirst() ?: return
            if (nowMs - head.recordedAtMs <= ttlMs) return
            entries.pollFirst()
        }
    }

    companion object {
        /**
         * Create and open are microseconds apart in Chromium, so this only has to cover the
         * round trip out to the Java callbacks. Short on purpose: every millisecond here is
         * time in which an orphaned create can mispair with the next popup.
         */
        const val DEFAULT_TTL_MS = 2_000L

        const val DEFAULT_MAX_ENTRIES = 16
    }
}
