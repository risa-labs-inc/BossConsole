package ai.rever.boss.plugin.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Pins the entropy half of `uniqueId`: `<prefix>-<epochMillis>` alone hands one id to every
 * mint inside a single clock millisecond, and the minted ids are identity - map keys, tab
 * addressing, persisted record names - so a collision silently merges two entities.
 *
 * A fixed clock forces the whole burst into one millisecond, which is exactly the shape
 * bulk creation takes (workspace restore opening many tabs at once, bookmark import,
 * back-to-back agent calls). The old mint produced one id per millisecond; this test
 * fails against it on the first iteration.
 */
class UniqueIdTest {
    private val fixedClock =
        object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(FIXED_MILLIS)
        }

    @Test
    fun `ten thousand mints inside one millisecond are all distinct`() {
        val ids = List(10_000) { uniqueId("tab", fixedClock) }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `workspace ids are distinct and retain their persisted prefix`() {
        val ids = List(10_000) { LayoutWorkspace.generateId() }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.matches(Regex("workspace-\\d+-[0-9a-f]{16}")) })
    }

    @Test
    fun `the minted id keeps the prefix, the clock's millisecond and a hex suffix`() {
        val id = uniqueId("bookmark", fixedClock)
        val parts = id.split("-")
        assertEquals("bookmark", parts.first())
        assertEquals(FIXED_MILLIS.toString(), parts[1])
        assertEquals(16, parts.last().length)
        assertTrue(parts.last().all { it in '0'..'9' || it in 'a'..'f' })
    }

    private companion object {
        const val FIXED_MILLIS = 1_700_000_000_000L
    }
}
