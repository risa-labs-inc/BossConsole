package ai.rever.boss.dashboard

/** Tracks dismissals only while disk loads are in flight; no filesystem work holds this lock. */
internal class RecentPagesLoadGuard {
    internal class Ticket {
        var cleared = false
        val removals = mutableListOf<(RecentBrowserPage) -> Boolean>()
    }

    private val lock = Any()
    private val active = mutableSetOf<Ticket>()

    fun begin(): Ticket = synchronized(lock) { Ticket().also { active.add(it) } }

    fun end(ticket: Ticket) = synchronized(lock) { active.remove(ticket) }

    fun remove(
        predicate: (RecentBrowserPage) -> Boolean,
        mutation: () -> Unit,
    ) = synchronized(lock) {
        active.filterNot { it.cleared }.forEach { it.removals.add(predicate) }
        mutation()
    }

    fun clear(mutation: () -> Unit) =
        synchronized(lock) {
            active.forEach {
                it.cleared = true
                it.removals.clear()
            }
            mutation()
        }

    fun <T> publish(
        ticket: Ticket,
        loaded: List<RecentBrowserPage>,
        merge: (List<RecentBrowserPage>) -> T,
    ): T =
        synchronized(lock) {
            val surviving =
                if (ticket.cleared) emptyList() else loaded.filterNot { page -> ticket.removals.any { it(page) } }
            merge(surviving)
        }
}
