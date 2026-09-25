package ai.rever.boss.services

/**
 * Sliding-window bound on untrusted URL requests that require operator approval.
 *
 * External `boss://url` requests pass through [URLHandlerService.handleURL].
 * Without a bound, a loop of requests can fill the approval queue.
 * [tryAcquire] admits at most [MAX_OPENS] opens per [WINDOW_MS]; the rest are
 * dropped with a log line by the caller.
 *
 * Thread-safe because deep-link and CLI paths can call [URLHandlerService.handleURL]
 * from different threads.
 */
internal class UrlOpenRateLimiter(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val lock = Any()
    private val opens = ArrayDeque<Long>()

    /** Records an open and returns true, or returns false once [MAX_OPENS] opens sit inside the window. */
    fun tryAcquire(): Boolean =
        synchronized(lock) {
            val now = nowMs()
            // A supplied clock may jump backwards. Start a fresh window rather than
            // refusing every request until it catches up with old timestamps.
            if (opens.isNotEmpty() && now < opens.last()) opens.clear()
            while (opens.isNotEmpty() && now - opens.first() >= WINDOW_MS) {
                opens.removeFirst()
            }
            if (opens.size >= MAX_OPENS) {
                false
            } else {
                opens.addLast(now)
                true
            }
        }

    companion object {
        const val MAX_OPENS = 5
        const val WINDOW_MS = 5_000L
    }
}
