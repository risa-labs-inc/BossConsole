package ai.rever.boss.window

import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ClosedTabHistory], the stack behind Cmd+Shift+T.
 */
class ClosedTabHistoryTest {
    private companion object {
        const val DOOMED_PLUGIN = "com.example.doomed"
    }

    private data class FakeTab(
        override val id: String,
        override val title: String = id,
    ) : TabInfo {
        override val typeId = TabTypeId("test", "test")
        override val icon: ImageVector = Icons.Default.Add
        override val tabIcon: TabIcon? = null
    }

    private data class PluginTab(
        override val id: String,
        override val title: String = id,
    ) : TabInfo {
        override val typeId = TabTypeId("plugin-tab", DOOMED_PLUGIN)
        override val icon: ImageVector = Icons.Default.Add
        override val tabIcon: TabIcon? = null
    }

    private val windowA = "window-a"
    private val windowB = "window-b"

    @BeforeTest
    fun reset() {
        ClosedTabHistory.clear(windowA)
        ClosedTabHistory.clear(windowB)
    }

    @Test
    fun `pops most recently closed first`() {
        ClosedTabHistory.record(windowA, FakeTab("first"))
        ClosedTabHistory.record(windowA, FakeTab("second"))

        assertEquals("second", ClosedTabHistory.pop(windowA)?.id)
        assertEquals("first", ClosedTabHistory.pop(windowA)?.id)
        assertNull(ClosedTabHistory.pop(windowA))
    }

    @Test
    fun `history is per window`() {
        ClosedTabHistory.record(windowA, FakeTab("a-tab"))

        // A different window must not be able to reopen another window's tab.
        assertNull(ClosedTabHistory.pop(windowB))
        assertEquals("a-tab", ClosedTabHistory.pop(windowA)?.id)
    }

    @Test
    fun `re-closing a reopened tab moves it to the top instead of duplicating`() {
        ClosedTabHistory.record(windowA, FakeTab("x"))
        ClosedTabHistory.record(windowA, FakeTab("y"))
        // The user reopened x and closed it again.
        ClosedTabHistory.record(windowA, FakeTab("x"))

        assertEquals("x", ClosedTabHistory.pop(windowA)?.id)
        assertEquals("y", ClosedTabHistory.pop(windowA)?.id)
        assertNull(ClosedTabHistory.pop(windowA), "x should not be on the stack twice")
    }

    @Test
    fun `history is bounded`() {
        repeat(ClosedTabHistory.MAX_ENTRIES + 10) { i ->
            ClosedTabHistory.record(windowA, FakeTab("tab-$i"))
        }

        assertEquals(ClosedTabHistory.MAX_ENTRIES, ClosedTabHistory.depths.value[windowA])

        // The oldest entries were dropped, not the newest.
        val newest = ClosedTabHistory.pop(windowA)
        assertEquals("tab-${ClosedTabHistory.MAX_ENTRIES + 9}", newest?.id)
    }

    @Test
    fun `re-closing an id deep in a full stack moves it up without changing depth`() {
        // record does removeAll -> addFirst -> trim, and the three interact: at MAX_ENTRIES the
        // dedupe has to remove before the trim, or the re-closed tab pushes the oldest entry off
        // and the depth is wrong by one. Subtle enough to pin.
        repeat(ClosedTabHistory.MAX_ENTRIES) { i -> ClosedTabHistory.record(windowA, FakeTab("tab-$i")) }
        assertEquals(ClosedTabHistory.MAX_ENTRIES, ClosedTabHistory.depths.value[windowA])

        // The oldest surviving entry, reopened and closed again.
        ClosedTabHistory.record(windowA, FakeTab("tab-0"))

        assertEquals(
            ClosedTabHistory.MAX_ENTRIES,
            ClosedTabHistory.depths.value[windowA],
            "moving an entry must not evict another",
        )
        assertEquals("tab-0", ClosedTabHistory.pop(windowA)?.id, "and it is on top")
        // Every other id is still there exactly once.
        val remaining = generateSequence { ClosedTabHistory.pop(windowA) }.map { it.id }.toList()
        assertEquals(remaining.size, remaining.distinct().size, "no duplicates")
        assertEquals((1 until ClosedTabHistory.MAX_ENTRIES).map { "tab-$it" }.toSet(), remaining.toSet())
    }

    @Test
    fun `an unloaded plugin's entries are dropped across every window`() {
        // Entries the user closed BEFORE the unload would otherwise pin the plugin's classloader,
        // and after an update hand the new factory an instance of the old class.
        ClosedTabHistory.record(windowA, FakeTab("keep-a"))
        ClosedTabHistory.record(windowA, PluginTab("doomed-a"))
        ClosedTabHistory.record(windowB, PluginTab("doomed-b"))

        ClosedTabHistory.dropEntriesFor(DOOMED_PLUGIN)

        assertEquals(1, ClosedTabHistory.depths.value[windowA])
        assertEquals("keep-a", ClosedTabHistory.pop(windowA)?.id)
        assertNull(ClosedTabHistory.depths.value[windowB], "the window's last entry was that plugin's")
        assertFalse(ClosedTabHistory.hasEntries(windowB))
    }

    @Test
    fun `depth drives the menu item's enabled state`() {
        assertFalse(ClosedTabHistory.hasEntries(windowA))
        assertNull(ClosedTabHistory.depths.value[windowA])

        ClosedTabHistory.record(windowA, FakeTab("only"))
        assertTrue(ClosedTabHistory.hasEntries(windowA))
        assertEquals(1, ClosedTabHistory.depths.value[windowA])

        ClosedTabHistory.pop(windowA)
        // Back to absent rather than 0, so the menu item greys out again.
        assertFalse(ClosedTabHistory.hasEntries(windowA))
        assertNull(ClosedTabHistory.depths.value[windowA])
    }

    @Test
    fun `closing a window drops its history`() {
        ClosedTabHistory.record(windowA, FakeTab("doomed"))
        ClosedTabHistory.clear(windowA)

        assertNull(ClosedTabHistory.pop(windowA))
        assertNull(ClosedTabHistory.depths.value[windowA])
    }

    @Test
    fun `clear leaves depth and hasEntries agreeing, and a later close starts over`() {
        // The two are read by different things - depths drives File > Reopen Closed Tab, while
        // pop and hasEntries answer the chord - so a window close that dropped one and not the
        // other would leave the item enabled for the life of the process with nothing behind it.
        // A tab closing as its window closes is exactly the interleaving that produces.
        repeat(3) { i -> ClosedTabHistory.record(windowA, FakeTab("tab-$i")) }

        ClosedTabHistory.clear(windowA)

        assertFalse(ClosedTabHistory.hasEntries(windowA))
        assertNull(ClosedTabHistory.depths.value[windowA])

        // A window id can come back (the same id is never reused today, but the state must not
        // remember anything either way).
        ClosedTabHistory.record(windowA, FakeTab("after"))
        assertEquals(1, ClosedTabHistory.depths.value[windowA])
        assertEquals("after", ClosedTabHistory.pop(windowA)?.id)
    }
}
