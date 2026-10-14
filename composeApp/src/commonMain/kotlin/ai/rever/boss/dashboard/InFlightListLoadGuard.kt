package ai.rever.boss.dashboard

/**
 * Preserves user removals while a list is being loaded outside this guard's lock.
 *
 * A load registers a [Ticket] before it starts I/O. Removals and clears are then copied onto every
 * active ticket while their in-memory mutation runs under the same short lock. Publishing filters
 * the stale decoded snapshot before handing it to [merge], so a disk read that started earlier
 * cannot put an item the user just removed back into memory.
 *
 * The lock never covers file I/O. [merge] must only perform the short in-memory publication that
 * has to be ordered against [remove] and [clear].
 */
internal class InFlightListLoadGuard<E> {
    internal class Ticket<E> {
        var cleared = false
        val removals = mutableListOf<(E) -> Boolean>()
    }

    private val lock = Any()
    private val active = mutableSetOf<Ticket<E>>()

    fun begin(): Ticket<E> = synchronized(lock) { Ticket<E>().also { active.add(it) } }

    /** Idempotent so a launch-completion hook and the load's `finally` can both retire a ticket. */
    fun end(ticket: Ticket<E>) {
        synchronized(lock) { active.remove(ticket) }
    }

    /**
     * Record [predicate] and apply the matching state [mutation] in one critical section.
     *
     * [mutation] intentionally has no default: registering a predicate while mutating the real
     * state later would reopen the load-publication race this guard closes.
     */
    fun remove(
        predicate: (E) -> Boolean,
        mutation: () -> Unit,
    ) = synchronized(lock) {
        active.filterNot { it.cleared }.forEach { it.removals.add(predicate) }
        mutation()
    }

    /** Record the clear and apply the required state [mutation] in one critical section. */
    fun clear(mutation: () -> Unit) =
        synchronized(lock) {
            active.forEach {
                it.cleared = true
                it.removals.clear()
            }
            mutation()
        }

    fun <R> publish(
        ticket: Ticket<E>,
        loaded: List<E>,
        merge: (List<E>) -> R,
    ): R =
        synchronized(lock) {
            val surviving =
                if (ticket.cleared) emptyList() else loaded.filterNot { item -> ticket.removals.any { it(item) } }
            merge(surviving)
        }
}
