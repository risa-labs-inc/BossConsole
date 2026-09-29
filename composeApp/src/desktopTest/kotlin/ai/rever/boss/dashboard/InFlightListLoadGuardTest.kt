package ai.rever.boss.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InFlightListLoadGuardTest {
    private data class Entry(
        val id: String,
        val revision: Int = 0,
    )

    @Test
    fun `remove and clear cannot regain an optional mutation callback`() {
        val methodNames = InFlightListLoadGuard::class.java.declaredMethods.map { it.name }

        assertFalse("remove\$default" in methodNames)
        assertFalse("clear\$default" in methodNames)
    }

    @Test
    fun `an untouched load publishes every decoded item in order`() {
        val guard = InFlightListLoadGuard<Entry>()
        val ticket = guard.begin()
        val loaded = listOf(Entry("a"), Entry("b"))

        assertEquals(loaded, guard.publish(ticket, loaded) { it })
    }

    @Test
    fun `a removal filters the matching item from an active load`() {
        val guard = InFlightListLoadGuard<Entry>()
        val ticket = guard.begin()

        guard.remove({ it.id == "a" }) {}

        assertEquals(listOf(Entry("b")), guard.publish(ticket, listOf(Entry("a"), Entry("b"))) { it })
    }

    @Test
    fun `a removal predicate can match several stale spellings`() {
        val guard = InFlightListLoadGuard<Entry>()
        val ticket = guard.begin()

        guard.remove({ it.id.startsWith("gone-") }) {}

        val surviving =
            guard.publish(ticket, listOf(Entry("gone-a"), Entry("keep"), Entry("gone-b"))) { it }
        assertEquals(listOf(Entry("keep")), surviving)
    }

    @Test
    fun `a nonmatching removal preserves the decoded list`() {
        val guard = InFlightListLoadGuard<Entry>()
        val ticket = guard.begin()
        val loaded = listOf(Entry("a"), Entry("b"))

        guard.remove({ it.id == "elsewhere" }) {}

        assertEquals(loaded, guard.publish(ticket, loaded) { it })
    }

    @Test
    fun `several removals compose instead of replacing each other`() {
        val guard = InFlightListLoadGuard<Entry>()
        val ticket = guard.begin()

        guard.remove({ it.id == "a" }) {}
        guard.remove({ it.id == "c" }) {}

        val surviving =
            guard.publish(ticket, listOf(Entry("a"), Entry("b"), Entry("c"), Entry("d"))) { it }
        assertEquals(listOf(Entry("b"), Entry("d")), surviving)
    }

    @Test
    fun `clear suppresses the complete stale snapshot`() {
        val guard = InFlightListLoadGuard<Entry>()
        val ticket = guard.begin()

        guard.clear {}

        assertTrue(guard.publish(ticket, listOf(Entry("a"), Entry("b"))) { it }.isEmpty())
    }

    @Test
    fun `clear supersedes earlier predicates without evaluating them`() {
        val guard = InFlightListLoadGuard<Entry>()
        val ticket = guard.begin()
        var evaluated = false
        guard.remove({
            evaluated = true
            false
        }) {}

        guard.clear {}
        guard.publish(ticket, listOf(Entry("a"))) { it }

        assertFalse(evaluated, "a cleared ticket must not retain or evaluate obsolete predicates")
    }

    @Test
    fun `a removal after clear cannot weaken the clear`() {
        val guard = InFlightListLoadGuard<Entry>()
        val ticket = guard.begin()
        guard.clear {}

        guard.remove({ it.id == "a" }) {}

        assertTrue(guard.publish(ticket, listOf(Entry("a"), Entry("b"))) { it }.isEmpty())
    }

    @Test
    fun `the in-memory mutation runs before a later publication`() {
        val guard = InFlightListLoadGuard<Entry>()
        val ticket = guard.begin()
        val events = mutableListOf<String>()

        guard.remove({ it.id == "a" }) { events += "removed" }
        guard.publish(ticket, listOf(Entry("a"))) {
            events += "published"
            it
        }

        assertEquals(listOf("removed", "published"), events)
    }

    @Test
    fun `remove then re-add keeps the new in-memory revision`() {
        val guard = InFlightListLoadGuard<Entry>()
        val ticket = guard.begin()
        var recorded = listOf(Entry("a", revision = 1))

        guard.remove({ it.id == "a" }) { recorded = emptyList() }
        recorded = listOf(Entry("a", revision = 2))

        val merged =
            guard.publish(ticket, listOf(Entry("a", revision = 1))) { loaded -> recorded + loaded }
        assertEquals(listOf(Entry("a", revision = 2)), merged)
    }

    @Test
    fun `clear then add keeps the new in-memory entry`() {
        val guard = InFlightListLoadGuard<Entry>()
        val ticket = guard.begin()
        var recorded = listOf(Entry("old"))

        guard.clear { recorded = emptyList() }
        recorded = listOf(Entry("new"))

        val merged = guard.publish(ticket, listOf(Entry("old"))) { loaded -> recorded + loaded }
        assertEquals(listOf(Entry("new")), merged)
    }

    @Test
    fun `one mutation is copied to every overlapping load`() {
        val guard = InFlightListLoadGuard<Entry>()
        val first = guard.begin()
        val second = guard.begin()

        guard.remove({ it.id == "a" }) {}

        assertEquals(listOf(Entry("b")), guard.publish(first, listOf(Entry("a"), Entry("b"))) { it })
        assertEquals(listOf(Entry("c")), guard.publish(second, listOf(Entry("a"), Entry("c"))) { it })
    }

    @Test
    fun `ending one overlapping load leaves the other tracked`() {
        val guard = InFlightListLoadGuard<Entry>()
        val ended = guard.begin()
        val active = guard.begin()
        guard.end(ended)

        guard.remove({ it.id == "a" }) {}

        assertEquals(listOf(Entry("a")), guard.publish(ended, listOf(Entry("a"))) { it })
        assertTrue(guard.publish(active, listOf(Entry("a"))) { it }.isEmpty())
    }

    @Test
    fun `a mutation before a load begins is not attached to that future load`() {
        val guard = InFlightListLoadGuard<Entry>()
        guard.remove({ it.id == "a" }) {}

        val later = guard.begin()

        assertEquals(listOf(Entry("a")), guard.publish(later, listOf(Entry("a"))) { it })
    }

    @Test
    fun `an ended ticket is not changed by later clears`() {
        val guard = InFlightListLoadGuard<Entry>()
        val ended = guard.begin()
        guard.end(ended)

        guard.clear {}

        assertEquals(listOf(Entry("a")), guard.publish(ended, listOf(Entry("a"))) { it })
    }

    @Test
    fun `ending a ticket twice is harmless`() {
        val guard = InFlightListLoadGuard<Entry>()
        val ticket = guard.begin()

        guard.end(ticket)
        guard.end(ticket)

        assertEquals(listOf(Entry("a")), guard.publish(ticket, listOf(Entry("a"))) { it })
    }

    @Test
    fun `publish returns the merge result rather than only the filtered list`() {
        val guard = InFlightListLoadGuard<Entry>()
        val ticket = guard.begin()
        guard.remove({ it.id == "a" }) {}

        val count = guard.publish(ticket, listOf(Entry("a"), Entry("b"))) { it.size }

        assertEquals(1, count)
    }
}
